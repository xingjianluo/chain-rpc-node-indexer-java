package com.github.xingjianluo.chainrpcnodeindexerjava.health;

/**
 * 节点健康状态。
 */
public enum HealthStatus {
    /** 健康，可被正常选中 */
    AVAILABLE,
    /** 高度落后，健康节点存在时不选中 */
    LAGGING,
    /** 连续失败被判不可用，不能被选中（冷却后仅放一次探测） */
    UNAVAILABLE,
    /** 不可用后的试探状态，需连续成功才恢复 */
    PROBING
}
