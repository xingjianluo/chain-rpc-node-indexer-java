package com.github.xingjianluo.chainrpcnodeindexerjava.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.xingjianluo.chainrpcnodeindexerjava.model.Block;

/**
 * {@link FileBlockStore} 单测：连续追加、重复抓取幂等、跳号/错链拒绝、
 * 重组原子替换、重启恢复，以及重组期间读一致性。
 */
class FileBlockStoreTest {

    @TempDir
    Path tempDir;

    private Block block(long height, String hash, String parent) {
        return new Block(height, hash, parent, 1000 + height, "payload-" + height);
    }

    private List<Block> chain(int n, String tag) {
        List<Block> list = new ArrayList<>();
        String parent = "0x0";
        for (long h = 0; h < n; h++) {
            String hash = tag + "-h" + h;
            list.add(block(h, hash, parent));
            parent = hash;
        }
        return list;
    }

    @Test
    void append_inSequence_andRead() {
        FileBlockStore store = new FileBlockStore(tempDir);
        assertThat(store.maxHeight()).isEqualTo(-1);
        for (Block b : chain(4, "a")) {
            assertThat(store.append(b)).isTrue();
        }
        assertThat(store.maxHeight()).isEqualTo(3);
        assertThat(store.latest().hash()).isEqualTo("a-h3");
        assertThat(store.get(1).parentHash()).isEqualTo("a-h0");
    }

    @Test
    void append_sameHeightSameHash_isIdempotent() {
        FileBlockStore store = new FileBlockStore(tempDir);
        List<Block> blocks = chain(3, "a");
        blocks.forEach(store::append);

        // 重复抓取 / 重启重放：同高度同哈希不能重复写入
        assertThat(store.append(blocks.get(2))).isFalse();
        assertThat(store.maxHeight()).isEqualTo(2);
    }

    @Test
    void append_sameHeightDifferentHash_throws() {
        FileBlockStore store = new FileBlockStore(tempDir);
        chain(3, "a").forEach(store::append);
        Block conflicting = block(1, "evil-h1", "a-h0");
        assertThatThrownBy(() -> store.append(conflicting))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("哈希不同");
    }

    @Test
    void append_skippingHeight_throws() {
        FileBlockStore store = new FileBlockStore(tempDir);
        store.append(block(0, "g", "0x0"));
        assertThatThrownBy(() -> store.append(block(2, "x", "g")))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("不允许跳过高度");
    }

    @Test
    void append_wrongParentHash_throws() {
        FileBlockStore store = new FileBlockStore(tempDir);
        store.append(block(0, "g", "0x0"));
        assertThatThrownBy(() -> store.append(block(1, "h1", "0xdead")))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("父哈希不匹配");
    }

    @Test
    void replaceFrom_rollsBackToForkAndRebuildsAtomically() {
        FileBlockStore store = new FileBlockStore(tempDir);
        chain(6, "old").forEach(store::append);

        // 在高度 4 分叉：保留 0..3，替换 4、5
        List<Block> replacement = List.of(
                block(4, "new-h4", "old-h3"),
                block(5, "new-h5", "new-h4"));
        store.replaceFrom(4, replacement);

        assertThat(store.maxHeight()).isEqualTo(5);
        assertThat(store.get(3).hash()).isEqualTo("old-h3");
        assertThat(store.get(4).hash()).isEqualTo("new-h4");
        assertThat(store.get(4).parentHash()).isEqualTo("old-h3");
        assertThat(store.get(5).hash()).isEqualTo("new-h5");
        assertThat(store.get(5).parentHash()).isEqualTo("new-h4");
    }

    @Test
    void replaceFrom_brokenReplacementChain_rejectedAndStoreUnchanged() {
        FileBlockStore store = new FileBlockStore(tempDir);
        chain(5, "old").forEach(store::append);

        List<Block> bad = List.of(
                block(4, "new-h4", "old-h3"),
                block(5, "new-h5", "0xbroken"));
        assertThatThrownBy(() -> store.replaceFrom(4, bad))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("哈希链接不上");

        // 提交失败必须保持旧链完整，没有中间状态
        assertThat(store.maxHeight()).isEqualTo(4);
        assertThat(store.get(4).hash()).isEqualTo("old-h4");
    }

    @Test
    void reopen_recoversFromSnapshotWithoutDuplicationOrGaps() {
        FileBlockStore first = new FileBlockStore(tempDir);
        chain(5, "a").forEach(first::append);
        assertThat(first.maxHeight()).isEqualTo(4);

        // 模拟服务重启
        FileBlockStore second = new FileBlockStore(tempDir);
        assertThat(second.maxHeight()).isEqualTo(4);
        assertThat(second.latest().hash()).isEqualTo("a-h4");

        // 重启后继续追加，下一块正好是 5
        assertThat(second.append(block(5, "a-h5", "a-h4"))).isTrue();
        // 重放已存在的块仍然幂等
        assertThat(second.append(block(5, "a-h5", "a-h4"))).isFalse();
    }

    @Test
    void reopen_afterReorg_loadsNewChain() {
        FileBlockStore first = new FileBlockStore(tempDir);
        chain(4, "old").forEach(first::append);
        first.replaceFrom(2, List.of(
                block(2, "new-h2", "old-h1"),
                block(3, "new-h3", "new-h2"),
                block(4, "new-h4", "new-h3")));

        FileBlockStore second = new FileBlockStore(tempDir);
        assertThat(second.maxHeight()).isEqualTo(4);
        assertThat(second.get(2).hash()).isEqualTo("new-h2");
        assertThat(second.get(4).parentHash()).isEqualTo("new-h3");
    }

    @Test
    void readers_neverObservePartialStateDuringRepeatedReorg() throws Exception {
        FileBlockStore store = new FileBlockStore(tempDir);
        chain(8, "base").forEach(store::append);

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicReference<String> violation = new AtomicReference<>();

        // 多个读线程：任意时刻 store 必须呈现一条完整自洽的链
        List<Thread> readers = new ArrayList<>();
        for (int r = 0; r < 4; r++) {
            Thread t = new Thread(() -> {
                while (!stop.get()) {
                    // 一次原子快照：必须是某一条完整自洽的链，不允许新旧混合
                    List<Block> view = store.snapshot();
                    String parent = "0x0";
                    long expected = 0;
                    for (Block b : view) {
                        if (b.height() != expected || !b.parentHash().equals(parent)) {
                            violation.set("快照中高度 " + expected + " 不一致：block="
                                    + b.hash() + "/" + b.parentHash()
                                    + " 期望 parent=" + parent);
                            stop.set(true);
                            return;
                        }
                        parent = b.hash();
                        expected++;
                    }
                }
            }, "reader-" + r);
            readers.add(t);
            t.start();
        }

        // 反复在不同深度重组，观察读者是否读到一半新一半旧
        for (int round = 0; round < 50 && violation.get() == null; round++) {
            String tag = "reorg" + round;
            long fork = 2 + (round % 4);
            List<Block> repl = new ArrayList<>();
            String parent = store.get(fork - 1).hash();
            for (long h = fork; h <= 8; h++) {
                String hash = tag + "-h" + h;
                repl.add(block(h, hash, parent));
                parent = hash;
            }
            store.replaceFrom(fork, repl);
        }

        stop.set(true);
        for (Thread t : readers) {
            t.join(2000);
        }
        assertThat(violation.get()).as("重组期间读视图必须始终一致").isNull();
    }
}
