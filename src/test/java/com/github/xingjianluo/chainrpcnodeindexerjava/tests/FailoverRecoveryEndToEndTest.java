package com.github.xingjianluo.chainrpcnodeindexerjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.SimulatedUpstreamNode;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.UpstreamNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 通过 REST 验证：主节点短暂故障时自动切换、偶发抖动不判死、恢复后重新被选中。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.data-dir=./target/e2e-failover-data",
                "app.simulated.chain-height=5",
                "app.simulated.mine-interval-millis=600000",
                "app.indexer.tick-millis=600000",
                "app.health.probe-interval-millis=100",
                "app.health.probe-cooldown-millis=100",
                "app.health.fail-threshold=3",
                "app.health.recover-successes=2"
        })
class FailoverRecoveryEndToEndTest {

    private static final Logger log = LoggerFactory.getLogger(FailoverRecoveryEndToEndTest.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    private List<UpstreamNode> nodes;

    private SimulatedUpstreamNode named(String name) {
        return nodes.stream().filter(n -> n.name().equals(name))
                .map(n -> (SimulatedUpstreamNode) n).findFirst().orElseThrow();
    }

    private JsonNode get(String path) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), resp.body());
        return mapper.readTree(resp.body());
    }

    @Test
    void failsOverOnOutageAndReturnsWhenRecovered() throws Exception {
        String first = get("/api/height").path("sourceNode").asText();
        String other = first.equals("node-a") ? "node-b" : "node-a";
        log.info("[E2E故障转移] 初始选中节点={}", first);

        // 主节点持续故障，连续失败后应剔除并切到另一节点
        named(first).setAlwaysError(true);
        String usedAfterFailover = first;
        for (int i = 0; i < 10; i++) {
            Thread.sleep(80);
            usedAfterFailover = get("/api/height").path("sourceNode").asText();
            if (other.equals(usedAfterFailover)) {
                break;
            }
        }
        assertEquals(other, usedAfterFailover, "主节点持续故障后必须切到其它健康节点");
        log.info("[E2E故障转移] 主节点故障，请求切到节点={}", usedAfterFailover);

        // 恢复：需要连续多次成功，而不是单次
        named(first).resetFaults();
        String recoveredNode = other;
        for (int i = 0; i < 30; i++) {
            Thread.sleep(80);
            // 只有主节点恢复可用，响应才可能再次来自它
            recoveredNode = get("/api/height").path("sourceNode").asText();
            if (first.equals(recoveredNode)) {
                break;
            }
        }
        assertEquals(first, recoveredNode, "连续成功恢复后应重新选中原节点");
        log.info("[E2E恢复] 节点 {} 连续成功后恢复选中", recoveredNode);

        // 偶发一次抖动：不应导致切换/判死
        named(first).failNext(1, com.github.xingjianluo.chainrpcnodeindexerjava.rpc.FailureReason.CONNECTIVITY);
        String jitterSource = get("/api/height").path("sourceNode").asText();
        // 本次请求可能切走一次，但节点状态不能被打死；连续多次请求总体应回到主节点
        int firstWins = 0;
        for (int i = 0; i < 6; i++) {
            Thread.sleep(60);
            if (first.equals(get("/api/height").path("sourceNode").asText())) {
                firstWins++;
            }
        }
        assertNotEquals(0, firstWins, "偶发抖动后节点应快速恢复参与选主");
        log.info("[E2E抖动] 一次 CONNECTIVITY 后节点 {} 仍在候选中，后续 {} 次选到它",
                first, firstWins);
    }
}
