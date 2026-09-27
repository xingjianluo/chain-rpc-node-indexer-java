package com.github.xingjianluo.chainrpcnodeindexerjava.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.xingjianluo.chainrpcnodeindexerjava.config.NodeProperties;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.ChainErrorReason;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.NodeHealthStatus;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.NodeState;

/**
 * {@link DefaultHealthRegistry} 单测：健康判定与恢复状态机。
 */
class DefaultHealthRegistryTest {

    private DefaultHealthRegistry registry(int failureThreshold, int recoverySuccesses) {
        return new DefaultHealthRegistry(List.of(
                new NodeProperties("node-a", "http://a", true),
                new NodeProperties("node-b", "http://b", true)),
                failureThreshold, recoverySuccesses);
    }

    @Test
    void startsHealthy() {
        DefaultHealthRegistry registry = registry(3, 2);
        assertThat(registry.isAvailable("node-a")).isTrue();
        assertThat(registry.healthy()).hasSize(2);
        assertThat(registry.stateOf("node-a").latestHeight()).isEqualTo(-1);
    }

    @Test
    void singleJitterDoesNotKillNode() {
        DefaultHealthRegistry registry = registry(3, 2);
        registry.recordFailure("node-a", ChainErrorReason.NETWORK_UNREACHABLE);
        assertThat(registry.isAvailable("node-a")).isTrue();
        assertThat(registry.stateOf("node-a").consecutiveFailures()).isEqualTo(1);
        assertThat(registry.stateOf("node-a").lastReason())
                .isEqualTo(ChainErrorReason.NETWORK_UNREACHABLE);

        // 一次成功即清零失败计数，健康节点不会被偶发抖动判死
        registry.recordSuccess("node-a", 10);
        NodeState state = registry.stateOf("node-a");
        assertThat(state.consecutiveFailures()).isZero();
        assertThat(state.status()).isEqualTo(NodeHealthStatus.HEALTHY);
        assertThat(state.latestHeight()).isEqualTo(10);
    }

    @Test
    void consecutiveFailuresReachThreshold_markUnavailableAndNotSelectable() {
        DefaultHealthRegistry registry = registry(3, 2);
        registry.recordFailure("node-a", ChainErrorReason.NODE_ERROR);
        registry.recordFailure("node-a", ChainErrorReason.BAD_RESPONSE);
        assertThat(registry.isAvailable("node-a")).isTrue();

        NodeState dead = registry.recordFailure("node-a", ChainErrorReason.NETWORK_UNREACHABLE);
        assertThat(dead.status()).isEqualTo(NodeHealthStatus.UNAVAILABLE);
        assertThat(registry.isAvailable("node-a")).isFalse();
        assertThat(registry.healthy()).extracting(NodeState::nodeId).containsExactly("node-b");
        // 最近一次失败原因可区分
        assertThat(dead.lastReason()).isEqualTo(ChainErrorReason.NETWORK_UNREACHABLE);
    }

    @Test
    void failuresAreResetByIntermittentSuccess() {
        DefaultHealthRegistry registry = registry(3, 2);
        registry.recordFailure("node-a", ChainErrorReason.NODE_ERROR);
        registry.recordFailure("node-a", ChainErrorReason.NODE_ERROR);
        registry.recordSuccess("node-a", 5);
        registry.recordFailure("node-a", ChainErrorReason.NODE_ERROR);
        registry.recordFailure("node-a", ChainErrorReason.NODE_ERROR);
        // 中间成功打断了连续性，连续失败只有 2，仍健康
        assertThat(registry.isAvailable("node-a")).isTrue();
    }

    @Test
    void laggingFailureCountsTowardThreshold() {
        DefaultHealthRegistry registry = registry(2, 2);
        registry.recordFailure("node-a", ChainErrorReason.HEIGHT_LAGGING);
        NodeState after = registry.recordFailure("node-a", ChainErrorReason.HEIGHT_LAGGING);
        assertThat(after.status()).isEqualTo(NodeHealthStatus.UNAVAILABLE);
        assertThat(after.lastReason()).isEqualTo(ChainErrorReason.HEIGHT_LAGGING);
    }

    @Test
    void recoveryRequiresConsecutiveSuccesses_singleLuckySuccessNotEnough() {
        DefaultHealthRegistry registry = registry(1, 2);
        registry.recordFailure("node-a", ChainErrorReason.NETWORK_UNREACHABLE);
        assertThat(registry.isAvailable("node-a")).isFalse();

        // 单次侥幸成功：仍不可被选中
        NodeState first = registry.recordSuccess("node-a", 8);
        assertThat(first.status()).isEqualTo(NodeHealthStatus.UNAVAILABLE);
        assertThat(first.consecutiveSuccesses()).isEqualTo(1);
        assertThat(registry.isAvailable("node-a")).isFalse();

        // 试探期间一次失败，连续成功计数清零重来
        registry.recordFailure("node-a", ChainErrorReason.NODE_ERROR);
        registry.recordSuccess("node-a", 8);
        assertThat(registry.isAvailable("node-a")).isFalse();

        // 再次连续两次成功才恢复
        NodeState recovered = registry.recordSuccess("node-a", 8);
        assertThat(recovered.status()).isEqualTo(NodeHealthStatus.HEALTHY);
        assertThat(recovered.consecutiveSuccesses()).isEqualTo(2);
        assertThat(registry.isAvailable("node-a")).isTrue();
    }

    @Test
    void updateHeightDoesNotChangeCounters() {
        DefaultHealthRegistry registry = registry(2, 2);
        registry.recordFailure("node-a", ChainErrorReason.NODE_ERROR);
        NodeState updated = registry.updateHeight("node-a", 99);
        assertThat(updated.latestHeight()).isEqualTo(99);
        assertThat(updated.consecutiveFailures()).isEqualTo(1);
        assertThat(updated.status()).isEqualTo(NodeHealthStatus.HEALTHY);
    }

    @Test
    void snapshotIsSortedById() {
        DefaultHealthRegistry registry = registry(3, 2);
        assertThat(registry.snapshot()).extracting(NodeState::nodeId)
                .containsExactly("node-a", "node-b");
    }
}
