package com.github.xingjianluo.chainrpcnodeindexerjava.query;

/**
 * 一次成功查询的结果以及它来自哪个节点、该节点当前高度。
 */
public record NodeResponse<T>(String node, long nodeHeight, T data) {
}
