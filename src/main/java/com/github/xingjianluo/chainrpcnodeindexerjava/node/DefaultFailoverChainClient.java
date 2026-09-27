package com.github.xingjianluo.chainrpcnodeindexerjava.node;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.xingjianluo.chainrpcnodeindexerjava.model.Block;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.BlockResult;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.ChainErrorReason;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.LatestHeightResult;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.NodeState;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.ChainNode;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.RpcException;

/**
 * {@link FailoverChainClient} 默认实现。
 * <p>
 * 高度查询采用“先各节点探高、再交叉验证”的两阶段策略：
 * <ol>
 *   <li>依次向健康候选节点查询最新高度，失败（网络/错误/坏数据）即记录原因并切换下一节点；</li>
 *   <li>以成功节点中的最高高度为集群高度；与最高高度差距超过 lagThresholdBlocks 的节点
 *       记一次 HEIGHT_LAGGING 失败（计入连续失败，可能因此被判不可用），其高度不被采用；</li>
 *   <li>正常节点记录成功（清空其连续失败计数，保证偶发抖动不判死）。</li>
 * </ol>
 * 区块查询按候选顺序逐个尝试，任一节点成功即返回，结果携带实际提供数据的节点 ID。
 */
public class DefaultFailoverChainClient implements FailoverChainClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultFailoverChainClient.class);

    private final NodeSelector selector;
    private final HealthRegistry registry;
    private final long lagThresholdBlocks;

    public DefaultFailoverChainClient(NodeSelector selector, HealthRegistry registry,
                                      long lagThresholdBlocks) {
        this.selector = selector;
        this.registry = registry;
        this.lagThresholdBlocks = lagThresholdBlocks;
    }

    @Override
    public LatestHeightResult fetchLatestHeight() {
        List<ChainNode> candidates = selector.selectCandidates();
        if (candidates.isEmpty()) {
            throw new NoHealthyNodeException("没有可用的健康节点");
        }

        record Sample(ChainNode node, long height) {
        }
        List<Sample> samples = new ArrayList<>();
        List<String> attempts = new ArrayList<>();

        for (ChainNode node : candidates) {
            attempts.add(node.id());
            try {
                long height = node.fetchLatestHeight();
                samples.add(new Sample(node, height));
                log.info("[failover] 探高 node={} -> height={}", node.id(), height);
            } catch (RpcException e) {
                registry.recordFailure(node.id(), e.reason());
                log.warn("[failover] node={} 探高失败 reason={}，切换下一候选",
                        node.id(), e.reason());
            }
        }

        if (samples.isEmpty()) {
            throw new NoHealthyNodeException(
                    "所有候选节点均不可用，尝试顺序=" + attempts + "，健康快照="
                            + registry.snapshot().stream()
                            .map(s -> s.nodeId() + ":" + s.status()
                                    + (s.lastReason() == null ? "" : "(" + s.lastReason() + ")"))
                            .toList());
        }

        long bestHeight = samples.stream().mapToLong(Sample::height).max().orElseThrow();
        Sample best = samples.stream()
                .filter(s -> s.height == bestHeight)
                .sorted(Comparator.comparing(s -> s.node().id()))
                .findFirst()
                .orElseThrow();

        // 交叉验证：明显落后的节点记 HEIGHT_LAGGING，其余记录成功（清失败计数）
        for (Sample s : samples) {
            long gap = bestHeight - s.height();
            if (gap > lagThresholdBlocks) {
                NodeState after = registry.recordFailure(s.node().id(),
                        ChainErrorReason.HEIGHT_LAGGING);
                log.warn("[failover] node={} height={} 落后集群高度 {} 达 {} 块(阈值={}) -> status={}",
                        s.node().id(), s.height(), bestHeight, gap, lagThresholdBlocks, after.status());
            } else {
                registry.recordSuccess(s.node().id(), s.height());
            }
        }

        log.info("[failover] 集群高度={} 选中节点={}（候选尝试顺序={}，判定依据：最高高度且id最小）",
                bestHeight, best.node().id(), attempts);
        return new LatestHeightResult(best.node().id(), bestHeight);
    }

    @Override
    public BlockResult fetchBlock(long height) {
        List<ChainNode> candidates = selector.selectCandidates();
        if (candidates.isEmpty()) {
            throw new NoHealthyNodeException("没有可用的健康节点");
        }
        List<String> attempts = new ArrayList<>();
        for (ChainNode node : candidates) {
            attempts.add(node.id());
            try {
                Block block = node.fetchBlock(height);
                if (block == null) {
                    // 该节点尚未同步到这个高度；仅当其它候选也拿不到时才影响最终结果，
                    // 不计失败（节点本身是健康的，只是数据暂时不全）。
                    log.info("[failover] node={} 尚无 height={} 的块，切换下一候选",
                            node.id(), height);
                    continue;
                }
                registry.recordSuccess(node.id(), block.height());
                log.info("[failover] 取块 height={} 选中节点={} hash={}（尝试顺序={}）",
                        height, node.id(), block.hash(), attempts);
                return new BlockResult(node.id(), block);
            } catch (RpcException e) {
                registry.recordFailure(node.id(), e.reason());
                log.warn("[failover] node={} 取块 height={} 失败 reason={}，切换下一候选",
                        node.id(), height, e.reason());
            }
        }
        throw new NoHealthyNodeException(
                "无法从任何节点取得 height=" + height + " 的区块，尝试顺序=" + attempts
                        + "，健康快照=" + registry.snapshot().stream()
                        .map(s -> s.nodeId() + ":" + s.status()
                                + (s.lastReason() == null ? "" : "(" + s.lastReason() + ")"))
                        .toList());
    }

    @Override
    public BlockResult fetchLatestBlock() {
        LatestHeightResult height = fetchLatestHeight();
        BlockResult block = fetchBlock(height.height());
        log.info("[failover] 最新块 height={} 来自节点={} hash={}",
                height.height(), block.nodeId(), block.block() == null ? null : block.block().hash());
        return block;
    }
}
