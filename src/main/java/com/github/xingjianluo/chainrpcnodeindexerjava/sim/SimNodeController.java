package com.github.xingjianluo.chainrpcnodeindexerjava.sim;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.Block;

/**
 * 模拟上游 JSON-RPC 节点：POST /sim/{nodeId}。
 * <p>
 * 根据该 nodeId 在 {@link SimBehaviorRegistry} 中的行为返回：
 * <ul>
 *   <li>OK —— 正常 JSON-RPC 2.0 结果；</li>
 *   <li>LAGGING —— 暴露的高度比链尖低 N 块（N 可设置）；</li>
 *   <li>TIMEOUT —— 阻塞住请求不返回（由客户端超时断开），模拟网络超时；</li>
 *   <li>ERROR —— 返回 JSON-RPC error；</li>
 *   <li>BAD_RESPONSE —— 返回不可解析/字段缺失的响应体。</li>
 * </ul>
 */
@RestController
public class SimNodeController {

    private static final Logger log = LoggerFactory.getLogger(SimNodeController.class);

    private final SimulatedChain chain;
    private final SimBehaviorRegistry behaviors;
    private final ObjectMapper mapper = new ObjectMapper();

    public SimNodeController(SimulatedChain chain, SimBehaviorRegistry behaviors) {
        this.chain = chain;
        this.behaviors = behaviors;
    }

    @PostMapping(value = "/sim/{nodeId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> handle(@PathVariable String nodeId,
                                         @RequestBody(required = false) String raw) {
        SimBehavior behavior = behaviors.behaviorOf(nodeId);
        log.info("[sim-node] node={} behavior={} body={}", nodeId, behavior, abbreviate(raw));

        JsonNode request;
        try {
            request = raw == null ? null : mapper.readTree(raw);
        } catch (Exception e) {
            return rpcError(-32700, "Parse error");
        }
        String method = request == null ? null : request.path("method").asText(null);
        JsonNode idNode = request == null ? null : request.get("id");
        long rpcId = idNode == null || idNode.isNull() ? 1L : idNode.asLong(1L);

        try {
            switch (behavior) {
                case TIMEOUT -> {
                    // 模拟节点挂起：睡眠足够久，让客户端先超时
                    Thread.sleep(60_000L);
                    return rpcResult(rpcId, null);
                }
                case ERROR -> {
                    return ResponseEntity.status(500)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(mapper.writeValueAsString(Map.of(
                                    "jsonrpc", "2.0",
                                    "id", rpcId,
                                    "error", Map.of("code", -32000,
                                            "message", "simulated node failure for " + nodeId))));
                }
                case BAD_RESPONSE -> {
                    // 既非合法 JSON，也没有 result/error，触发 BAD_RESPONSE
                    return ResponseEntity.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{this-is-not-valid-json from " + nodeId);
                }
                case LAGGING -> {
                    return ResponseEntity.ok(handleMethod(nodeId, method, request, rpcId,
                            behaviors.lagOf(nodeId)));
                }
                case OK, UNREACHABLE -> {
                    // UNREACHABLE 通过把 endpoint 指向未监听端口模拟，走不到这里
                    return ResponseEntity.ok(handleMethod(nodeId, method, request, rpcId, 0));
                }
                default -> {
                    return rpcError(-32603, "unknown behavior");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return rpcError(-32603, "interrupted");
        } catch (Exception e) {
            log.warn("[sim-node] node={} 处理异常: {}", nodeId, e.toString());
            return rpcError(-32603, e.getMessage());
        }
    }

    private String handleMethod(String nodeId, String method, JsonNode request,
                                long rpcId, int lagBlocks) throws Exception {
        long tip = chain.tipHeight();
        long effectiveTip = Math.max(-1L, tip - Math.max(0, lagBlocks));

        if ("chain_blockNumber".equals(method)) {
            return mapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0", "id", rpcId, "result", effectiveTip));
        }
        if ("chain_getBlockByNumber".equals(method)) {
            JsonNode params = request.path("params");
            long height = params.get(0).asLong();
            if (height > effectiveTip) {
                return mapper.writeValueAsString(Map.of(
                        "jsonrpc", "2.0", "id", rpcId, "result", null));
            }
            Block block = chain.blockAt(height);
            Object result = block == null ? null : Map.of(
                    "number", block.height(),
                    "hash", block.hash(),
                    "parentHash", block.parentHash(),
                    "timestamp", block.timestamp(),
                    "payload", block.payload() == null ? "" : block.payload());
            return mapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0", "id", rpcId, "result", result));
        }
        return mapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "id", rpcId,
                "error", Map.of("code", -32601, "message", "method not found: " + method)));
    }

    private ResponseEntity<String> rpcResult(long rpcId, Object result) {
        try {
            return ResponseEntity.ok(mapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0", "id", rpcId, "result", result)));
        } catch (Exception e) {
            return rpcError(-32603, e.getMessage());
        }
    }

    private ResponseEntity<String> rpcError(int code, String message) {
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(Map.of(
                            "jsonrpc", "2.0", "id", 1,
                            "error", Map.of("code", code, "message", message))));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("{\"jsonrpc\":\"2.0\"}");
        }
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 100 ? s : s.substring(0, 100) + "...";
    }
}
