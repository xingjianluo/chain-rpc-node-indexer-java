package com.github.xingjianluo.chainrpcnodeindexerjava.api.dto;

/**
 * 对外区块视图。
 *
 * @param source   数据来源节点 ID；来自本地索引时为 "local-index"
 * @param indexed  是否来自本地已确认索引（true 表示重组语义下的一致读）
 * @param height   高度
 * @param hash     哈希
 * @param parentHash 父哈希
 * @param timestamp 时间戳
 * @param payload  附加数据
 */
public record BlockDto(String source,
                       boolean indexed,
                       long height,
                       String hash,
                       String parentHash,
                       long timestamp,
                       String payload) {
}
