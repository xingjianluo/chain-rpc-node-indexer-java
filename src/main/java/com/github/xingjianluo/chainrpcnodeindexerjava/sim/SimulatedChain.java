package com.github.xingjianluo.chainrpcnodeindexerjava.sim;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

import com.github.xingjianluo.chainrpcnodeindexerjava.model.Block;

/**
 * 本地模拟链（线程安全）。
 * <p>
 * 区块哈希是确定性的：hash = 短SHA256(height | parentHash | chainEpoch | payload)。
 * {@code chainEpoch} 在每次重组时递增，因此同一高度在新链上会得到不同哈希，
 * 可以真实触发“同高度哈希不一致”的重组判定。
 */
public class SimulatedChain {

    private final NavigableMap<Long, Block> blocks = new TreeMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    /** 链身份：重组时递增，使新链与旧链在同高度哈希不同。 */
    private long chainEpoch = 0;

    public SimulatedChain() {
    }

    /** 启动时预生成创世块与若干块。 */
    public void initialize(int initialBlockCount) {
        lock.lock();
        try {
            int want = Math.max(1, initialBlockCount);
            while (blocks.size() < want) {
                doProduceBlock("genesis-tip-" + blocks.size());
            }
        } finally {
            lock.unlock();
        }
    }

    /** 追加一块，返回新块。 */
    public Block produceBlock(String payload) {
        lock.lock();
        try {
            return doProduceBlock(payload);
        } finally {
            lock.unlock();
        }
    }

    /** 一次追加多块。 */
    public List<Block> produceBlocks(int count) {
        lock.lock();
        try {
            List<Block> created = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                created.add(doProduceBlock("block-batch-" + i));
            }
            return List.copyOf(created);
        } finally {
            lock.unlock();
        }
    }

    private Block doProduceBlock(String payload) {
        long height = blocks.isEmpty() ? 0L : blocks.lastKey() + 1;
        String parentHash = blocks.isEmpty() ? "0x0" : blocks.lastEntry().getValue().hash();
        Block block = new Block(height,
                hashOf(height, parentHash, payload),
                parentHash,
                System.currentTimeMillis(),
                payload == null ? "" : payload);
        blocks.put(height, block);
        return block;
    }

    public long tipHeight() {
        lock.lock();
        try {
            return blocks.isEmpty() ? -1L : blocks.lastKey();
        } finally {
            lock.unlock();
        }
    }

    /** 取链上某高度当前的块，不存在返回 null。 */
    public Block blockAt(long height) {
        lock.lock();
        try {
            return blocks.get(height);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 在深度 depth 处重组：保留高度 0..tip-depth 的块（共同祖先），
     * 删除其上的旧块，chainEpoch+1 后补齐到原链尖高度。
     *
     * @return 分叉点高度（共同祖先 +1）；无法重组（深度非法）返回 -1
     */
    public long reorg(int depth) {
        lock.lock();
        try {
            long tip = blocks.isEmpty() ? -1L : blocks.lastKey();
            if (depth <= 0 || depth > tip + 1) {
                return -1;
            }
            long keepThrough = tip - depth;
            // 删除 keepThrough 以上的全部块
            NavigableMap<Long, Block> tail = new TreeMap<>(blocks.tailMap(keepThrough + 1, true));
            for (Long h : tail.keySet()) {
                blocks.remove(h);
            }
            chainEpoch++;
            int reorgCount = tail.size();
            List<Block> regrown = new ArrayList<>();
            for (int i = 0; i < reorgCount; i++) {
                regrown.add(doProduceBlock("reorg-epoch" + chainEpoch + "-" + i));
            }
            return keepThrough + 1;
        } finally {
            lock.unlock();
        }
    }

    private String hashOf(long height, String parentHash, String payload) {
    try {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        String material = height + "|" + parentHash + "|" + chainEpoch + "|" + payload;
        byte[] digest = md.digest(material.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder("0x");
        for (int i = 0; i < 4; i++) {
            sb.append(String.format("%02x", digest[i]));
        }
        return sb.toString();
    } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
    }
}
}
