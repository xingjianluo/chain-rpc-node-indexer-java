package com.github.xingjianluo.chainrpcnodeindexerjava.rpc;

/**
 * 底层 HTTP JSON-RPC 传输抽象（便于在单测中替换模拟行为）。
 */
public interface RpcTransport {

    /**
     * 发送一个 JSON-RPC 请求并返回原始 JSON 字符串响应。
     *
     * @param id          节点 ID（仅用于日志）
     * @param endpoint    节点地址
     * @param requestBody 已序列化的 JSON-RPC 请求体
     * @param timeoutMs   超时毫秒
     * @return 响应体字符串
     * @throws RpcException NETWORK_UNREACHABLE / NODE_ERROR
     */
    String post(String id, String endpoint, String requestBody, long timeoutMs);
}
