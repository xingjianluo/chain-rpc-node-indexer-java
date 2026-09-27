package com.github.xingjianluo.chainrpcnodeindexerjava.indexer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.xingjianluo.chainrpcnodeindexerjava.model.Block;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.BlockResult;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.LatestHeightResult;
import com.github.xingjianluo.chainrpcnodeindexerjava.node.NoHealthyNodeException;
import com.github.xingjianluo.chainrpcnodeindexerjava.storage.FileBlockStore;

/**
 * {@link DefaultBlockIndexer} 单测：追块、重复抓取幂等、链重组回滚到共同祖先后重建，
 * 以及上游全挂时本地链保持不变。
 */
class DefaultBlockIndexerTest {

    private static final Logger log = LoggerFactory.getLogger(DefaultBlockIndexerTest.class);

    /** 内存假“上游链视图”：可随时切换到一条重组后的链。 */
    static class FakeChainClient implements com.github.xingjianluo.chainrpcnodeindexerjava.node.FailoverChainClient {
        final Map<Long, Block> blocks = new HashMap<>();
        long tip = -1;
        boolean allDown;
        String sourceNode = "node-a";

        void setChain(Map<Long, Block> chain, long tipHeight) {
            blocks.clear();
            blocks.putAll(chain);
            tip = tipHeight;
        }

        @Override
        public LatestHeightResult fetchLatestHeight() {
            if (allDown) {
                throw new NoHealthyNodeException("测试：全部节点不可用");
            }
            log.info("[测试] 上游集群高度={} 选中节点={}", tip, sourceNode);
            return new LatestHeightResult(sourceNode, tip);
        }

        @Override
        public BlockResult fetchBlock(long height) {
            if (allDown) {
                throw new NoHealthyNodeException("测试：全部节点不可用");
            }
            Block b = blocks.get(height);
            log.info("[测试] 取块 height={} 节点={} hash={}", height, sourceNode,
                    b == null ? "null" : b.hash());
            return new BlockResult(sourceNode, b);
        }

        @Override
        public BlockResult fetchLatestBlock() {
            return fetchBlock(tip);
        }
    }

    @TempDir
    Path tempDir;

    private Block block(long h, String hash, String parent, String payload) {
        return new Block(h, hash, parent, 1000 + h, payload);
    }

    /** 构造一条从 0 到 n 的链，键为高度。 */
    private Map<Long, Block> chain(String tag, int n, String payloadPrefix) {
        Map<Long, Block> map = new HashMap<>();
        String parent = "0x0";
        for (long h = 0; h < n; h++) {
            String hash = tag + "-h" + h;
            map.put(h, block(h, hash, parent, payloadPrefix + h));
            parent = hash;
        }
        return map;
    }

    @Test
    void catchesUpBlockByBlockWithoutGapsOrDuplicates() {
        FileBlockStore store = new FileBlockStore(tempDir);
        FakeChainClient upstream = new FakeChainClient();
        upstream.setChain(chain("a", 5, "p"), 4);
        DefaultBlockIndexer indexer = new DefaultBlockIndexer(store, upstream);

        // 多轮调度逐步追赶
        for (int i = 0; i < 6; i++) {
            indexer.catchUpOnce();
        }
        assertThat(indexer.indexedHeight()).isEqualTo(4);
        for (long h = 0; h <= 4; h++) {
            assertThat(store.get(h).hash()).isEqualTo("a-h" + h);
        }

        // 再跑若干轮：无新块时不能重复写入
        for (int i = 0; i < 3; i++) {
            indexer.catchUpOnce();
        }
        assertThat(store.snapshot()).hasSize(5);
    }

    @Test
    void reorgAtSameHeight_rollsBackToCommonAncestorAndRebuilds() {
        FileBlockStore store = new FileBlockStore(tempDir);
        FakeChainClient upstream = new FakeChainClient();
        upstream.setChain(chain("old", 6, "old"), 5);
        DefaultBlockIndexer indexer = new DefaultBlockIndexer(store, upstream);
        while (indexer.indexedHeight() < 5) {
            indexer.catchUpOnce();
        }
        assertThat(store.get(5).hash()).isEqualTo("old-h5");

        // 同高度重组：0..3 相同，4、5 换成新链（tip 仍是 5，典型“等高分叉”）
        Map<Long, Block> forked = new HashMap<>();
        forked.put(0L, block(0, "old-h0", "0x0", "old0"));
        forked.put(1L, block(1, "old-h1", "old-h0", "old1"));
        forked.put(2L, block(2, "old-h2", "old-h1", "old2"));
        forked.put(3L, block(3, "old-h3", "old-h2", "old3"));
        forked.put(4L, block(4, "new-h4", "old-h3", "new4"));
        forked.put(5L, block(5, "new-h5", "new-h4", "new5"));
        upstream.setChain(forked, 5);

        // 多跑几轮确保检测到并完成
        for (int i = 0; i < 5; i++) {
            indexer.catchUpOnce();
        }

        assertThat(indexer.indexedHeight()).isEqualTo(5);
        // 共同祖先及其以下不变
        assertThat(store.get(3).hash()).isEqualTo("old-h3");
        // 分叉点起是新链
        assertThat(store.get(4).hash()).isEqualTo("new-h4");
        assertThat(store.get(4).parentHash()).isEqualTo("old-h3");
        assertThat(store.get(5).hash()).isEqualTo("new-h5");
        assertThat(store.get(5).parentHash()).isEqualTo("new-h4");
        assertThat(store.get(5).payload()).isEqualTo("new5");

        // 一致性：整条本地链快照自洽
        String parent = "0x0";
        for (Block b : store.snapshot()) {
            assertThat(b.parentHash()).isEqualTo(parent);
            parent = b.hash();
        }
        log.info("[测试] 同高度重组完成，共同祖先高度=3，本地链尖 hash={}", store.get(5).hash());
    }

    @Test
    void shorteningReorg_thenGrowth_rebuildsCorrectly() {
        FileBlockStore store = new FileBlockStore(tempDir);
        FakeChainClient upstream = new FakeChainClient();
        upstream.setChain(chain("old", 6, "old"), 5);
        DefaultBlockIndexer indexer = new DefaultBlockIndexer(store, upstream);
        while (indexer.indexedHeight() < 5) {
            indexer.catchUpOnce();
        }

        // 缩短型分叉：新链暂时只到高度 4（0..2 相同，3、4 是新链）
        Map<Long, Block> shorter = new HashMap<>();
        shorter.put(0L, block(0, "old-h0", "0x0", "old0"));
        shorter.put(1L, block(1, "old-h1", "old-h0", "old1"));
        shorter.put(2L, block(2, "old-h2", "old-h1", "old2"));
        shorter.put(3L, block(3, "newA-h3", "old-h2", "newA3"));
        shorter.put(4L, block(4, "newA-h4", "newA-h3", "newA4"));
        upstream.setChain(shorter, 4);

        indexer.catchUpOnce();
        assertThat(indexer.indexedHeight()).isEqualTo(4);
        assertThat(store.get(3).hash()).isEqualTo("newA-h3");
        assertThat(store.get(4).hash()).isEqualTo("newA-h4");
        assertThat(store.maxHeight()).isEqualTo(4);

        // 新链继续长到 6
        shorter.put(5L, block(5, "newA-h5", "newA-h4", "newA5"));
        shorter.put(6L, block(6, "newA-h6", "newA-h5", "newA6"));
        upstream.setChain(shorter, 6);
        for (int i = 0; i < 3; i++) {
            indexer.catchUpOnce();
        }
        assertThat(indexer.indexedHeight()).isEqualTo(6);
        assertThat(store.get(6).parentHash()).isEqualTo("newA-h5");
    }

    @Test
    void allNodesDown_keepsLocalChainUntouched() {
        FileBlockStore store = new FileBlockStore(tempDir);
        FakeChainClient upstream = new FakeChainClient();
        upstream.setChain(chain("a", 4, "p"), 3);
        DefaultBlockIndexer indexer = new DefaultBlockIndexer(store, upstream);
        while (indexer.indexedHeight() < 3) {
            indexer.catchUpOnce();
        }

        upstream.allDown = true;
        for (int i = 0; i < 5; i++) {
            indexer.catchUpOnce(); // 不应抛异常，也不改动本地
        }
        assertThat(indexer.indexedHeight()).isEqualTo(3);
        assertThat(store.snapshot()).hasSize(4);

        // 节点恢复且链长高，继续追赶
        upstream.allDown = false;
        upstream.setChain(chain("a", 6, "p"), 5);
        for (int i = 0; i < 4; i++) {
            indexer.catchUpOnce();
        }
        assertThat(indexer.indexedHeight()).isEqualTo(5);
    }

    @Test
    void restartResume_appendsFromLastHeightWithoutDuplication() {
        // 第一段生命周期
        FakeChainClient upstream1 = new FakeChainClient();
        upstream1.setChain(chain("a", 4, "p"), 3);
        FileBlockStore store1 = new FileBlockStore(tempDir);
        DefaultBlockIndexer indexer1 = new DefaultBlockIndexer(store1, upstream1);
        while (indexer1.indexedHeight() < 3) {
            indexer1.catchUpOnce();
        }

        // 重启：新 store 指向同一目录，上游多了两块
        FakeChainClient upstream2 = new FakeChainClient();
        upstream2.setChain(chain("a", 6, "p"), 5);
        FileBlockStore store2 = new FileBlockStore(tempDir);
        DefaultBlockIndexer indexer2 = new DefaultBlockIndexer(store2, upstream2);
        assertThat(indexer2.indexedHeight()).isEqualTo(3);
        for (int i = 0; i < 4; i++) {
            indexer2.catchUpOnce();
        }
        assertThat(indexer2.indexedHeight()).isEqualTo(5);
        assertThat(store2.snapshot()).hasSize(6);
        // 多跑不重复
        indexer2.catchUpOnce();
        assertThat(store2.snapshot()).hasSize(6);
    }
}
