package com.github.xingjianluo.chainrpcnodeindexerjava.rpc;

import com.github.xingjianluo.chainrpcnodeindexerjava.chain.Block;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 纯本地的内存链，供多个 {@link SimulatedUpstreamNode} 共享。
 *
 * <p>语义：
 * <ul>
 *     <li>创世块高度 0，parentHash 为空串；</li>
 *     <li>{@link #mine(int)} 在链尾追加新块；</li>
 *     <li>{@link #reorg(long, long)} 从 {@code fromHeight}（不含该高度）截断，
 *     换分支种子后重建到 {@code newLength}，因此冲突从 fromHeight 开始，
 *     共同祖先为 fromHeight-1。</li>
 * </ul>
 * 所有方法线程安全。
 */
public class SimulatedChain {

    private final List<Block> blocks = new ArrayList<>();
    /** 分支种子：每次重组递增，使重建块的 hash/payload 与旧链不同。 */
    private long branchSeed = 1;
    private static final long GENESIS_TS = 1_700_000_000_000L;

    public SimulatedChain(int initialBlocks) {
        if (initialBlocks < 1) {
            throw new IllegalArgumentException("初始链至少包含创世块");
        }
        for (int i = 0; i < initialBlocks; i++) {
            appendBlock();
        }
    }

    public synchronized long height() {
        return (long) blocks.size() - 1;
    }

    /** 高度不存在返回 null（由节点层翻译为错误）。 */
    public synchronized Block blockAt(long height) {
        if (height < 0 || height >= blocks.size()) {
            return null;
        }
        return blocks.get((int) height);
    }

    public synchronized List<Block> blocks() {
        return List.copyOf(blocks);
    }

    public synchronized void mine(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("mine count 必须为正数");
        }
        for (int i = 0; i < count; i++) {
            appendBlock();
        }
    }

    /**
     * 链重组：保留 [0, fromHeight)，丢弃 fromHeight 及之后的块，换种子重建到 newLength。
     */
    public synchronized void reorg(long fromHeight, long newLength) {
        if (fromHeight < 0 || fromHeight > blocks.size()) {
            throw new IllegalArgumentException("非法重组起点 " + fromHeight);
        }
        if (newLength < fromHeight) {
            throw new IllegalArgumentException("重组后长度不能短于截断点");
        }
        blocks.subList((int) fromHeight, blocks.size()).clear();
        branchSeed++;
        while (blocks.size() < newLength) {
            appendBlock();
        }
    }

    private void appendBlock() {
        long h = blocks.size();
        String parent = h == 0 ? "" : blocks.get((int) h - 1).hash();
        String hash = hashOf(branchSeed, h, parent);
        String payload = "data-" + Long.toHexString(branchSeed) + "-h" + h;
        blocks.add(new Block(h, hash, parent, GENESIS_TS + h * 1000L, payload));
    }

    private static String hashOf(long seed, long height, String parentHash) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String input = seed + "|" + height + "|" + parentHash;
            byte[] out = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("0x");
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", out[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
