package com.github.xingjianluo.chainrpcnodeindexerjava.storage;

import java.util.List;

import com.github.xingjianluo.chainrpcnodeindexerjava.model.Block;

/**
 * 区块持久化存储。
 * <p>
 * 高度从 0 开始连续。所有写操作（追加/重组替换）必须原子可见：
 * 读线程要么看到写之前的完整链，要么看到写之后的完整链，不会看到中间状态。
 */
public interface BlockStore {

    /** 已存储的最大高度；空库返回 -1。 */
    long maxHeight();

    /** 按高度取块，不存在返回 null。 */
    Block get(long height);

    /** 当前链上最新块（空库为 null）。 */
    Block latest();

    /**
     * 原子获取当前整条链的不可变快照（高度 0..maxHeight）。
     * 快照要么是提交前的完整旧链，要么是提交后的完整新链，
     * 重组回滚期间不会出现新旧混合。
     */
    List<Block> snapshot();

    /**
     * 追加下一块（height 必须等于 maxHeight+1）。
     * 相同高度重复追加相同哈希时忽略并返回 false；哈希冲突说明发生了未检测到的重组，抛出异常。
     *
     * @return 真正写入返回 true
     */
    boolean append(Block block);

    /**
     * 重组：删除高度大于等于 fromHeight 的所有块，然后依次写入 replacements。
     * 整体原子可见。
     */
    void replaceFrom(long fromHeight, java.util.List<Block> replacements);
}
