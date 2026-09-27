package com.github.xingjianluo.chainrpcnodeindexerjava.index;

import com.github.xingjianluo.chainrpcnodeindexerjava.chain.Block;
import com.github.xingjianluo.chainrpcnodeindexerjava.query.ChainQueryService;
import com.github.xingjianluo.chainrpcnodeindexerjava.query.NodeResponse;
import com.github.xingjianluo.chainrpcnodeindexerjava.store.BlockStore;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 持续区块索引器（由单个后台线程驱动）。
 *
 * <p>每个 {@link #tick()}：
 * <ol>
 *     <li>通过 {@link ChainQueryService} 拿网络高度（自动故障转移）；</li>
 *     <li><b>链头校验</b>：取 {@code min(本地头, 网络头)} 高度的上游区块比对 hash。
 *         即使重组后链高度不变，hash 不一致也能发现；</li>
 *     <li>hash 一致则从本地链头 + 1 顺序补块。已存在且 hash 一致的块直接跳过
 *         —— 重复抓取、重启恢复都不会重复写入或漏块；</li>
 *     <li>hash 不一致判定链重组：沿 parentHash 向上定位共同祖先，丢弃之后的旧块，
 *         再按新链补齐；</li>
 *     <li>新快照在本地构建完成后才<b>原子替换</b>并落盘，替换前对外查询仍读旧快照，
 *         因此回滚/重建期间对外结论始终一致。</li>
 * </ol>
 */
public class ChainIndexer {

    private static final Logger log = LoggerFactory.getLogger(ChainIndexer.class);

    private final ChainQueryService queryService;
    private final BlockStore store;
    private final long maxBlocksPerTick;

    private volatile ChainSnapshot current;

    public ChainIndexer(ChainQueryService queryService, BlockStore store, long maxBlocksPerTick) {
        this.queryService = queryService;
        this.store = store;
        this.maxBlocksPerTick = maxBlocksPerTick;
        this.current = store.load();
    }

    public ChainSnapshot snapshot() {
        return current;
    }

    /**
     * 执行一轮同步。
     *
     * @return 本轮新增/替换的块数（纯跳过重复块不计）
     */
    public synchronized int tick() {
        final long networkHeight;
        try {
            networkHeight = queryService.latestHeight().data();
        } catch (RuntimeException e) {
            // 所有节点都不可用时跳过本轮，不改动已提交的一致性快照
            log.warn("本轮同步跳过：无法从任何节点获取高度: {}", e.getMessage());
            return 0;
        }

        ChainSnapshot snapshot = current;
        List<Block> working = new ArrayList<>(snapshot.blocks());
        int processed = 0;
        long budget = maxBlocksPerTick;

        // ---- 第一步：链头 hash 校验，发现“同高度重组” ----
        if (!working.isEmpty()) {
            long verifyHeight = Math.min((long) working.size() - 1, networkHeight);
            Block upstream = queryService.blockByHeight(verifyHeight).data();
            budget--;
            Block local = working.get((int) verifyHeight);
            if (!local.hash().equals(upstream.hash())) {
                long ancestor = findCommonAncestor(working, verifyHeight, upstream);
                log.warn("检测到链重组：高度 {} 本地 hash {} 与新链 {} 不一致，"
                                + "回滚到共同祖先高度 {}",
                        verifyHeight, local.hash(), upstream.hash(), ancestor);
                working.subList((int) ancestor + 1, working.size()).clear();
                // 共同祖先之上的块全部按新链重建
                processed += extend(working, networkHeight, ancestor + 1, budget);
                return commitIfChanged(snapshot, working, processed, networkHeight);
            }
            // 看到的节点比本地矮（节点滞后）且重叠部分一致：本轮无事可做
            if (networkHeight <= verifyHeight) {
                log.debug("可见节点高度 {} 未超过本地 {}，跳过", networkHeight, verifyHeight);
                return 0;
            }
        }

        // ---- 第二步：顺序补齐新高度 ----
        long startHeight = working.size();
        processed += extend(working, networkHeight, startHeight, budget);
        return commitIfChanged(snapshot, working, processed, networkHeight);
    }

    /**
     * 从 startHeight 起顺序抓块追加到 working，直到 networkHeight 或预算耗尽。
     * 若某高度已存在且 hash 不一致（理论上第一步已挡住，这里做双保险），回滚重建。
     * 返回写入/替换的块数。
     */
    private int extend(List<Block> working, long networkHeight, long startHeight, long budget) {
        int processed = 0;
        long height = startHeight;
        long target = Math.min(networkHeight, startHeight + budget - 1);
        while (height <= target) {
            Block fetched = queryService.blockByHeight(height).data();

            if (height < working.size()) {
                Block existing = working.get((int) height);
                if (existing.hash().equals(fetched.hash())) {
                    height++;
                    continue;
                }
                long ancestor = findCommonAncestor(working, height, fetched);
                working.subList((int) ancestor + 1, working.size()).clear();
                height = ancestor + 1;
                target = Math.min(networkHeight, height + (target - height));
                continue;
            }

            String expectedParent = working.isEmpty() ? ""
                    : working.get(working.size() - 1).hash();
            if (!fetched.parentHash().equals(expectedParent)) {
                if (working.isEmpty()) {
                    throw new IllegalStateException("创世块 parentHash 不符合预期");
                }
                // 新链在该位置分叉：丢掉本地链头，回退一格重新比对
                working.remove(working.size() - 1);
                height--;
                target++;
                continue;
            }
            working.add(fetched);
            processed++;
            log.info("已索引高度 {} hash {}（来源节点高度 {}）",
                    height, fetched.hash(), networkHeight);
            height++;
        }
        return processed;
    }

    private int commitIfChanged(ChainSnapshot previous, List<Block> working,
                                int processed, long networkHeight) {
        if (processed == 0 && working.size() == previous.blocks().size()) {
            return 0;
        }
        ChainSnapshot rebuilt = ChainSnapshot.of(working);
        // 原子落盘 + 原子替换在同一把锁内完成
        store.commit(rebuilt);
        current = rebuilt;
        log.info("本轮提交 {} 个区块，链头 {} -> {}（网络高度 {}）",
                processed, previous.headHeight(), rebuilt.headHeight(), networkHeight);
        return processed;
    }

    /**
     * 已知 {@code divergentHeight} 上新旧 hash 不同，沿新链 parentHash 向上找，
     * 返回共同祖先高度（该高度及之前的块新旧链一致）；找不到返回 -1（创世重组）。
     */
    private long findCommonAncestor(List<Block> local, long divergentHeight, Block divergentBlock) {
        Block cursor = divergentBlock;
        for (long probe = divergentHeight - 1; probe >= 0; probe--) {
            Block localBlock = local.get((int) probe);
            if (localBlock.hash().equals(cursor.parentHash())) {
                return probe;
            }
            NodeResponse<Block> parentResp = queryService.blockByHeight(probe);
            cursor = parentResp.data();
            if (localBlock.hash().equals(cursor.hash())) {
                return probe;
            }
        }
        return -1L;
    }
}
