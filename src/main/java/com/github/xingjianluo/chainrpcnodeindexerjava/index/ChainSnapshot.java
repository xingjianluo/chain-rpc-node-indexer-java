package com.github.xingjianluo.chainrpcnodeindexerjava.index;

import com.github.xingjianluo.chainrpcnodeindexerjava.chain.Block;
import java.util.ArrayList;
import java.util.List;

/**
 * 某一时刻的完整链视图，<b>不可变</b>。
 *
 * <p>索引器在抓取/回滚/重建期间只操作本地副本，构建完成后才用一个新快照原子替换旧快照；
 * 对外查询拿到的始终是某个完整的 {@code ChainSnapshot}，因此重组期间不会读到
 * “一半新一半旧”的中间状态。
 *
 * @param blocks 必须按高度 0..n 连续、parentHash 首尾相连
 */
public record ChainSnapshot(List<Block> blocks) {

    public ChainSnapshot {
        blocks = List.copyOf(blocks);
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            if (b.height() != i) {
                throw new IllegalArgumentException("快照片段不连续：位置 " + i + " 实际高度 " + b.height());
            }
            if (i > 0 && !b.parentHash().equals(blocks.get(i - 1).hash())) {
                throw new IllegalArgumentException("快照父子关系断裂于高度 " + i);
            }
        }
    }

    public static ChainSnapshot empty() {
        return new ChainSnapshot(List.of());
    }

    public static ChainSnapshot of(List<Block> blocks) {
        return new ChainSnapshot(new ArrayList<>(blocks));
    }

    public long headHeight() {
        return blocks.isEmpty() ? -1L : blocks.get(blocks.size() - 1).height();
    }

    public String headHash() {
        return blocks.isEmpty() ? null : blocks.get(blocks.size() - 1).hash();
    }

    /** 查询指定高度；超出范围返回 null。 */
    public Block atHeight(long height) {
        if (height < 0 || height >= blocks.size()) {
            return null;
        }
        return blocks.get((int) height);
    }

    public int size() {
        return blocks.size();
    }
}
