package com.github.xingjianluo.chainrpcnodeindexerjava.model;

/**
 * 某个上游节点在某一时刻的健康快照。
 *
 * @param nodeId            节点 ID
 * @param endpoint          节点地址
 * @param status            健康状态
 * @param latestHeight      已知的最新高度，未知为 -1
 * @param consecutiveFailures 连续失败次数
 * @param consecutiveSuccesses 连续成功次数（恢复探测用）
 * @param lastReason        最近一次失败原因，无则为 null
 * @param lastUpdatedEpochMs 最近一次更新时间
 */
public record NodeState(String nodeId,
                        String endpoint,
                        NodeHealthStatus status,
                        long latestHeight,
                        int consecutiveFailures,
                        int consecutiveSuccesses,
                        ChainErrorReason lastReason,
                        long lastUpdatedEpochMs) {
}
