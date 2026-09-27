package com.github.xingjianluo.chainrpcnodeindexerjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.chainrpcnodeindexerjava.chain.Block;
import com.github.xingjianluo.chainrpcnodeindexerjava.health.NodeHealthTracker;
import com.github.xingjianluo.chainrpcnodeindexerjava.index.ChainIndexer;
import com.github.xingjianluo.chainrpcnodeindexerjava.index.ChainSnapshot;
import com.github.xingjianluo.chainrpcnodeindexerjava.query.ChainQueryService;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.SimulatedChain;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.SimulatedUpstreamNode;
import com.github.xingjianluo.chainrpcnodeindexerjava.store.FileBlockStore;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ChainIndexerTest {

    private static final Logger log = LoggerFactory.getLogger(ChainIndexerTest.class);

    private record Ctx(SimulatedChain chain, ChainQueryService query, ChainIndexer indexer) {
    }

    private Ctx build(Path dir, int chainHeight) {
        SimulatedChain chain = new SimulatedChain(chainHeight);
        List<com.github.xingjianluo.chainrpcnodeindexerjava.rpc.UpstreamNode> nodes = List.of(
                new SimulatedUpstreamNode("a", chain, 1000),
                new SimulatedUpstreamNode("b", chain, 1000));
        NodeHealthTracker tracker = new NodeHealthTracker(3, 2, 1000, 2);
        ChainQueryService query = new ChainQueryService(nodes, tracker);
        FileBlockStore store = new FileBlockStore(dir.resolve("blocks.json"), new ObjectMapper());
        ChainIndexer indexer = new ChainIndexer(query, store, 1000);
        return new Ctx(chain, query, indexer);
    }

    @Test
    void fullSyncThenIdempotentRerun(@TempDir Path dir) {
        Ctx ctx = build(dir, 8);
        int first = ctx.indexer.tick();
        assertEquals(8, first, "创世块起共 8 个块（高度 0..7）");
        ChainSnapshot snap = ctx.indexer.snapshot();
        assertEquals(7L, snap.headHeight());
        // 父子链完整
        for (int i = 1; i < snap.blocks().size(); i++) {
            assertEquals(snap.blocks().get(i - 1).hash(), snap.blocks().get(i).parentHash());
        }

        int second = ctx.indexer.tick();
        assertEquals(0, second, "重复抓取不能重复写入");
        log.info("[去重] 首轮处理=8，重复 tick 处理=0，当前高度=7");

        // 出块后只补新块，不漏不重
        ctx.chain.mine(2);
        int third = ctx.indexer.tick();
        assertEquals(2, third);
        assertEquals(9L, ctx.indexer.snapshot().headHeight());
        log.info("[增量] 出块 2 个后 tick 处理=2，当前高度=9");
    }

    @Test
    void reorgRollsBackToCommonAncestorAndSnapshotStaysConsistent(@TempDir Path dir) {
        Ctx ctx = build(dir, 10);
        ctx.indexer.tick();
        ChainSnapshot before = ctx.indexer.snapshot();
        Block oldAt7 = before.atHeight(7);
        Block ancestor6 = before.atHeight(6);

        // 在高度 7 处重组，长度保持 10：0..6 是共同祖先，7..9 全部换 hash
        ctx.chain.reorg(7, 10);
        assertNotEquals(oldAt7.hash(), ctx.chain.blockAt(7).hash());
        assertEquals(ancestor6.hash(), ctx.chain.blockAt(6).hash());

        int processed = ctx.indexer.tick();
        ChainSnapshot after = ctx.indexer.snapshot();
        assertEquals(3, processed, "只重建高度 7/8/9 三个块，共同祖先 0..6 不动");
        assertEquals(ancestor6.hash(), after.atHeight(6).hash());
        assertEquals(ctx.chain.blockAt(7).hash(), after.atHeight(7).hash());
        assertEquals(ctx.chain.blockAt(9).hash(), after.atHeight(9).hash());
        for (int i = 1; i < after.blocks().size(); i++) {
            assertEquals(after.blocks().get(i - 1).hash(), after.blocks().get(i).parentHash(),
                    "重组后快照必须是一条完整一致的链");
        }
        log.info("[重组] 共同祖先高度=6，回滚重建高度 7..9，新链头={}，快照父子关系完整",
                after.headHash());
    }

    @Test
    void deepReorgWorksAndQueryNeverSeesHalfState(@TempDir Path dir) throws Exception {
        Ctx ctx = build(dir, 12);
        ctx.indexer.tick();
        Block keep = ctx.indexer.snapshot().atHeight(3);

        // 另一个线程持续读快照：任何时刻快照都必须是一条自洽的链
        final boolean[] stop = {false};
        final boolean[] consistent = {true};
        Thread reader = new Thread(() -> {
            while (!stop[0]) {
                ChainSnapshot s = ctx.indexer.snapshot();
                for (int i = 1; i < s.blocks().size(); i++) {
                    if (!s.blocks().get(i - 1).hash().equals(s.blocks().get(i).parentHash())) {
                        consistent[0] = false;
                    }
                }
            }
        });
        reader.start();

        ctx.chain.reorg(4, 14); // 4..13 全换，且链变长
        int total = 0;
        for (int i = 0; i < 5 && total < 14; i++) {
            total += ctx.indexer.tick();
        }
        stop[0] = true;
        reader.join();

        ChainSnapshot after = ctx.indexer.snapshot();
        assertEquals(13L, after.headHeight());
        assertEquals(keep.hash(), after.atHeight(3).hash(), "共同祖先 0..3 保留");
        assertEquals(ctx.chain.blockAt(13).hash(), after.atHeight(13).hash());
        assertTrue(consistent[0], "并发读取期间不能出现一半新一半旧的快照");
        log.info("[深度重组+一致性] 回滚点=4，最终高度=13，并发读取始终自洽");
    }

    @Test
    void restartResumesWithoutDuplicateOrGap(@TempDir Path dir) {
        Ctx first = build(dir, 6);
        first.indexer.tick();
        assertEquals(5L, first.indexer.snapshot().headHeight());

        // 重启：链继续增长；用同一个存储文件重新加载 store/indexer（节点复用同一条链）
        first.chain.mine(3);
        NodeHealthTracker tracker2 = new NodeHealthTracker(3, 2, 1000, 2);
        ChainQueryService query2 = new ChainQueryService(
                List.of(new SimulatedUpstreamNode("a", first.chain, 1000)), tracker2);
        FileBlockStore store2 = new FileBlockStore(dir.resolve("blocks.json"), new ObjectMapper());
        ChainIndexer restarted = new ChainIndexer(query2, store2, 1000);

        assertEquals(5L, restarted.snapshot().headHeight(), "重启后从文件恢复到高度 5");
        int processed = restarted.tick();
        assertEquals(3, processed, "只补重启期间新增的 3 个块，不重复写入");
        assertEquals(8L, restarted.snapshot().headHeight());
        log.info("[重启恢复] 恢复点高度=5，补齐 3 块到高度=8，无重复无漏块");
    }

    @Test
    void allNodesDownSkipsTickAndKeepsLastSnapshot(@TempDir Path dir) {
        Ctx ctx = build(dir, 5);
        ctx.indexer.tick();
        long head = ctx.indexer.snapshot().headHeight();
        // 查询服务里的节点都挂掉（通过 tracker 无法直接拿到，改用把链节点全部置错）
        ctx.query.nodes().forEach(n -> ((SimulatedUpstreamNode) n).setAlwaysError(true));
        ctx.chain.mine(2);
        int processed = ctx.indexer.tick();
        assertEquals(0, processed);
        assertEquals(head, ctx.indexer.snapshot().headHeight(), "节点全挂时保持旧快照不变");
        assertNull(ctx.indexer.snapshot().atHeight(99));
        log.info("[全节点不可用] tick 跳过，当前高度保持={}", head);
    }
}
