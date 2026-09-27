package com.github.xingjianluo.chainrpcnodeindexerjava.model;

/**
 * 区块数据。
 *
 * @param height      高度
 * @param hash        本块哈希（唯一标识，重组判定依据）
 * @param parentHash  父块哈希
 * @param timestamp   区块时间戳
 * @param payload     模拟交易/附加数据
 */
public record Block(long height, String hash, String parentHash, long timestamp, String payload) {
}
