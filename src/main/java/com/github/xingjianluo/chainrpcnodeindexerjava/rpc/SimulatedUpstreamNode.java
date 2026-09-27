package com.github.xingjianluo.chainrpcnodeindexerjava.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.xingjianluo.chainrpcnodeindexerjava.chain.Block;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 本地模拟 JSON-RPC 节点。
 *
 * <p>为了让测试覆盖真实的解析路径，节点内部先拼出 JSON-RPC 2.0 文本响应，
 * 再由 {@link JsonRpcCodec} 解析；故障注入可以产生超时/连接异常、JSON-RPC error
 * 以及格式错误的文本。滞后节点看到的链高度被固定压低。
 */
public class SimulatedUpstreamNode implements UpstreamNode {

    private record Fault(FailureReason reason, String detail) {
    }

    private final String name;
    private final SimulatedChain chain;
    private final long timeoutMillis;
    private final Deque<Fault> upcomingFaults = new ArrayDeque<>();

    private volatile long latencyMillis;
    private volatile long lagBy;
    private volatile boolean alwaysError;
    private volatile boolean alwaysMalformed;

    public SimulatedUpstreamNode(String name, SimulatedChain chain, long timeoutMillis) {
        this.name = name;
        this.chain = chain;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public long timeoutMillis() {
        return timeoutMillis;
    }

    public void setLatencyMillis(long latencyMillis) {
        this.latencyMillis = latencyMillis;
    }

    /** 节点对外暴露的高度 = 链真实高度 - lagBy（不能低于 0）。 */
    public void setLagBy(long lagBy) {
        this.lagBy = Math.max(0, lagBy);
    }

    public long lagBy() {
        return lagBy;
    }

    public void setAlwaysError(boolean alwaysError) {
        this.alwaysError = alwaysError;
    }

    public void setAlwaysMalformed(boolean alwaysMalformed) {
        this.alwaysMalformed = alwaysMalformed;
    }

    /** 接下来 n 次请求按指定原因失败，按调用顺序生效（用于模拟偶发抖动）。 */
    public synchronized void failNext(int n, FailureReason reason) {
        String detail = switch (reason) {
            case CONNECTIVITY -> "模拟连接失败/超时";
            case RPC_ERROR -> "模拟节点内部错误";
            case BAD_RESPONSE -> "模拟返回格式错误";
            case LAGGING -> "模拟高度落后";
        };
        for (int i = 0; i < n; i++) {
            upcomingFaults.addLast(new Fault(reason, detail));
        }
    }

    public synchronized void resetFaults() {
        upcomingFaults.clear();
        latencyMillis = 0;
        alwaysError = false;
        alwaysMalformed = false;
    }

    @Override
    public long getLatestHeight() {
        JsonNode result = invoke("chain_getLatestHeight", List.of(), -1L);
        return JsonRpcCodec.toHeight(result.get("height"));
    }

    @Override
    public Block getBlockByHeight(long height) {
        JsonNode result = invoke("chain_getBlockByHeight", List.of(height), height);
        return JsonRpcCodec.toBlock(result, height);
    }

    private JsonNode invoke(String method, List<?> params, long requestedHeight) {
        sleepLatency();
        Fault fault = nextFault();
        if (fault != null) {
            throw faultException(fault);
        }
        if (alwaysError) {
            throw new RpcException(FailureReason.RPC_ERROR, "[" + name + "] 节点被设置为持续报错");
        }
        long servedHeight = Math.max(0L, chain.height() - lagBy);

        // 真的产出“坏内容”并经过 codec 解析，模拟返回内容格式不对（对所有方法生效）
        if (alwaysMalformed) {
            long wanted = "chain_getLatestHeight".equals(method) ? -1L
                    : ((Number) params.get(0)).longValue();
            String raw = wanted < 0
                    ? "{not-valid-json"
                    : "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{"
                        + "\"height\":" + wanted + ",\"parentHash\":\"0xdead\"}}"; // 缺 hash/timestamp
            JsonNode parsed = JsonRpcCodec.extractResult(raw);
            if (wanted < 0) {
                return parsed;
            }
            JsonRpcCodec.toBlock(parsed, wanted);
            return parsed;
        }

        final ObjectNode resultNode;
        if ("chain_getLatestHeight".equals(method)) {
            resultNode = JsonRpcCodec.MAPPER.createObjectNode();
            resultNode.put("height", servedHeight);
        } else {
            long wanted = ((Number) params.get(0)).longValue();
            if (wanted > servedHeight) {
                throw new RpcException(FailureReason.RPC_ERROR,
                        "[" + name + "] 请求高度 " + wanted + " 超过节点高度 " + servedHeight);
            }
            Block block = chain.blockAt(wanted);
            if (block == null) {
                throw new RpcException(FailureReason.RPC_ERROR, "[" + name + "] 找不到高度 " + wanted);
            }
            resultNode = JsonRpcCodec.MAPPER.valueToTree(block);
        }
        // 统一先序列化为 JSON-RPC 文本，再走解析，模拟真实链路
        String raw;
        try {
            ObjectNode envelope = JsonRpcCodec.MAPPER.createObjectNode();
            envelope.put("jsonrpc", "2.0");
            envelope.put("id", 1);
            envelope.set("result", resultNode);
            raw = JsonRpcCodec.MAPPER.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new RpcException(FailureReason.BAD_RESPONSE, "[" + name + "] 响应序列化失败", e);
        }
        return JsonRpcCodec.extractResult(raw);
    }

    private synchronized Fault nextFault() {
        return upcomingFaults.pollFirst();
    }

    private RpcException faultException(Fault fault) {
        return switch (fault.reason()) {
            case CONNECTIVITY -> new RpcException(FailureReason.CONNECTIVITY,
                    "[" + name + "] " + fault.detail() + "（超过超时 " + timeoutMillis + "ms）");
            case RPC_ERROR -> new RpcException(FailureReason.RPC_ERROR, "[" + name + "] " + fault.detail());
            case BAD_RESPONSE -> new RpcException(FailureReason.BAD_RESPONSE, "[" + name + "] " + fault.detail());
            case LAGGING -> new RpcException(FailureReason.LAGGING, "[" + name + "] " + fault.detail());
        };
    }

    private void sleepLatency() {
        long delay = latencyMillis;
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RpcException(FailureReason.CONNECTIVITY, "[" + name + "] 请求被中断", e);
            }
        }
    }
}
