package com.github.xingjianluo.chainrpcnodeindexerjava.rpc;

/**
 * 上游节点失败原因分类。
 */
public enum FailureReason {
    /** 网络连不上 / 请求超时 */
    CONNECTIVITY,
    /** 节点可达但返回 JSON-RPC error 或明确错误 */
    RPC_ERROR,
    /** HTTP 通了但返回内容无法解析或字段不合法 */
    BAD_RESPONSE,
    /** 节点高度明显落后于网络最佳高度 */
    LAGGING
}
