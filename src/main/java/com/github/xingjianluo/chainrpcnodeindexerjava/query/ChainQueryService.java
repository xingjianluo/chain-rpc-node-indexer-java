package com.github.xingjianluo.chainrpcnodeindexerjava.query;

import com.github.xingjianluo.chainrpcnodeindexerjava.chain.Block;
import com.github.xingjianluo.chainrpcnodeindexerjava.health.NodeHealthTracker;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.FailureReason;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.RpcException;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.UpstreamNode;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 对多上游做带故障转移的查询。
 *
 * <p>每轮从 {@link NodeHealthTracker#candidates()} 取有序候选，逐个尝试：
 * <ul>
 *     <li>成功：记录高度与成功，返回 {@link NodeResponse}（带节点名与该节点当前高度）；</li>
 *     <li>失败：按 {@link FailureReason} 记账并立即切换下一候选；</li>
 *     <li>全部失败：抛 {@link AllNodesFailedException}，带最后一个失败原因。</li>
 * </ul>
 */
public class ChainQueryService {

    private static final Logger log = LoggerFactory.getLogger(ChainQueryService.class);

    private final List<UpstreamNode> nodes;
    private final NodeHealthTracker tracker;

    public ChainQueryService(List<UpstreamNode> nodes, NodeHealthTracker tracker) {
        this.nodes = nodes;
        this.tracker = tracker;
        for (UpstreamNode node : nodes) {
            tracker.register(node);
        }
    }

    public List<UpstreamNode> nodes() {
        return List.copyOf(nodes);
    }

    public NodeResponse<Long> latestHeight() {
        return execute("查询最新高度", UpstreamNode::getLatestHeight, (node, height) -> height);
    }

    public NodeResponse<Block> blockByHeight(long height) {
        // 成功后再问一次节点高度，供滞后判定与返回展示；该次失败则视为本节点整体失败
        return execute("查询高度 " + height + " 的区块",
                node -> node.getBlockByHeight(height),
                (node, block) -> node.getLatestHeight());
    }

    /**
     * @param call       对单个节点的主调用
     * @param heightOf   主调用成功后获取该节点最新高度（用于健康/滞后判定与返回）
     */
    private <T> NodeResponse<T> execute(String action,
                                        Function<UpstreamNode, T> call,
                                        BiFunction<UpstreamNode, T, Long> heightOf) {
        List<UpstreamNode> candidates = tracker.candidates();
        FailureReason lastReason = null;
        String lastDetail = "没有可尝试的节点";

        for (UpstreamNode node : candidates) {
            String name = node.name();
            try {
                T data = call.apply(node);
                long nodeHeight;
                try {
                    nodeHeight = heightOf.apply(node, data);
                } catch (RpcException e) {
                    tracker.recordFailure(name, e.reason(), action + " 后取高度失败: " + e.getMessage());
                    lastReason = e.reason();
                    lastDetail = e.getMessage();
                    log.warn("动作[{}] 节点 [{}] 取高度失败原因[{}]，切换下一节点: {}",
                            action, name, e.reason(), e.getMessage());
                    continue;
                }
                tracker.recordSuccess(name, nodeHeight);
                log.info("动作[{}] 选中节点 [{}] 成功，节点当前高度 {}，网络最佳高度 {}",
                        action, name, nodeHeight, tracker.networkBestHeight());
                return new NodeResponse<>(name, nodeHeight, data);
            } catch (RpcException e) {
                tracker.recordFailure(name, e.reason(), e.getMessage());
                lastReason = e.reason();
                lastDetail = e.getMessage();
                log.warn("动作[{}] 节点 [{}] 失败，判定原因[{}]，切换下一节点: {}",
                        action, name, e.reason(), e.getMessage());
            } catch (RuntimeException e) {
                tracker.recordFailure(name, FailureReason.BAD_RESPONSE, e.toString());
                lastReason = FailureReason.BAD_RESPONSE;
                lastDetail = e.toString();
                log.warn("动作[{}] 节点 [{}] 抛出未预期异常[{}]，切换下一节点", action, name, e.toString());
            }
        }

        log.error("动作[{}] 全部 {} 个候选节点均失败，最后原因[{}]: {}",
                action, candidates.size(), lastReason, lastDetail);
        throw new AllNodesFailedException(
                "所有上游节点均不可用，最后失败原因: " + lastReason + " (" + lastDetail + ")",
                lastReason);
    }
}
