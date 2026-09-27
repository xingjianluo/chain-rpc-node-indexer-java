package com.github.xingjianluo.chainrpcnodeindexerjava.chain;

/**
 * 区块数据。使用内容标识 hash 做幂等与重组判断。
 */
public record Block(long height, String hash, String parentHash, long timestamp, String payload) {
}
