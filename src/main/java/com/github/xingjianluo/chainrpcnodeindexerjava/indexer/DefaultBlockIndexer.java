package com.github.xingjianluo.chainrpcnodeindexerjava.indexer;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.xingjianluo.chainrpcnodeindexerjava.model.Block;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.BlockResult;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.LatestHeightResult;
import com.github.xingjianluo.chainrpcnodeindexerjava.node.FailoverChainClient;
import com.github.xingjianluo.chainrpcnodeindexerjava.storage.BlockStore;

/**
 * {@link BlockIndexer} 默认实现。
 * <p>
 * 每轮 {@link #catchUpOnce()} 的语义：
 * <ol>
 *   <li>经故障转移客户端拿到集群最高高度（结果带源节点信息）；</li>
 *   <li>本地落后 -> 取下一块并校验 parentHash：衔接一致则追加；对不上则走重组；</li>
 *   <li>本地与集群<b>等高或更高</b> -> 拉取集群链尖块与本地同高度块比对哈希：
 *       一致说明没有分叉；不一致说明发生了“同高度/缩短型”重组，进入重组流程；</li>
 * </ol>
 * 重组处理：沿新链向父块回溯，与本地存储逐高度比对，找到共同祖先后把分叉点之后的
 * 新链段一次性 {@code replaceFrom}——存储层先写好完整新快照再原子切换，
 * <b>回滚重建期间读请求看到的要么是完整旧链，要么是完整新链</b>，不会一半新一半旧。
 * 上游全部不可用时本轮跳过，等待下轮/节点恢复，不会污染本地链。
 */
public class DefaultBlockIndexer implements BlockIndexer {

    private static final Logger log = LoggerFactory.getLogger(DefaultBlockIndexer.class);

    /** 单次重组回溯的最大深度，防止异常节点构造的超长伪链拖垮进程。 */
    private static final long MAX_REORG_DEPTH = 64L;

    private final BlockStore store;
    private final FailoverChainClient client;

    public DefaultBlockIndexer(BlockStore store, FailoverChainClient client) {
        this.store = store;
        this.client = client;
    }

    @Override
    public long indexedHeight() {
        return store.maxHeight();
    }

    @Override
    public void catchUpOnce() {
        LatestHeightResult head;
        try {
            head = client.fetchLatestHeight();
        } catch (RuntimeException e) {
            log.warn("[indexer] 获取集群高度失败，本轮跳过: {}", e.getMessage());
            return;
        }
        long local = store.maxHeight();

        if (head.height() > local) {
            catchUpNextBlock(head, local);
        } else {
            verifyTipForSameHeightReorg(head, local);
        }
    }

    /** 本地落后：抓 local+1 一块。 */
    private void catchUpNextBlock(LatestHeightResult head, long local) {
        long nextHeight = local + 1;
        BlockResult fetched = fetchWithFailover(nextHeight);
        if (fetched == null) {
            return;
        }
        Block candidate = fetched.block();
        Block localTip = store.latest();
        String expectedParent = localTip == null ? "0x0" : localTip.hash();

        if (expectedParent.equals(candidate.parentHash())) {
            boolean written = store.append(candidate);
            log.info("[indexer] {} height={} hash={} 来源节点={} localHeight={}",
                    written ? "写入新块" : "重复块已忽略",
                    candidate.height(), candidate.hash(), fetched.nodeId(), store.maxHeight());
            return;
        }

        log.warn("[indexer] 检测到链重组：本地链尖 height={} hash={}，新块 height={} hash={} parentHash={}",
                local, localTip == null ? null : localTip.hash(),
                candidate.height(), candidate.hash(), candidate.parentHash());
        handleReorg(candidate, fetched.nodeId());
    }

    /**
     * 等高/本地更高：集群链尖没有高于本地时仍需比对哈希，
     * 否则“同高度重组/缩短型重组”会被漏检。
     */
    private void verifyTipForSameHeightReorg(LatestHeightResult head, long local) {
        if (local < 0) {
            // 本地空库但集群高度 <= -1（无块），无事可做
            log.debug("[indexer] 本地与集群均无块（remote={}，来源节点={}）",
                    head.height(), head.nodeId());
            return;
        }
        long compareHeight = Math.min(local, head.height());
        BlockResult remoteTip = fetchWithFailover(compareHeight);
        if (remoteTip == null || remoteTip.block() == null) {
            return;
        }
        Block localAtHeight = store.get(compareHeight);
        if (localAtHeight != null && localAtHeight.hash().equals(remoteTip.block().hash())) {
            log.debug("[indexer] 高度 {} 哈希一致，无重组（来源节点={}）",
                    compareHeight, remoteTip.nodeId());
            return;
        }
        log.warn("[indexer] 检测到同高度/缩短型重组：本地 height={} hash={}，"
                        + "集群 height={} 同高度块 hash={}（来源节点={}）",
                compareHeight, localAtHeight == null ? null : localAtHeight.hash(),
                head.height(), remoteTip.block().hash(), remoteTip.nodeId());
        handleReorg(remoteTip.block(), remoteTip.nodeId());
    }

    private BlockResult fetchWithFailover(long height) {
        try {
            return client.fetchBlock(height);
        } catch (RuntimeException e) {
            log.warn("[indexer] 取块 height={} 失败，本轮跳过: {}", height, e.getMessage());
            return null;
        }
    }

    /**
     * 处理重组：
     * <pre>
     * 新链：... ancestor - new[fork] - new[fork+1] ... new[h]
     * 旧链：... ancestor - old[fork] - ... （被整体替换）
     * </pre>
     * 全程只读上游、不动本地存储；找到共同祖先后一次性原子替换。
     */
    private void handleReorg(Block firstNewBlock, String sourceNode) {
        // 1) 从已知的新链块开始沿 parentHash 向上游回溯，收集“新链分叉段”（降序）
        List<Block> newChainDescending = new ArrayList<>();
        Block cursor = firstNewBlock;
        long forkHeight = -1;
        Block commonAncestor = null;

        for (long step = 0; step <= MAX_REORG_DEPTH; step++) {
            Block localAtHeight = store.get(cursor.height());
            if (localAtHeight != null && localAtHeight.hash().equals(cursor.hash())) {
                // 同高度同哈希 -> 共同祖先
                forkHeight = cursor.height() + 1;
                commonAncestor = cursor;
                break;
            }
            if (cursor.height() == 0) {
                // 连创世块都不一致：从高度 0 起整条重建
                forkHeight = 0;
                newChainDescending.add(cursor);
                commonAncestor = null;
                break;
            }
            newChainDescending.add(cursor);
            long parentHeight = cursor.height() - 1;
            BlockResult parentResult = fetchWithFailover(parentHeight);
            if (parentResult == null || parentResult.block() == null) {
                log.warn("[indexer] 重组回溯在 height={} 取不到父块，本轮中止，本地链保持不变",
                        parentHeight);
                return;
            }
            if (parentResult.block().height() != parentHeight
                    || !cursor.parentHash().equals(parentResult.block().hash())) {
                log.warn("[indexer] 上游新链在 height={} 附近哈希链不稳定，本轮中止保持本地链",
                        parentHeight);
                return;
            }
            cursor = parentResult.block();
        }

        if (forkHeight < 0) {
            log.warn("[indexer] 重组深度超过 {} 仍未找到共同祖先，放弃本轮以保护本地链",
                    MAX_REORG_DEPTH);
            return;
        }

        // 2) 降序收集转成升序
        List<Block> replacements = new ArrayList<>(newChainDescending.size());
        for (int i = newChainDescending.size() - 1; i >= 0; i--) {
            replacements.add(newChainDescending.get(i));
        }

        long oldTip = store.maxHeight();
        long newTip = replacements.isEmpty() ? forkHeight - 1
                : replacements.get(replacements.size() - 1).height();
        log.warn("[indexer] 重组方案：共同祖先 height={} hash={}，回滚高度 >= {}，"
                        + "重建 {} 个块，旧链尖={} 新链尖={}，来源节点={}",
                commonAncestor == null ? -1 : commonAncestor.height(),
                commonAncestor == null ? null : commonAncestor.hash(),
                forkHeight, replacements.size(), oldTip, newTip, sourceNode);

        // 3) 一次性原子替换；存储层保证提交前外部读到的始终是旧链
        store.replaceFrom(forkHeight, replacements);
        log.info("[indexer] 重组完成并对外生效：新链尖 height={} hash={}，本地高度={}",
                newTip, replacements.isEmpty() ? null :
                        replacements.get(replacements.size() - 1).hash(), store.maxHeight());
    }
}
