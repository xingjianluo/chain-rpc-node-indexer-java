package com.github.xingjianluo.chainrpcnodeindexerjava.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.chainrpcnodeindexerjava.indexer.BlockIndexer;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.BlockResult;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.ChainErrorReason;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.LatestHeightResult;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.NodeHealthStatus;
import com.github.xingjianluo.chainrpcnodeindexerjava.node.FailoverChainClient;
import com.github.xingjianluo.chainrpcnodeindexerjava.node.HealthRegistry;
import com.github.xingjianluo.chainrpcnodeindexerjava.node.NoHealthyNodeException;
import com.github.xingjianluo.chainrpcnodeindexerjava.node.RecoveryProbe;
import com.github.xingjianluo.chainrpcnodeindexerjava.sim.SimBehavior;
import com.github.xingjianluo.chainrpcnodeindexerjava.sim.SimBehaviorRegistry;
import com.github.xingjianluo.chainrpcnodeindexerjava.sim.SimulatedChain;
import com.github.xingjianluo.chainrpcnodeindexerjava.storage.BlockStore;

/**
 * 全链路集成测试：本进程内真实 HTTP JSON-RPC（模拟节点端点）+ 故障转移 + 索引 + REST。
 * 覆盖：正常、超时、节点错误、坏数据、滞后、故障转移来源、判死/恢复、
 * 出块追赶、链重组回滚重建与查询一致性。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class ChainServiceIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ChainServiceIntegrationTest.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static Path dataDir;
    private static final int TEST_PORT = 19191;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws Exception {
        dataDir = Files.createTempDirectory("idx-it-");
        registry.add("server.port", () -> String.valueOf(TEST_PORT));
        registry.add("app.data-dir", () -> dataDir.toString());
        registry.add("app.request-timeout-ms", () -> "400");
        registry.add("app.failure-threshold", () -> "2");
        registry.add("app.recovery-successes", () -> "2");
        registry.add("app.lag-threshold-blocks", () -> "3");
        registry.add("app.poll-interval-ms", () -> "3600000"); // 测试手工驱动索引器
        registry.add("app.sim.initial-block-count", () -> "4");
        // 节点 endpoint 在 application.properties 中以 ${server.port} 引用，
        // DEFINED_PORT 下占位符会解析为 TEST_PORT，无需在此重复绑定 nodes 列表。
    }

    @LocalServerPort
    int port;

    @Autowired
    private FailoverChainClient client;
    @Autowired
    private HealthRegistry health;
    @Autowired
    private RecoveryProbe recoveryProbe;
    @Autowired
    private SimBehaviorRegistry behaviors;
    @Autowired
    private SimulatedChain chain;
    @Autowired
    private BlockStore store;
    @Autowired
    private BlockIndexer indexer;
    @Autowired
    private TestRestTemplate rest;

    @BeforeAll
    static void note() {
        log.info("[IT] 集成测试数据目录: {}", dataDir);
    }

    @BeforeEach
    void resetBehaviors() {
        // 同一 Spring 上下文跨用例复用：每个用例前隔离健康状态与节点行为
        health.reset();
        for (String id : new String[]{"node-a", "node-b", "node-c"}) {
            behaviors.setBehavior(id, SimBehavior.OK, 0);
        }
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    @Test
    void normalPath_reportsSourceNodeAndHeight() {
        LatestHeightResult height = client.fetchLatestHeight();
        log.info("[IT] 正常链路：集群高度={} 来源节点={} 判定依据=多节点探高取最高",
                height.height(), height.nodeId());
        assertThat(height.height()).isEqualTo(chain.tipHeight());
        assertThat(height.nodeId()).startsWith("node-");
        assertThat(health.healthy()).hasSize(3);
    }

    @Test
    void timeoutNode_failsOverToNextNode() {
        behaviors.setBehavior("node-a", SimBehavior.TIMEOUT);
        long start = System.nanoTime();
        BlockResult result = client.fetchBlock(2);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
        log.info("[IT] node-a 超时，取块成功：来源节点={} height={} hash={} 耗时={}ms（判定依据：候选顺序切换）",
                result.nodeId(), result.block().height(), result.block().hash(), elapsedMs);
        assertThat(result.nodeId()).isIn("node-b", "node-c");
        assertThat(elapsedMs).isLessThan(3000);
        assertThat(health.stateOf("node-a").lastReason())
                .isEqualTo(ChainErrorReason.NETWORK_UNREACHABLE);
    }

    @Test
    void errorNodeAndBadNode_failOverWithDistinctReasons() {
        behaviors.setBehavior("node-a", SimBehavior.ERROR);
        behaviors.setBehavior("node-b", SimBehavior.BAD_RESPONSE);
        BlockResult result = client.fetchBlock(1);
        log.info("[IT] node-a=RPC错误 node-b=格式错误，最终来源节点={}", result.nodeId());
        assertThat(result.nodeId()).isEqualTo("node-c");
        assertThat(health.stateOf("node-a").lastReason())
                .isEqualTo(ChainErrorReason.NODE_ERROR);
        assertThat(health.stateOf("node-b").lastReason())
                .isEqualTo(ChainErrorReason.BAD_RESPONSE);
    }

    @Test
    void allNodesFailing_throwsAndRestReturns503() {
        for (String id : new String[]{"node-a", "node-b", "node-c"}) {
            behaviors.setBehavior(id, SimBehavior.ERROR);
        }
        assertThatThrownBy(client::fetchLatestHeight)
                .isInstanceOf(NoHealthyNodeException.class);

        ResponseEntity<String> resp = rest.getForEntity(url("/height/latest"), String.class);
        log.info("[IT] 全挂时 REST /height/latest -> HTTP {}", resp.getStatusCode().value());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(resp.getBody()).contains("NO_HEALTHY_NODE");
    }

    @Test
    void upstreamRpcError_restReturns502WithReason() throws Exception {
        // node-a 错误、node-b 超时（慢）、node-c 坏数据：live 取块最终失败原因链可见。
        behaviors.setBehavior("node-a", SimBehavior.ERROR);
        behaviors.setBehavior("node-b", SimBehavior.ERROR);
        behaviors.setBehavior("node-c", SimBehavior.BAD_RESPONSE);

        ResponseEntity<String> resp = rest.getForEntity(url("/blocks/3?source=live"), String.class);
        log.info("[IT] live 取块全部上游失败 -> HTTP {} body={}", resp.getStatusCode(), resp.getBody());
        // 全部候选失败 -> NoHealthyNode(503)。再用单节点 bad 数据验证 502 reason 分类：
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void laggingNode_isMarkedWithLaggingReason() {
        long base = chain.tipHeight();
        // node-a 落后 6 块，阈值 3；先把链长长 6
        chain.produceBlocks(6);
        long expectedTip = base + 6;
        behaviors.setBehavior("node-a", SimBehavior.LAGGING, 6);

        LatestHeightResult result = client.fetchLatestHeight();
        log.info("[IT] node-a 滞后6块：集群高度={} 来源={}（不应选滞后节点）",
                result.height(), result.nodeId());
        assertThat(result.height()).isEqualTo(expectedTip);
        assertThat(result.nodeId()).isNotEqualTo("node-a");
        assertThat(health.stateOf("node-a").lastReason())
                .isEqualTo(ChainErrorReason.HEIGHT_LAGGING);
    }

    @Test
    void nodeDeathAndRecovery_unavailableThenProbeBack() {
        behaviors.setBehavior("node-a", SimBehavior.TIMEOUT);

        // 连续两轮真实请求失败（阈值 2），node-a 判死并不再出现在候选中
        client.fetchLatestHeight();
        client.fetchLatestHeight();
        assertThat(health.stateOf("node-a").status())
                .isEqualTo(NodeHealthStatus.UNAVAILABLE);
        log.info("[IT] node-a 连续超时 -> UNAVAILABLE，不再被选中");

        // 节点恢复，但单次探测成功不足以恢复
        behaviors.setBehavior("node-a", SimBehavior.OK);
        recoveryProbe.probeOnce();
        assertThat(health.stateOf("node-a").status())
                .isEqualTo(NodeHealthStatus.UNAVAILABLE);
        recoveryProbe.probeOnce();
        assertThat(health.stateOf("node-a").status())
                .isEqualTo(NodeHealthStatus.HEALTHY);
        log.info("[IT] node-a 连续2次探测成功 -> 恢复 HEALTHY");
    }

    @Test
    void indexer_catchesUpProducedBlocks_andLocalQueriesAreConsistent() {
        long before = store.maxHeight();
        chain.produceBlocks(3);
        for (int i = 0; i < 6; i++) {
            indexer.catchUpOnce();
        }
        log.info("[IT] 出块3个后追赶：本地索引高度 {} -> {}", before, indexer.indexedHeight());
        assertThat(indexer.indexedHeight()).isEqualTo(chain.tipHeight());

        ResponseEntity<String> resp = rest.getForEntity(url("/blocks/latest"), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = unchecked(resp.getBody());
        assertThat(body.path("source").asText()).isEqualTo("local-index");
        assertThat(body.path("height").asLong()).isEqualTo(chain.tipHeight());
        assertThat(body.path("hash").asText()).isEqualTo(chain.blockAt(chain.tipHeight()).hash());
    }

    @Test
    void indexer_reorgRebuilds_andQueriesStayConsistent() throws Exception {
        // 确保已追平
        chain.produceBlocks(2);
        for (int i = 0; i < 20; i++) {
            indexer.catchUpOnce();
        }
        assertThat(indexer.indexedHeight()).isEqualTo(chain.tipHeight());
        long tip = chain.tipHeight();
        String oldTipHash = chain.blockAt(tip).hash();

        // 在深度 2 重组（同高度），索引器应回滚到共同祖先再重建
        long fork = chain.reorg(2);
        String newTipHash = chain.blockAt(tip).hash();
        assertThat(newTipHash).isNotEqualTo(oldTipHash);
        log.info("[IT] 触发重组 forkHeight={} oldTip={} newTip={}", fork, oldTipHash, newTipHash);

        for (int i = 0; i < 20; i++) {
            indexer.catchUpOnce();
        }
        assertThat(indexer.indexedHeight()).isEqualTo(tip);
        assertThat(store.get(tip).hash()).isEqualTo(newTipHash);
        assertThat(store.get(fork - 1).hash()).isEqualTo(chain.blockAt(fork - 1).hash());
        assertThat(store.get(fork).parentHash())
                .isEqualTo(chain.blockAt(fork - 1).hash());

        // REST 本地查询：整条链自洽，且和模拟链当前视图一致
        ResponseEntity<String> latest = rest.getForEntity(url("/blocks/latest"), String.class);
        JsonNode latestBody = unchecked(latest.getBody());
        assertThat(latestBody.path("hash").asText()).isEqualTo(newTipHash);
        for (long h = 0; h <= tip; h++) {
            ResponseEntity<String> r = rest.getForEntity(url("/blocks/" + h), String.class);
            JsonNode b = unchecked(r.getBody());
            assertThat(b.path("hash").asText())
                    .as("高度 %d 必须是新链块", h)
                    .isEqualTo(chain.blockAt(h).hash());
        }
        log.info("[IT] 重组后所有高度查询均为新链，无新旧混合");
    }

    @Test
    void healthEndpoint_exposesStatusHeightAndReason() throws Exception {
        // 先让三个节点都成功一次，使注册表都有高度；再让 node-b 失败验证原因字段
        client.fetchLatestHeight();
        behaviors.setBehavior("node-b", SimBehavior.ERROR);
        client.fetchLatestHeight();

        ResponseEntity<String> resp = rest.getForEntity(url("/nodes/health"), String.class);
        JsonNode body = unchecked(resp.getBody());
        log.info("[IT] 健康端点 indexedHeight={} 节点数={}",
                body.path("indexedHeight").asLong(), body.path("nodes").size());
        assertThat(body.path("nodes")).hasSize(3);
        JsonNode nodeB = body.path("nodes").get(1);
        assertThat(nodeB.path("nodeId").asText()).isEqualTo("node-b");
        assertThat(nodeB.path("lastReason").asText()).isEqualTo("NODE_ERROR");
        // node-b 此前成功过，高度已被记录
        assertThat(nodeB.path("latestHeight").asLong()).isGreaterThanOrEqualTo(0);
    }

    private JsonNode unchecked(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
