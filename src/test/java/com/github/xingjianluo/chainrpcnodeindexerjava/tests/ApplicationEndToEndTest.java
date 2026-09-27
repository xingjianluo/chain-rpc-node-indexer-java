package com.github.xingjianluo.chainrpcnodeindexerjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 全链路：Spring 启动（随机端口 + 独立测试数据目录），后台任务真实运行，
 * 通过 REST 验证索引推进、来源节点、健康视图。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.data-dir=./target/e2e-data",
                "app.simulated.chain-height=5",
                "app.simulated.mine-interval-millis=600000",
                "app.indexer.tick-millis=200",
                "app.health.probe-interval-millis=200"
        })
class ApplicationEndToEndTest {

    private static final Logger log = LoggerFactory.getLogger(ApplicationEndToEndTest.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    private JsonNode get(String path) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), path + " -> " + resp.body());
        return mapper.readTree(resp.body());
    }

    @Test
    void fullStackIndexesChainAndExposesSourceAndHealth() throws Exception {
        // 等待后台索引器追平初始链（高度 4）
        long indexed = -1;
        for (int i = 0; i < 50; i++) {
            indexed = get("/api/chain/head").path("indexedHeight").asLong();
            if (indexed >= 4) {
                break;
            }
            Thread.sleep(100);
        }
        assertEquals(4L, indexed);
        log.info("[E2E] 后台索引追平，当前索引高度={}", indexed);

        JsonNode heightResp = get("/api/height");
        assertEquals(4L, heightResp.path("height").asLong());
        String source = heightResp.path("sourceNode").asText();
        assertNotNull(source);
        assertTrue(source.startsWith("node-"));
        log.info("[E2E] /api/height 高度=4 选中节点={} 节点高度={}",
                source, heightResp.path("nodeHeight").asLong());

        JsonNode block = get("/api/blocks/2");
        assertEquals(source, block.path("sourceNode").asText());
        assertEquals(2, block.path("block").path("height").asInt());

        JsonNode indexedBlock = get("/api/chain/blocks/2");
        assertTrue(indexedBlock.path("found").asBoolean());
        assertEquals(2, indexedBlock.path("block").path("height").asInt());

        JsonNode nodes = get("/api/nodes");
        assertTrue(nodes.isArray());
        assertEquals(3, nodes.size());
        // 等待健康探测把预置滞后的 node-c 标记为 LAGGING
        boolean seenLagging = false;
        for (int i = 0; i < 30; i++) {
            JsonNode latest = get("/api/nodes");
            seenLagging = false;
            for (JsonNode n : latest) {
                if ("LAGGING".equals(n.path("status").asText())
                        && "node-c".equals(n.path("node").asText())) {
                    seenLagging = true;
                }
            }
            if (seenLagging) {
                break;
            }
            Thread.sleep(100);
        }
        for (JsonNode n : nodes) {
            log.info("[E2E] 节点={} 状态={} 最新高度={} 连续失败={} 连续成功={} 判定依据={}",
                    n.path("node").asText(), n.path("status").asText(),
                    n.path("latestHeight").asLong(), n.path("consecutiveFailures").asInt(),
                    n.path("consecutiveSuccesses").asInt(), n.path("lastReason").asText());
            if ("LAGGING".equals(n.path("status").asText())) {
                seenLagging = true;
                assertEquals("node-c", n.path("node").asText());
            }
        }
        assertTrue(seenLagging, "预置滞后的 node-c 应被标记为 LAGGING");
    }
}
