package com.github.xingjianluo.chainrpcnodeindexerjava.rpc;

import com.github.xingjianluo.chainrpcnodeindexerjava.chain.Block;

/**
 * 一个上游 JSON-RPC 节点。
 */
public interface UpstreamNode {

    String name();

    /** 请求超时时间（毫秒），模拟节点据此制造超时。 */
    long timeoutMillis();

    long getLatestHeight();

    Block getBlockByHeight(long height);
}
