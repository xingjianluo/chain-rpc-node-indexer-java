package com.github.xingjianluo.chainrpcnodeindexerjava.node;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.xingjianluo.chainrpcnodeindexerjava.model.NodeState;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.ChainNode;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.RpcException;

/**
 * 定期对 UNAVAILABLE 节点做轻量探测（eth_blockNumber 等价调用）。
 * <p>
 * 恢复规则由 {@link HealthRegistry} 保证：单次探测成功不会恢复，
 * 需连续成功达到阈值；探测失败则连续成功计数清零。这样节点短暂异常恢复后能自愈，
 * 但不会因一次侥幸成功被重新选中。
 */
public class RecoveryProbe {

    private static final Logger log = LoggerFactory.getLogger(RecoveryProbe.class);

    private final HealthRegistry registry;
    private final NodeSelector selector;

    public RecoveryProbe(HealthRegistry registry, NodeSelector selector) {
        this.registry = registry;
        this.selector = selector;
    }

    /** 对所有 UNAVAILABLE 节点执行一轮探测（由定时器或测试调用）。 */
    public void probeOnce() {
        for (ChainNode node : selector.allNodes()) {
            NodeState state = registry.stateOf(node.id());
            if (state == null || state.status()
                    != com.github.xingjianluo.chainrpcnodeindexerjava.model.NodeHealthStatus.UNAVAILABLE) {
                continue;
            }
            try {
                long height = node.fetchLatestHeight();
                NodeState after = registry.recordSuccess(node.id(), height);
                log.info("[recovery] 探测 node={} 成功 height={} 连续成功={} status={}",
                        node.id(), height, after.consecutiveSuccesses(), after.status());
            } catch (RpcException e) {
                NodeState after = registry.recordFailure(node.id(), e.reason());
                log.warn("[recovery] 探测 node={} 失败 reason={} 连续失败={} status={}",
                        node.id(), e.reason(), after.consecutiveFailures(), after.status());
            }
        }
    }
}
