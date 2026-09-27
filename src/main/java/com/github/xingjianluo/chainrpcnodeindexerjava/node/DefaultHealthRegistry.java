package com.github.xingjianluo.chainrpcnodeindexerjava.node;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.xingjianluo.chainrpcnodeindexerjava.config.NodeProperties;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.ChainErrorReason;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.NodeHealthStatus;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.NodeState;

/**
 * {@link HealthRegistry} 默认实现。
 * <p>
 * 判定规则（阈值可配置）：
 * <ul>
 *   <li>初始 HEALTHY；偶发一次失败只累计失败计数，不立刻判死；</li>
 *   <li><b>连续</b>失败次数达到 failureThreshold 才置 UNAVAILABLE，之后不再被选择；</li>
 *   <li>任意一次成功会把连续失败清零（健康节点因此不会被偶发抖动判死）；</li>
 *   <li>UNAVAILABLE 节点处于“试探”状态：单次成功不恢复，需<b>连续</b>成功达到
 *       recoverySuccesses 次才恢复为 HEALTHY；试探期间一次失败即计数清零重来；</li>
 *   <li>每次结果都会记录失败原因（NETWORK_UNREACHABLE / NODE_ERROR /
 *       BAD_RESPONSE / HEIGHT_LAGGING）。</li>
 * </ul>
 */
public class DefaultHealthRegistry implements HealthRegistry {

    private static final Logger log = LoggerFactory.getLogger(DefaultHealthRegistry.class);

    private final int failureThreshold;
    private final int recoverySuccesses;
    private final Map<String, NodeState> states = new ConcurrentHashMap<>();
    private final Map<String, String> endpoints = new ConcurrentHashMap<>();

    public DefaultHealthRegistry(List<NodeProperties> nodeProperties,
                                 int failureThreshold, int recoverySuccesses) {
        this.failureThreshold = failureThreshold;
        this.recoverySuccesses = recoverySuccesses;
        for (NodeProperties p : nodeProperties) {
            if (!p.enabled()) {
                continue;
            }
            endpoints.put(p.id(), p.endpoint());
            states.put(p.id(), new NodeState(p.id(), p.endpoint(), NodeHealthStatus.HEALTHY,
                    -1L, 0, 0, null, System.currentTimeMillis()));
        }
        log.info("[health] 登记 {} 个上游节点: {}", states.size(), states.keySet());
    }

    @Override
    public NodeState recordFailure(String nodeId, ChainErrorReason reason) {
        return states.compute(nodeId, (id, prev) -> {
            if (prev == null) {
                prev = new NodeState(id, endpoints.getOrDefault(id, ""),
                        NodeHealthStatus.HEALTHY, -1L, 0, 0, null, System.currentTimeMillis());
            }
            int failures = prev.consecutiveFailures() + 1;
            NodeHealthStatus status = prev.status();
            String verdict;
            if (status == NodeHealthStatus.HEALTHY && failures >= failureThreshold) {
                status = NodeHealthStatus.UNAVAILABLE;
                verdict = "连续失败 " + failures + " 次达到阈值 " + failureThreshold + "，判定不可用";
            } else if (status == NodeHealthStatus.UNAVAILABLE) {
                verdict = "此前已不可用，继续等待连续成功 " + recoverySuccesses + " 次才恢复";
            } else {
                verdict = "未达失败阈值 " + failureThreshold + "（当前连续失败 "
                        + failures + " 次），维持可用";
            }
            NodeState next = new NodeState(id, prev.endpoint(), status, prev.latestHeight(),
                    failures, 0, reason, System.currentTimeMillis());
            log.warn("[health] node={} 失败原因={} 连续失败={} -> {}（判定依据：{}）",
                    id, reason, failures, status, verdict);
            return next;
        });
    }

    @Override
    public NodeState recordSuccess(String nodeId, long latestHeight) {
        return states.compute(nodeId, (id, prev) -> {
            if (prev == null) {
                prev = new NodeState(id, endpoints.getOrDefault(id, ""),
                        NodeHealthStatus.HEALTHY, -1L, 0, 0, null, System.currentTimeMillis());
            }
            long height = Math.max(prev.latestHeight(), latestHeight);
            int successes = prev.consecutiveSuccesses() + 1;
            NodeHealthStatus status = prev.status();
            String verdict = "健康节点成功，失败计数清零";
            if (status == NodeHealthStatus.UNAVAILABLE) {
                if (successes >= recoverySuccesses) {
                    status = NodeHealthStatus.HEALTHY;
                    verdict = "不可用节点已连续成功 " + successes + " 次，恢复健康";
                } else {
                    verdict = "不可用节点试探成功 " + successes + "/" + recoverySuccesses
                            + " 次，仍不可被选中";
                }
            }
            NodeState next = new NodeState(id, prev.endpoint(), status, height,
                    0, successes, prev.lastReason(), System.currentTimeMillis());
            log.info("[health] node={} 成功 height={} 连续成功={} -> {}（判定依据：{}）",
                    id, height, successes, status, verdict);
            return next;
        });
    }

    @Override
    public NodeState updateHeight(String nodeId, long latestHeight) {
        return states.compute(nodeId, (id, prev) -> {
            if (prev == null) {
                return new NodeState(id, endpoints.getOrDefault(id, ""),
                        NodeHealthStatus.HEALTHY, latestHeight, 0, 0, null,
                        System.currentTimeMillis());
            }
            long height = Math.max(prev.latestHeight(), latestHeight);
            return new NodeState(id, prev.endpoint(), prev.status(), height,
                    prev.consecutiveFailures(), prev.consecutiveSuccesses(),
                    prev.lastReason(), prev.lastUpdatedEpochMs());
        });
    }

    @Override
    public NodeState stateOf(String nodeId) {
        return states.get(nodeId);
    }

    @Override
    public List<NodeState> snapshot() {
        List<NodeState> list = new ArrayList<>(states.values());
        list.sort(Comparator.comparing(NodeState::nodeId));
        return List.copyOf(list);
    }

    @Override
    public List<NodeState> healthy() {
        return snapshot().stream()
                .filter(s -> s.status() == NodeHealthStatus.HEALTHY)
                .toList();
    }

    @Override
    public boolean isAvailable(String nodeId) {
        NodeState s = states.get(nodeId);
        return s != null && s.status() == NodeHealthStatus.HEALTHY;
    }

    @Override
    public NodeState mark(String nodeId, NodeHealthStatus status, ChainErrorReason reason) {
        return states.compute(nodeId, (id, prev) -> {
            NodeState base = prev != null ? prev : new NodeState(id,
                    endpoints.getOrDefault(id, ""), NodeHealthStatus.HEALTHY, -1L,
                    0, 0, null, System.currentTimeMillis());
            NodeState next = new NodeState(id, base.endpoint(), status, base.latestHeight(),
                    status == NodeHealthStatus.UNAVAILABLE ? failureThreshold : 0,
                    status == NodeHealthStatus.HEALTHY ? recoverySuccesses : 0,
                    reason, System.currentTimeMillis());
            log.info("[health] node={} 被手动置为 {} reason={}", id, status, reason);
            return next;
        });
    }

    @Override
    public void reset() {
        endpoints.forEach((id, endpoint) -> states.put(id, new NodeState(
                id, endpoint, NodeHealthStatus.HEALTHY, -1L, 0, 0, null,
                System.currentTimeMillis())));
        log.info("[health] 全部节点状态已重置为 HEALTHY");
    }
}
