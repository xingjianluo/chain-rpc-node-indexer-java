package com.github.xingjianluo.chainrpcnodeindexerjava.rpc;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 对单个上游节点的 JSON-RPC 客户端。
 */
public interface JsonRpcClient {

    /** 节点 ID */
    String id();

    /** 节点地址 */
    String endpoint();

    /**
     * 发起一次 JSON-RPC 调用。
     *
     * @throws RpcException NETWORK_UNREACHABLE / NODE_ERROR / BAD_RESPONSE
     */
    JsonNode call(String method, Object params);
}
