package com.github.xingjianluo.chainrpcnodeindexerjava.rpc;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.ChainErrorReason;

/**
 * 对单个上游节点的 JSON-RPC 2.0 客户端。
 * <ul>
 *   <li>传输层异常（连不上/超时/HTTP 非 2xx）由 {@link RpcTransport} 抛出；</li>
 *   <li>响应体不是合法 JSON、不是对象、缺少 result 且无 error -> BAD_RESPONSE；</li>
 *   <li>响应带 JSON-RPC error 字段 -> NODE_ERROR。</li>
 * </ul>
 */
public class HttpJsonRpcClient implements JsonRpcClient {

    private static final Logger log = LoggerFactory.getLogger(HttpJsonRpcClient.class);

    private final String id;
    private final String endpoint;
    private final RpcTransport transport;
    private final long timeoutMs;
    private final ObjectMapper mapper;
    private final AtomicLong requestSeq = new AtomicLong();

    public HttpJsonRpcClient(String id, String endpoint, RpcTransport transport, long timeoutMs) {
        this(id, endpoint, transport, timeoutMs, new ObjectMapper());
    }

    public HttpJsonRpcClient(String id, String endpoint, RpcTransport transport,
                             long timeoutMs, ObjectMapper mapper) {
        this.id = id;
        this.endpoint = endpoint;
        this.transport = transport;
        this.timeoutMs = timeoutMs;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String endpoint() {
        return endpoint;
    }

    @Override
    public JsonNode call(String method, Object params) {
        long requestId = requestSeq.incrementAndGet();
        String requestBody;
        try {
            requestBody = mapper.writeValueAsString(new RpcRequest(
                    "2.0", requestId, method, params == null ? List.of() : params));
        } catch (JsonProcessingException e) {
            // 本地构造请求失败，按“无法生成合法请求/响应格式问题”对待
            throw new RpcException(ChainErrorReason.BAD_RESPONSE,
                    "节点 " + id + " 请求序列化失败: " + e.getMessage(), e);
        }

        String raw = transport.post(id, endpoint, requestBody, timeoutMs);

        JsonNode root;
        try {
            root = mapper.readTree(raw);
        } catch (JsonProcessingException e) {
            log.warn("[rpc] node={} method={} 响应不是合法 JSON: {}", id, method, abbreviate(raw));
            throw new RpcException(ChainErrorReason.BAD_RESPONSE,
                    "节点 " + id + " 返回内容不是合法 JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            log.warn("[rpc] node={} method={} 响应不是 JSON 对象: {}", id, method, abbreviate(raw));
            throw new RpcException(ChainErrorReason.BAD_RESPONSE,
                    "节点 " + id + " 返回内容格式不对：根节点不是 JSON 对象");
        }

        JsonNode errorNode = root.get("error");
        if (errorNode != null && !errorNode.isNull()) {
            int code = errorNode.path("code").asInt(-1);
            String message = errorNode.path("message").asText("(无错误描述)");
            log.warn("[rpc] node={} method={} 返回 JSON-RPC error: code={} message={}",
                    id, method, code, message);
            throw new RpcException(ChainErrorReason.NODE_ERROR,
                    "节点 " + id + " 返回错误 code=" + code + " message=" + message);
        }

        JsonNode result = root.get("result");
        if (result == null || result.isNull()) {
            log.warn("[rpc] node={} method={} 响应缺少 result 字段: {}", id, method, abbreviate(raw));
            throw new RpcException(ChainErrorReason.BAD_RESPONSE,
                    "节点 " + id + " 返回内容格式不对：缺少 result 字段");
        }
        return result;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "(空响应体)";
        }
        return s.length() <= 120 ? s : s.substring(0, 120) + "...";
    }

    /** JSON-RPC 2.0 请求信封。 */
    private record RpcRequest(String jsonrpc, long id, String method, Object params) {
    }
}
