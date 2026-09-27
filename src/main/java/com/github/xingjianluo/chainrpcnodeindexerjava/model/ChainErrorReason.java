package com.github.xingjianluo.chainrpcnodeindexerjava.model;

/**
 * 上游节点失败原因分类。
 */
public enum ChainErrorReason {

    /** 网络连不上 / 连接被拒 / 超时 / IO 错误 */
    NETWORK_UNREACHABLE,

    /** 节点返回 JSON-RPC error，或 HTTP 状态码非 2xx */
    NODE_ERROR,

    /** 节点有响应，但返回体无法解析或缺少必需字段 */
    BAD_RESPONSE,

    /** 节点可用，但其最新高度明显落后于其它健康节点 */
    HEIGHT_LAGGING
}
