package com.github.xingjianluo.chainrpcnodeindexerjava.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.xingjianluo.chainrpcnodeindexerjava.config.NodeProperties;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.Block;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.BlockResult;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.ChainErrorReason;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.LatestHeightResult;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.NodeHealthStatus;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.ChainNode;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.RpcException;

/**
 * {@link DefaultFailoverChainClient} 单测：
 * 节点超时/报错/坏数据时切换、结果来源节点、高度落后交叉验证、全部不可用时报错。
 */
class DefaultFailoverChainClientTest {

    private static final Logger log = LoggerFactory.getLogger(DefaultFailoverChainClientTest.class);

    static class FakeNode implements ChainNode {
        final String id;
        ChainErrorReason failReason; // 非 null 则始终按该原因失败
        long height = -1;
        int heightCalls;
        int blockCalls;

        FakeNode(String id, long height) {
            this.id = id;
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
            heightCalls++;
            if (failReason != null) {
                throw new RpcException(failReason, id + " 模拟失败 " + failReason);
            }
            log.info("[测试] 选中节点={} 查询最新高度 -> {}", id, height);
            return height;
        }

        @Override
        public Block fetchBlock(long h) {
            blockCalls++;
            if (failReason != null) {
                throw new RpcException(failReason, id + " 取块失败 " + failReason);
            }
            if (h > height) {
                return null;
            }
            return new Block(h, id + "-h" + h,
                    h == 0 ? "0x0" : id + "-h" + (h - 1), 1000 + h, "p" + h);
        }
    }

    private record Harness(DefaultFailoverChainClient client,
                           DefaultHealthRegistry registry,
                           DefaultNodeSelector selector,
                           List<FakeNode> fakes) {
    }

    private Harness harness(long lagThreshold, int failureThreshold, FakeNode... fakesArr) {
        List<FakeNode> fakes = List.of(fakesArr);
        List<NodeProperties> props = new ArrayList<>();
        List<ChainNode> nodes = new ArrayList<>();
        for (FakeNode f : fakes) {
            props.add(new NodeProperties(f.id, f.endpoint(), true));
            nodes.add(f);
        }
        DefaultHealthRegistry registry =
                new DefaultHealthRegistry(props, failureThreshold, 2);
        DefaultNodeSelector selector = new DefaultNodeSelector(registry, nodes);
        DefaultFailoverChainClient client =
                new DefaultFailoverChainClient(selector, registry, lagThreshold);
        return new Harness(client, registry, selector, fakes);
    }

    @Test
    void fetchesHighestHeight_andReportsSourceNode() {
        FakeNode a = new FakeNode("node-a", 100);
        FakeNode b = new FakeNode("node-b", 102);
        FakeNode c = new FakeNode("node-c", 101);
        Harness h = harness(5, 3, a, b, c);

        LatestHeightResult result = h.client.fetchLatestHeight();
        log.info("[测试] 集群高度={} 来源节点={} 判定依据=多节点探高取最大值",
                result.height(), result.nodeId());
        assertThat(result.height()).isEqualTo(102);
        assertThat(result.nodeId()).isEqualTo("node-b");
    }

    @Test
    void failoverOnTimeoutErrorAndBadResponse_stillSucceedsFromHealthyNode() {
        FakeNode timeout = new FakeNode("node-a", 100);
        timeout.failReason = ChainErrorReason.NETWORK_UNREACHABLE; // 超时归类
        FakeNode error = new FakeNode("node-b", 100);
        error.failReason = ChainErrorReason.NODE_ERROR;
        FakeNode bad = new FakeNode("node-c", 100);
        bad.failReason = ChainErrorReason.BAD_RESPONSE;
        FakeNode good = new FakeNode("node-d", 100);
        Harness h = harness(5, 3, timeout, error, bad, good);

        LatestHeightResult height = h.client.fetchLatestHeight();
        assertThat(height.nodeId()).isEqualTo("node-d");
        assertThat(height.height()).isEqualTo(100);

        BlockResult block = h.client.fetchBlock(50);
        log.info("[测试] 取块 height=50 实际来源节点={}，前三个节点分别失败原因=超时/节点错误/格式错误",
                block.nodeId());
        assertThat(block.nodeId()).isEqualTo("node-d");
        assertThat(block.block().hash()).isEqualTo("node-d-h50");

        // 前三个节点各记录了可区分的失败原因，但均未达阈值仍是健康
        assertThat(h.registry.stateOf("node-a").lastReason())
                .isEqualTo(ChainErrorReason.NETWORK_UNREACHABLE);
        assertThat(h.registry.stateOf("node-b").lastReason())
                .isEqualTo(ChainErrorReason.NODE_ERROR);
        assertThat(h.registry.stateOf("node-c").lastReason())
                .isEqualTo(ChainErrorReason.BAD_RESPONSE);
        assertThat(h.registry.healthy()).hasSize(4);
    }

    @Test
    void oneJitterDoesNotKillNode_andNextCallUsesItAgain() {
        FakeNode flaky = new FakeNode("node-a", 50);
        FakeNode stable = new FakeNode("node-b", 50);
        Harness h = harness(5, 3, flaky, stable);

        flaky.failReason = ChainErrorReason.NETWORK_UNREACHABLE;
        h.client.fetchLatestHeight(); // node-a 抖一次，切到 node-b 成功
        assertThat(h.registry.isAvailable("node-a")).isTrue();

        flaky.failReason = null; // 恢复
        LatestHeightResult again = h.client.fetchLatestHeight();
        // 两者等高，node-a 按 ID 优先
        assertThat(again.nodeId()).isEqualTo("node-a");
    }

    @Test
    void laggingNodeIsRecordedButHeightAdoptsBest() {
        FakeNode laggard = new FakeNode("node-a", 80);
        FakeNode leader = new FakeNode("node-b", 100);
        Harness h = harness(5, 3, laggard, leader);

        LatestHeightResult result = h.client.fetchLatestHeight();
        assertThat(result.height()).isEqualTo(100);
        assertThat(h.registry.stateOf("node-a").lastReason())
                .isEqualTo(ChainErrorReason.HEIGHT_LAGGING);
        // 单次落后未达失败阈值，暂不判死
        assertThat(h.registry.isAvailable("node-a")).isTrue();
    }

    @Test
    void persistentlyFailingNodeGetsExcludedFromCandidates() {
        FakeNode bad = new FakeNode("node-a", 100);
        bad.failReason = ChainErrorReason.NODE_ERROR;
        FakeNode good = new FakeNode("node-b", 100);
        Harness h = harness(5, 2, bad, good);

        h.client.fetchLatestHeight();
        h.client.fetchLatestHeight();
        assertThat(h.registry.stateOf("node-a").status())
                .isEqualTo(NodeHealthStatus.UNAVAILABLE);
        assertThat(h.selector.selectCandidates()).extracting(ChainNode::id)
                .containsExactly("node-b");
    }

    @Test
    void throwsWhenNoHealthyNode() {
        FakeNode dead = new FakeNode("node-a", 10);
        dead.failReason = ChainErrorReason.NETWORK_UNREACHABLE;
        Harness h = harness(5, 1, dead);
        // 阈值 1：唯一节点第一次失败即判死，调用直接失败
        assertThatThrownBy(h.client::fetchLatestHeight)
                .isInstanceOf(NoHealthyNodeException.class);
        assertThat(h.registry.stateOf("node-a").status())
                .isEqualTo(NodeHealthStatus.UNAVAILABLE);
        // 此后候选为空
        assertThat(h.selector.selectCandidates()).isEmpty();
        assertThatThrownBy(h.client::fetchLatestHeight)
                .isInstanceOf(NoHealthyNodeException.class)
                .hasMessageContaining("没有可用的健康节点");
    }

    @Test
    void fetchBlock_skipsNodeThatDoesNotHaveHeightYet() {
        FakeNode behind = new FakeNode("node-a", 10);   // 健康但数据暂不全
        FakeNode ahead = new FakeNode("node-b", 20);
        Harness h = harness(5, 3, behind, ahead);

        // 不因为“暂时没有该高度”给 behind 记失败
        BlockResult result = h.client.fetchBlock(15);
        assertThat(result.nodeId()).isEqualTo("node-b");
        assertThat(h.registry.stateOf("node-a").consecutiveFailures()).isZero();
    }
}
