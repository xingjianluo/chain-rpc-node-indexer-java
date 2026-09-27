package com.github.xingjianluo.chainrpcnodeindexerjava.indexer;

/**
 * 后台区块索引器：持续从健康节点抓取区块并写入 {@link BlockStore}，
 * 处理去重与链重组（回滚到共同祖先后重建，整个过程对外一致）。
 */
public interface BlockIndexer {

    /** 执行一轮追赶（抓一块/检测一次重组）；测试可直接调用。 */
    void catchUpOnce();

    /** 当前本地已索引高度。 */
    long indexedHeight();
}
