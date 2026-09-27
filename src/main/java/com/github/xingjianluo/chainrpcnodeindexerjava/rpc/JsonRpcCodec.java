package com.github.xingjianluo.chainrpcnodeindexerjava.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.xingjianluo.chainrpcnodeindexerjava.chain.Block;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * JSON-RPC 2.0 请求构造与响应解析。
 *
 * <p>解析时严格区分两类失败：
 * <ul>
 *     <li>{@link FailureReason#RPC_ERROR}：响应是合法 JSON-RPC，但带 {@code error} 字段；</li>
 *     <li>{@link FailureReason#BAD_RESPONSE}：响应不是合法 JSON、不符合 JSON-RPC 结构、
 *     或 result 内容缺字段/类型不对/高度对不上。</li>
 * </ul>
 */
public final class JsonRpcCodec {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonRpcCodec() {
    }

    /** 构造 JSON-RPC 2.0 请求体。params 作为位置参数数组。 */
    public static String buildRequest(String method, List<?> params) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.put("id", 1);
        root.put("method", method);
        root.set("params", MAPPER.valueToTree(params));
        return root.toString();
    }

    /**
     * 从原始响应文本中取出 result 节点（result 允许为 null）。
     *
     * @throws RpcException RPC_ERROR 节点返回 error；BAD_RESPONSE 内容无法解析
     */
    public static JsonNode extractResult(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            throw new RpcException(FailureReason.BAD_RESPONSE, "空响应体");
        }
        final JsonNode root;
        try {
            root = MAPPER.readTree(rawResponse.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RpcException(FailureReason.BAD_RESPONSE, "响应不是合法 JSON: " + rawResponse, e);
        }
        if (root == null || !root.isObject() || !root.has("jsonrpc")) {
            throw new RpcException(FailureReason.BAD_RESPONSE, "不是合法 JSON-RPC 响应: " + rawResponse);
        }
        JsonNode error = root.get("error");
        if (error != null && !error.isNull()) {
            int code = error.path("code").asInt(-1);
            String message = error.path("message").asText("unknown error");
            throw new RpcException(FailureReason.RPC_ERROR, "节点返回错误 code=" + code + ": " + message);
        }
        return root.get("result");
    }

    /** 解析区块高度；要求是整数（可接受字符串形式的十进制整数）。 */
    public static long toHeight(JsonNode result) {
        if (result == null || result.isNull()) {
            throw new RpcException(FailureReason.BAD_RESPONSE, "高度 result 为空");
        }
        try {
            if (result.isNumber()) {
                return result.asLong();
            }
            if (result.isTextual()) {
                return Long.parseLong(result.asText());
            }
        } catch (NumberFormatException e) {
            throw new RpcException(FailureReason.BAD_RESPONSE, "无法解析高度: " + result, e);
        }
        throw new RpcException(FailureReason.BAD_RESPONSE, "高度字段类型不对: " + result);
    }

    /**
     * 解析区块对象，并校验高度与请求高度一致（防止节点错位返回）。
     */
    public static Block toBlock(JsonNode result, long requestedHeight) {
        if (result == null || !result.isObject()) {
            throw new RpcException(FailureReason.BAD_RESPONSE, "区块 result 为空或不是对象");
        }
        Long height;
        JsonNode heightNode = result.get("height");
        if (heightNode == null || heightNode.isNull()) {
            throw new RpcException(FailureReason.BAD_RESPONSE, "区块缺少 height 字段: " + result);
        }
        try {
            height = heightNode.isNumber() ? heightNode.asLong() : Long.parseLong(heightNode.asText());
        } catch (NumberFormatException e) {
            throw new RpcException(FailureReason.BAD_RESPONSE, "区块 height 无法解析: " + result, e);
        }
        if (height != requestedHeight) {
            throw new RpcException(FailureReason.BAD_RESPONSE,
                    "区块高度不匹配，请求=" + requestedHeight + " 实际=" + height);
        }
        String hash = textField(result, "hash");
        String parentHash = result.path("parentHash").asText(null);
        if (parentHash == null) {
            throw new RpcException(FailureReason.BAD_RESPONSE, "区块缺少 parentHash 字段: " + result);
        }
        JsonNode tsNode = result.get("timestamp");
        if (tsNode == null || !tsNode.isNumber()) {
            throw new RpcException(FailureReason.BAD_RESPONSE, "区块缺少数值型 timestamp: " + result);
        }
        String payload = result.path("payload").asText("");
        if (height > 0 && parentHash.isBlank()) {
            throw new RpcException(FailureReason.BAD_RESPONSE, "非创世区块 parentHash 为空: " + result);
        }
        return new Block(height, hash, parentHash, tsNode.asLong(), payload);
    }

    private static String textField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new RpcException(FailureReason.BAD_RESPONSE, "区块缺少非空文本字段 " + field + ": " + node);
        }
        return value.asText();
    }
}
