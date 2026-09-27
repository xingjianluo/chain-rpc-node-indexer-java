package com.github.xingjianluo.chainrpcnodeindexerjava.rpc;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.Block;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.ChainErrorReason;

/**
 * {@link ChainNode} 默认实现。
 * <p>
 * 使用模拟但贴近以太坊风格的两个方法：
 * <ul>
 *   <li>{@code chain_blockNumber()} -> 结果为整数或 0x 十六进制字符串；</li>
 *   <li>{@code chain_getBlockByNumber(params:[height, false])}
 *       -> 结果为 null（高度不存在）或包含
 *       number/hash/parentHash/timestamp/payload 的对象。</li>
 * </ul>
 * 字段缺失、类型无法识别时抛 {@link ChainErrorReason#BAD_RESPONSE}。
 */
public class DefaultChainNode implements ChainNode {

    private static final Logger log = LoggerFactory.getLogger(DefaultChainNode.class);

    private final JsonRpcClient client;

    public DefaultChainNode(JsonRpcClient client) {
        this.client = client;
    }

    @Override
    public String id() {
        return client.id();
    }

    @Override
    public String endpoint() {
        return client.endpoint();
    }

    @Override
    public long fetchLatestHeight() {
        JsonNode result = client.call("chain_blockNumber", List.of());
        long height = parseHeight(result, "chain_blockNumber");
        log.debug("[node] {} 最新高度={}", id(), height);
        return height;
    }

    @Override
    public Block fetchBlock(long height) {
        JsonNode result = client.call("chain_getBlockByNumber",
                List.of(height, false));
        if (result.isNull()) {
            return null;
        }
        if (!result.isObject()) {
            throw new RpcException(ChainErrorReason.BAD_RESPONSE,
                    "节点 " + id() + " 返回区块格式不对：result 不是对象（height=" + height + "）");
        }
        try {
            long number = parseHeight(result.get("number"), "block.number");
            String hash = requireText(result, "hash", height);
            String parentHash = requireText(result, "parentHash", height);
            long timestamp = parseHeight(result.get("timestamp"), "block.timestamp");
            JsonNode payloadNode = result.get("payload");
            String payload = payloadNode == null || payloadNode.isNull() ? "" : payloadNode.asText("");
            Block block = new Block(number, hash, parentHash, timestamp, payload);
            log.debug("[node] {} 取块 height={} hash={}", id(), height, hash);
            return block;
        } catch (RpcException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RpcException(ChainErrorReason.BAD_RESPONSE,
                    "节点 " + id() + " 返回区块字段无法解析（height=" + height + "）: " + e.getMessage(), e);
        }
    }

    private long parseHeight(JsonNode node, String fieldDesc) {
        if (node == null || node.isNull()) {
            throw new RpcException(ChainErrorReason.BAD_RESPONSE,
                    "节点 " + id() + " 返回内容格式不对：缺少 " + fieldDesc);
        }
        if (node.isNumber()) {
            return node.asLong();
        }
        if (node.isTextual()) {
            String text = node.asText().trim();
            try {
                if (text.startsWith("0x") || text.startsWith("0X")) {
                    return Long.parseUnsignedLong(text.substring(2), 16);
                }
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                throw new RpcException(ChainErrorReason.BAD_RESPONSE,
                        "节点 " + id() + " 返回内容格式不对：" + fieldDesc + " 不是合法高度值: " + text);
            }
        }
        throw new RpcException(ChainErrorReason.BAD_RESPONSE,
                "节点 " + id() + " 返回内容格式不对：" + fieldDesc + " 既非数字也非字符串");
    }

    private String requireText(JsonNode result, String field, long height) {
        JsonNode node = result.get(field);
        if (node == null || node.isNull() || !node.isTextual() || node.asText().isBlank()) {
            throw new RpcException(ChainErrorReason.BAD_RESPONSE,
                    "节点 " + id() + " 返回区块缺少 " + field + "（height=" + height + "）");
        }
        return node.asText();
    }
}
