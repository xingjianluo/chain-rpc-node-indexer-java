package com.github.xingjianluo.chainrpcnodeindexerjava.sim;

/**
 * 单个模拟节点的行为模式。
 */
public enum SimBehavior {
    /** 正常：返回链上真实最新数据 */
    OK,
    /** 滞后：对外暴露的高度比链尖低若干块 */
    LAGGING,
    /** 超时：请求挂起不返回 */
    TIMEOUT,
    /** 返回 JSON-RPC error */
    ERROR,
    /** 返回无法解析/字段缺失的内容 */
    BAD_RESPONSE,
    /** 监听端口关闭、连不上（由调用方直接访问未监听端口模拟，此枚举预留） */
    UNREACHABLE
}
