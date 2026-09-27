package com.github.xingjianluo.chainrpcnodeindexerjava.rpc;

import com.github.xingjianluo.chainrpcnodeindexerjava.model.Block;

/**
 * 单个链节点的链相关操作（在 JSON-RPC 之上做字段解析）。
 */
public interface ChainNode {

    String id();

    String endpoint();

    /** 查询节点最新高度；失败抛出 {@link RpcException}。 */
    long fetchLatestHeight();

    /**
     * 按高度取区块；节点上不存在该高度时返回 null。
     * 解析失败抛出 {@link RpcException}（BAD_RESPONSE）。
     */
    Block fetchBlock(long height);
}
