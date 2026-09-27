package com.github.xingjianluo.chainrpcnodeindexerjava.model;

/**
 * 从上游取得的最新高度结果（经多节点交叉验证后的集群最高高度）。
 *
 * @param nodeId 实际提供该最高高度的节点
 * @param height 集群内观测到的最高高度
 */
public record LatestHeightResult(String nodeId, long height) {
}
