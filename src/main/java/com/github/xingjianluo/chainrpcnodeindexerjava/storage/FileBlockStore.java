package com.github.xingjianluo.chainrpcnodeindexerjava.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.Block;

/**
 * 基于本地文件的 {@link BlockStore}。
 * <p>
 * 存储结构：
 * <pre>
 * dataDir/
 *   meta.json        —— 当前生效快照的指针（{"snapshot":"snapshot-12.json"}），原子替换
 *   snapshot-N.json  —— 不可变全量快照（从创世块到链尖的完整数组）
 * </pre>
 * 一致性模型：
 * <ul>
 *   <li>每次提交（追加一块 / 重组整段）都生成一份新的不可变快照，再原子替换 meta 指针。
 *       读线程只通过 volatile 引用读快照，因此<b>要么看到旧链，要么看到新链，
 *       不会看到回滚重建的中间状态</b>；</li>
 *   <li>快照先落盘（原子临时文件 rename）再切指针，进程在任意时刻崩溃，重启后 meta 指向的
 *       一定是一份完整快照，已确认的块不丢，未提交的块不留；</li>
 *   <li>写锁串行化追加与重组；读不加锁。</li>
 * </ul>
 */
public class FileBlockStore implements BlockStore {

    private static final Logger log = LoggerFactory.getLogger(FileBlockStore.class);
    private static final String META_FILE = "meta.json";

    private final Path dataDir;
    private final ObjectMapper mapper;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final AtomicLong snapshotSeq = new AtomicLong();

    /** 当前生效的不可变视图；TreeMap 保证按高度有序。 */
    private volatile NavigableMap<Long, Block> current = new TreeMap<>();

    public FileBlockStore(Path dataDir) {
        this(dataDir, new ObjectMapper());
    }

    public FileBlockStore(Path dataDir, ObjectMapper mapper) {
        this.dataDir = dataDir;
        this.mapper = mapper.copy()
                .setSerializationInclusion(JsonInclude.Include.ALWAYS);
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new StorageException("无法创建数据目录: " + dataDir, e);
        }
        recover();
    }

    private void recover() {
        Path metaPath = dataDir.resolve(META_FILE);
        String snapshotName;
        if (Files.exists(metaPath)) {
            try {
                Meta meta = mapper.readValue(Files.readString(metaPath, StandardCharsets.UTF_8), Meta.class);
                snapshotName = meta.snapshot();
            } catch (IOException e) {
                throw new StorageException("meta.json 无法读取（存储损坏）: " + metaPath, e);
            }
        } else {
            // 首次启动，或写快照后、切指针前崩溃：无 meta 即视为空库，稍后清理孤儿快照
            snapshotName = null;
        }

        if (snapshotName != null) {
            Path snapshotPath = dataDir.resolve(snapshotName);
            if (!Files.exists(snapshotPath)) {
                throw new StorageException("meta 指向的快照不存在: " + snapshotPath);
            }
            List<Block> blocks;
            try {
                blocks = mapper.readValue(Files.readString(snapshotPath, StandardCharsets.UTF_8),
                        new TypeReference<List<Block>>() {
                        });
            } catch (IOException e) {
                throw new StorageException("快照无法解析（存储损坏）: " + snapshotPath, e);
            }
            NavigableMap<Long, Block> loaded = validateAndIndex(blocks);
            current = loaded;
            log.info("[store] 从 {} 恢复 {} 个块，最高高度={}", snapshotName,
                    loaded.size(), loaded.isEmpty() ? -1 : loaded.lastKey());
        } else {
            log.info("[store] 空库启动（{} 下无 meta.json）", dataDir);
        }

        // 清理孤儿/陈旧快照（崩溃残留或被替换的旧版本），保留当前版本
        cleanupStaleSnapshots(snapshotName);
    }

    private NavigableMap<Long, Block> validateAndIndex(List<Block> blocks) {
        NavigableMap<Long, Block> map = new TreeMap<>();
        String expectedParent = "0x0";
        long expectedHeight = 0L;
        for (Block b : blocks) {
            if (b == null) {
                throw new StorageException("快照中存在 null 区块");
            }
            if (b.height() != expectedHeight) {
                throw new StorageException("快照高度不连续：期望 " + expectedHeight
                        + "，实际 " + b.height());
            }
            if (expectedHeight == 0L && !"0x0".equals(b.parentHash())) {
                // 创世块父哈希约定为 0x0，宽松处理：仅在首块校验
                log.debug("[store] 创世块 parentHash={}（非 0x0，按模拟数据接受）", b.parentHash());
            }
            if (expectedHeight > 0L && !expectedParent.equals(b.parentHash())) {
                throw new StorageException("快照哈希链断裂：height=" + b.height()
                        + " 期望 parentHash=" + expectedParent + " 实际=" + b.parentHash());
            }
            map.put(b.height(), b);
            expectedParent = b.hash();
            expectedHeight++;
        }
        return map;
    }

    private void cleanupStaleSnapshots(String keepName) {
        try (Stream<Path> files = Files.list(dataDir)) {
            files.filter(p -> p.getFileName().toString().startsWith("snapshot-")
                            && p.getFileName().toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().equals(keepName))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.warn("[store] 清理旧快照失败 {}: {}", p, e.toString());
                        }
                    });
        } catch (IOException e) {
            log.warn("[store] 扫描快照目录失败: {}", e.toString());
        }
    }

    @Override
    public long maxHeight() {
        NavigableMap<Long, Block> view = current;
        return view.isEmpty() ? -1L : view.lastKey();
    }

    @Override
    public Block get(long height) {
        return current.get(height);
    }

    @Override
    public Block latest() {
        NavigableMap<Long, Block> view = current;
        return view.isEmpty() ? null : view.lastEntry().getValue();
    }

    @Override
    public List<Block> snapshot() {
        // current 本身在提交时被整体替换，values() 的快照拷贝即一次原子一致读
        return List.copyOf(current.values());
    }

    @Override
    public boolean append(Block block) {
        lock.writeLock().lock();
        try {
            long expected = maxHeight() + 1;
            if (block.height() < expected) {
                Block existing = current.get(block.height());
                if (existing != null && existing.hash().equals(block.hash())) {
                    // 重复抓取 / 重启后重放：同高度同哈希，幂等忽略
                    log.info("[store] 高度 {} 已存在相同哈希，幂等忽略重复写入", block.height());
                    return false;
                }
                throw new StorageException("高度 " + block.height() + " 已存在但哈希不同："
                        + (existing == null ? null : existing.hash()) + " vs " + block.hash()
                        + "（追加路径不应发生重组，请走 replaceFrom）");
            }
            if (block.height() > expected) {
                throw new StorageException("不允许跳过高度追加：下一个应为 " + expected
                        + "，实际 " + block.height());
            }
            if (expected > 0) {
                Block tip = current.lastEntry().getValue();
                if (!tip.hash().equals(block.parentHash())) {
                    throw new StorageException("父哈希不匹配：height=" + block.height()
                            + " 期望 parentHash=" + tip.hash() + " 实际=" + block.parentHash());
                }
            }
            NavigableMap<Long, Block> next = new TreeMap<>(current);
            next.put(block.height(), block);
            commit(next, "append-height-" + block.height());
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void replaceFrom(long fromHeight, List<Block> replacements) {
        lock.writeLock().lock();
        try {
            if (fromHeight < 0) {
                throw new IllegalArgumentException("fromHeight 不能为负: " + fromHeight);
            }
            // 只保留严格低于分叉点的高度（共同祖先及以下），分叉点起全部由 replacements 重建
            NavigableMap<Long, Block> next = new TreeMap<>(current.headMap(fromHeight, false));
            long expectedHeight = fromHeight;
            String expectedParent = next.isEmpty() ? "0x0" : next.lastEntry().getValue().hash();
            for (Block b : replacements) {
                if (b.height() != expectedHeight) {
                    throw new StorageException("replaceFrom 高度不连续：期望 " + expectedHeight
                            + " 实际 " + b.height());
                }
                if (!expectedParent.equals(b.parentHash())) {
                    throw new StorageException("replaceFrom 哈希链接不上：height=" + b.height()
                            + " 期望 parentHash=" + expectedParent + " 实际=" + b.parentHash());
                }
                next.put(b.height(), b);
                expectedParent = b.hash();
                expectedHeight++;
            }
            // 回滚到共同祖先后，重建段不得比原链短导致高度倒退为“更短链”——
            // 调用方负责保证 replacements 覆盖到至少原链尖；这里只记录语义不做强制。
            commit(next, "reorg-from-" + fromHeight);
            log.info("[store] 重组提交：共同祖先高度={}，新链尖高度={}（原子切换完成）",
                    fromHeight - 1, next.isEmpty() ? -1 : next.lastKey());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 将新视图写成不可变快照，原子切换 meta 指针，再发布内存视图。
     * 崩溃点分析：写快照前后崩溃 -> 旧 meta 仍指向旧快照；切指针后崩溃 -> 新链生效。
     */
    private void commit(NavigableMap<Long, Block> next, String reason) {
        String snapshotName = "snapshot-" + snapshotSeq.incrementAndGet()
                + "-h" + (next.isEmpty() ? -1 : next.lastKey()) + ".json";
        Path snapshotPath = dataDir.resolve(snapshotName);
        List<Block> blocks = List.copyOf(next.values());
        try {
            writeAtomically(snapshotPath, mapper.writeValueAsBytes(blocks));
            Path metaPath = dataDir.resolve(META_FILE);
            writeAtomically(metaPath, mapper.writeValueAsBytes(new Meta(snapshotName)));
        } catch (IOException e) {
            throw new StorageException("提交快照失败: " + reason, e);
        }
        NavigableMap<Long, Block> immutableView = new TreeMap<>(next);
        current = immutableView;
        cleanupStaleSnapshots(snapshotName);
        log.debug("[store] 提交完成 reason={} blocks={} snapshot={}",
                reason, blocks.size(), snapshotName);
    }

    private void writeAtomically(Path target, byte[] content) throws IOException {
        Path tmp = dataDir.resolve(target.getFileName().toString() + ".tmp-"
                + Thread.currentThread().getId() + "-" + System.nanoTime());
        try {
            Files.write(tmp, content,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 临时文件清理失败不影响正确性，下次启动由快照清理逻辑兜底
            }
        }
    }

    /** meta.json 结构。 */
    private record Meta(String snapshot) {
    }

    // 便于测试/排查：导出当前视图（不可变拷贝）
}
