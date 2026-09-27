package com.github.xingjianluo.chainrpcnodeindexerjava.health;

import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.FailureReason;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.UpstreamNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 多节点健康状态机与选择器。
 *
 * <p>规则（所有阈值可配，见 {@code app.health.*}）：
 * <ol>
 *     <li><b>偶发抖动不判死</b>：一次失败只累加 consecutiveFailures，
 *     连续失败达到 {@code fail-threshold} 次才进入 {@link HealthStatus#UNAVAILABLE}；</li>
 *     <li><b>判死后不选中</b>：UNAVAILABLE 的节点不出现在候选里；冷却
 *     {@code probe-cooldown-millis} 后，每轮最多放一个节点进入 {@link HealthStatus#PROBING}
 *         试探；</li>
 *     <li><b>恢复需连续成功</b>：PROBING 必须连续成功 {@code recover-successes} 次才恢复
 *     AVAILABLE；探测期间任意一次失败立即打回 UNAVAILABLE 并重新冷却；</li>
 *     <li><b>滞后单独标记</b>：成功取到高度，但低于网络最佳高度超过 {@code lag-threshold}
 *     时记为 {@link HealthStatus#LAGGING}（仍然健康，只排到队尾）；</li>
 *     <li>候选顺序：AVAILABLE（按最新高度降序）→ 至多一个 PROBING → LAGGING。</li>
 * </ol>
 */
public class NodeHealthTracker {

    private static final Logger log = LoggerFactory.getLogger(NodeHealthTracker.class);

    private final int failThreshold;
    private final int recoverSuccesses;
    private final long probeCooldownMillis;
    private final long lagThreshold;

    private final List<UpstreamNode> nodes = new ArrayList<>();
    private final Map<String, NodeHealth> health = new LinkedHashMap<>();

    public NodeHealthTracker(int failThreshold, int recoverSuccesses,
                             long probeCooldownMillis, long lagThreshold) {
        this.failThreshold = failThreshold;
        this.recoverSuccesses = recoverSuccesses;
        this.probeCooldownMillis = probeCooldownMillis;
        this.lagThreshold = lagThreshold;
    }

    public synchronized void register(UpstreamNode node) {
        if (health.containsKey(node.name())) {
            throw new IllegalArgumentException("重复的节点名: " + node.name());
        }
        nodes.add(node);
        health.put(node.name(), new NodeHealth(node.name()));
    }

    public synchronized int size() {
        return nodes.size();
    }

    /**
     * 本轮允许尝试的节点，按优先级排序。调用方按顺序 failover。
     * 不可用节点默认排除；冷却到期时放唯一一个最高优先级的不可用节点做探测。
     */
    public synchronized List<UpstreamNode> candidates() {
        long now = System.currentTimeMillis();
        List<UpstreamNode> available = new ArrayList<>();
        List<UpstreamNode> lagging = new ArrayList<>();
        UpstreamNode probe = null;
        long probePriority = Long.MIN_VALUE;

        for (UpstreamNode node : nodes) {
            NodeHealth h = health.get(node.name());
            switch (h.getStatus()) {
                case AVAILABLE -> available.add(node);
                case LAGGING -> lagging.add(node);
                case PROBING -> {
                    // 已在探测中：允许继续尝试，并作为最高优先级的探测节点
                    if (probe == null || h.getLatestHeight() > probePriority) {
                        probe = node;
                        probePriority = h.getLatestHeight();
                    }
                }
                case UNAVAILABLE -> {
                    if (probe == null
                            && h.getConsecutiveFailures() >= failThreshold
                            && now - h.getLastChangedAt() >= probeCooldownMillis) {
                        probe = node;
                        probePriority = h.getLatestHeight();
                    }
                }
            }
        }

        available.sort(Comparator
                .comparingLong((UpstreamNode n) -> health.get(n.name()).getLatestHeight()).reversed());
        lagging.sort(Comparator
                .comparingLong((UpstreamNode n) -> health.get(n.name()).getLatestHeight()).reversed());

        List<UpstreamNode> result = new ArrayList<>(available.size() + lagging.size() + 1);
        result.addAll(available);
        if (probe != null) {
            NodeHealth ph = health.get(probe.name());
            if (ph.getStatus() == HealthStatus.UNAVAILABLE) {
                ph.setStatus(HealthStatus.PROBING);
                log.info("节点 [{}] 冷却到期，放入 PROBING 试探（累计失败 {} 次）",
                        probe.name(), ph.getConsecutiveFailures());
            }
            result.add(probe);
        }
        result.addAll(lagging);
        return result;
    }

    /**
     * 记录一次成功。
     *
     * @param latestHeight 本次从该节点观察到的最新高度，用于滞后判断与网络最佳高度
     */
    public synchronized void recordSuccess(String node, long latestHeight) {
        NodeHealth h = require(node);
        h.markAttempt();
        h.setLatestHeight(latestHeight);
        h.setConsecutiveFailures(0);
        int successes = h.getConsecutiveSuccesses() + 1;
        h.setConsecutiveSuccesses(successes);
        long best = computeBestHeight();
        boolean lag = best - latestHeight > lagThreshold;

        switch (h.getStatus()) {
            case PROBING -> {
                if (lag) {
                    // 探测成功但落后：仍需继续成功，保持 PROBING
                    h.setStatus(HealthStatus.PROBING);
                    log.info("节点 [{}] 探测成功({}/{}) 但高度 {} 落后于最佳 {}，继续观察",
                            node, successes, recoverSuccesses, latestHeight, best);
                } else if (successes >= recoverSuccesses) {
                    h.setStatus(HealthStatus.AVAILABLE);
                    log.info("节点 [{}] 连续成功 {} 次，恢复 AVAILABLE，当前高度 {}",
                            node, successes, latestHeight);
                } else {
                    log.info("节点 [{}] 探测成功 {}/{}，等待连续成功确认",
                            node, successes, recoverSuccesses);
                }
            }
            case UNAVAILABLE -> {
                // 理论上成功前必先 PROBING；防御性处理：单次成功不恢复
                h.setStatus(HealthStatus.PROBING);
                log.info("节点 [{}] 在 UNAVAILABLE 下侥幸成功一次，转入 PROBING 继续观察", node);
            }
            case AVAILABLE -> {
                if (lag) {
                    h.setStatus(HealthStatus.LAGGING);
                    log.info("节点 [{}] 高度 {} 落后于网络最佳 {}（阈值 {}），标记 LAGGING",
                            node, latestHeight, best, lagThreshold);
                } else {
                    log.debug("节点 [{}] 健康，高度 {}", node, latestHeight);
                }
            }
            case LAGGING -> {
                if (!lag) {
                    h.setStatus(HealthStatus.AVAILABLE);
                    log.info("节点 [{}] 高度追平到 {}，恢复 AVAILABLE", node, latestHeight);
                }
            }
        }
    }

    /**
     * 记录一次失败。
     */
    public synchronized void recordFailure(String node, FailureReason reason, String detail) {
        NodeHealth h = require(node);
        h.markAttempt();
        h.setConsecutiveSuccesses(0);
        int failures = h.getConsecutiveFailures() + 1;
        h.setConsecutiveFailures(failures);
        h.setLastFailure(reason, detail);

        switch (h.getStatus()) {
            case PROBING -> {
                h.setStatus(HealthStatus.UNAVAILABLE);
                log.warn("节点 [{}] 探测期失败（{}），立即打回 UNAVAILABLE 重新冷却: {}",
                        node, reason, detail);
            }
            case UNAVAILABLE -> log.warn("节点 [{}] 仍 UNAVAILABLE，失败原因 {}: {}", node, reason, detail);
            case LAGGING, AVAILABLE -> {
                if (failures >= failThreshold) {
                    h.setStatus(HealthStatus.UNAVAILABLE);
                    log.warn("节点 [{}] 连续失败 {} 次达到阈值，判定 UNAVAILABLE，最后原因 {}: {}",
                            node, failures, reason, detail);
                } else {
                    log.warn("节点 [{}] 第 {}/{} 次失败（{}），暂不剔除: {}",
                            node, failures, failThreshold, reason, detail);
                }
            }
        }
    }

    /** 所有成功报告过高度的节点中的最大值；没有则 -1。 */
    public synchronized long networkBestHeight() {
        return computeBestHeight();
    }

    private long computeBestHeight() {
        long best = -1L;
        for (NodeHealth h : health.values()) {
            best = Math.max(best, h.getLatestHeight());
        }
        return best;
    }

    public synchronized List<NodeHealth> snapshot() {
        List<NodeHealth> copies = new ArrayList<>(health.size());
        for (NodeHealth h : health.values()) {
            copies.add(h.copy());
        }
        return copies;
    }

    private NodeHealth require(String name) {
        NodeHealth h = health.get(name);
        if (h == null) {
            throw new IllegalArgumentException("未注册的节点: " + name);
        }
        return h;
    }
}
