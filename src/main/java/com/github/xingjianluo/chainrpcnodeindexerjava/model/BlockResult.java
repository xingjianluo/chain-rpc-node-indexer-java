package com.github.xingjianluo.chainrpcnodeindexerjava.model;

/**
 * 从上游节点取到的区块结果。
 *
 * @param nodeId 实际提供数据的节点
 * @param block  区块；按高度不存在时为 null
 */
public record BlockResult(String nodeId, Block block) {
}
