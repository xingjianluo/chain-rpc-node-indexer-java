package com.github.xingjianluo.chainrpcnodeindexerjava.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.xingjianluo.chainrpcnodeindexerjava.config.NodeProperties;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.ChainErrorReason;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.NodeHealthStatus;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.NodeState;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.ChainNode;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.RpcException;

/**
 * {@link RecoveryProbe} 单测：不可用节点的恢复必须凭连续多次成功。
 */
class RecoveryProbeTest {

    private static final Logger log = LoggerFactory.getLogger(RecoveryProbeTest.class);

    static class ToggleNode implements ChainNode {
        final String id;
        boolean up;
        long height;
        int probes;

        ToggleNode(String id, boolean up, long height) {
            this.id = id;
            this.up = up;
            this.height = height;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String endpoint() {
            return "http://" + id;
        }

        @Override
        public long fetchLatestHeight() {
            probes++;
            if (!up) {
                throw new RpcException(ChainErrorReason.NETWORK_UNREACHABLE, id + " 连不上");
            }
            return height;
        }

        @Override
        public com.github.xingjianluo.chainrpcnodeindexerjava.model.Block fetchBlock(long h) {
            throw new UnsupportedOperationException();
        }
    }

    private Harness harness(ToggleNode node) {
        List<NodeProperties> props = List.of(new NodeProperties(node.id, node.endpoint(), true));
        DefaultHealthRegistry registry = new DefaultHealthRegistry(props, 2, 3);
        DefaultNodeSelector selector = new DefaultNodeSelector(registry, List.of(node));
        RecoveryProbe probe = new RecoveryProbe(registry, selector);
        return new Harness(registry, probe);
    }

    private record Harness(DefaultHealthRegistry registry, RecoveryProbe probe) {
    }

    @Test
    void unavailableNodeRecoversOnlyAfterConsecutiveSuccessfulProbes() {
        ToggleNode node = new ToggleNode("node-a", false, 42);
        Harness h = harness(node);

        // 连续两次真实失败 -> 判死
        h.registry.recordFailure("node-a", ChainErrorReason.NETWORK_UNREACHABLE);
        h.registry.recordFailure("node-a", ChainErrorReason.NETWORK_UNREACHABLE);
        assertThat(h.registry.stateOf("node-a").status())
                .isEqualTo(NodeHealthStatus.UNAVAILABLE);

        // 第 1 次探测成功：仍不可用
        node.up = true;
        h.probe.probeOnce();
        NodeState s1 = h.registry.stateOf("node-a");
        log.info("[测试] 探测第1次成功: status={} 连续成功={} 判定依据=需连续3次成功",
                s1.status(), s1.consecutiveSuccesses());
        assertThat(s1.status()).isEqualTo(NodeHealthStatus.UNAVAILABLE);
        assertThat(s1.consecutiveSuccesses()).isEqualTo(1);

        // 中间又失败一次：计数清零
        node.up = false;
        h.probe.probeOnce();
        assertThat(h.registry.stateOf("node-a").consecutiveSuccesses()).isZero();
        assertThat(h.registry.stateOf("node-a").status())
                .isEqualTo(NodeHealthStatus.UNAVAILABLE);

        // 再连续三次成功才恢复
        node.up = true;
        h.probe.probeOnce();
        h.probe.probeOnce();
        assertThat(h.registry.isAvailable("node-a")).isFalse();
        h.probe.probeOnce();
        NodeState recovered = h.registry.stateOf("node-a");
        log.info("[测试] 连续3次探测成功后恢复: status={} height={} 判定依据=达到恢复阈值",
                recovered.status(), recovered.latestHeight());
        assertThat(recovered.status()).isEqualTo(NodeHealthStatus.HEALTHY);
        assertThat(recovered.latestHeight()).isEqualTo(42);
        assertThat(node.probes).isEqualTo(5);
    }

    @Test
    void probeSkipsHealthyNodes() {
        ToggleNode node = new ToggleNode("node-a", true, 10);
        Harness h = harness(node);
        h.probe.probeOnce();
        // 健康节点不会被恢复探测打扰
        assertThat(node.probes).isZero();
    }
}
