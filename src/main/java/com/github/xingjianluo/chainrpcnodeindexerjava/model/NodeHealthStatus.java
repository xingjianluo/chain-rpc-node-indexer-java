package com.github.xingjianluo.chainrpcnodeindexerjava.model;

/**
 * 节点健康状态。
 * UNAVAILABLE 的节点不会再被选中，只有连续多次探测成功才会回到 HEALTHY。
 */
public enum NodeHealthStatus {
    HEALTHY,
    UNAVAILABLE
}
