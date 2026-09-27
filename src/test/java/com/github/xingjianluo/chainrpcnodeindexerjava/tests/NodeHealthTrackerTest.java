package com.github.xingjianluo.chainrpcnodeindexerjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.xingjianluo.chainrpcnodeindexerjava.health.HealthStatus;
import com.github.xingjianluo.chainrpcnodeindexerjava.health.NodeHealth;
import com.github.xingjianluo.chainrpcnodeindexerjava.health.NodeHealthTracker;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.FailureReason;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.SimulatedChain;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.SimulatedUpstreamNode;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.UpstreamNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class NodeHealthTrackerTest {

    private static final Logger log = LoggerFactory.getLogger(NodeHealthTrackerTest.class);

    private NodeHealthTracker tracker(int failThreshold, int recoverSuccesses,
                                      long cooldownMs, long lagThreshold) {
        return new NodeHealthTracker(failThreshold, recoverSuccesses, cooldownMs, lagThreshold);
    }

    private UpstreamNode node(String name) {
        return new SimulatedUpstreamNode(name, new SimulatedChain(3), 500);
    }

    @Test
    void singleJitterDoesNotKillNodeAndItRecovers() {
        NodeHealthTracker t = tracker(3, 2, 1000, 2);
        UpstreamNode a = node("a");
        t.register(a);

        t.recordFailure("a", FailureReason.CONNECTIVITY, "一次超时");
        assertEquals(HealthStatus.AVAILABLE, t.snapshot().get(0).getStatus(),
                "偶发一次失败不能判死");
        t.recordSuccess("a", 10);
        assertEquals(HealthStatus.AVAILABLE, t.snapshot().get(0).getStatus());
        assertEquals(0, t.snapshot().get(0).getConsecutiveFailures());
        log.info("[抖动恢复] 节点=a 判定依据=连续失败<阈值，成功后清零，状态=AVAILABLE，高度=10");
    }

    @Test
    void consecutiveFailuresOpenCircuitAndNodeIsExcluded() {
        NodeHealthTracker t = tracker(3, 2, 100_000, 2);
        UpstreamNode a = node("a");
        UpstreamNode b = node("b");
        t.register(a);
        t.register(b);

        t.recordFailure("a", FailureReason.CONNECTIVITY, "超时1");
        t.recordFailure("a", FailureReason.RPC_ERROR, "报错2");
        t.recordFailure("a", FailureReason.BAD_RESPONSE, "坏响应3");

        NodeHealth ha = t.snapshot().get(0);
        assertEquals(HealthStatus.UNAVAILABLE, ha.getStatus());
        assertEquals(FailureReason.BAD_RESPONSE, ha.getLastReason(), "最后原因可区分");
        List<String> names = t.candidates().stream().map(UpstreamNode::name).toList();
        assertFalse(names.contains("a"), "判死后不能再被选中");
        assertTrue(names.contains("b"));
        log.info("[熔断] 节点=a 判定依据=连续3次失败(阈值3)，状态=UNAVAILABLE，候选={}", names);
    }

    @Test
    void probingRequiresConsecutiveSuccessesSingleLuckySuccessKeepsProbing() throws Exception {
        NodeHealthTracker t = tracker(1, 2, 10, 2);
        UpstreamNode a = node("a");
        t.register(a);

        t.recordFailure("a", FailureReason.RPC_ERROR, "挂了");
        assertEquals(HealthStatus.UNAVAILABLE, t.snapshot().get(0).getStatus());
        Thread.sleep(30);
        // 冷却到期：唯一候选是 a，取候选时转 PROBING
        List<UpstreamNode> candidates = t.candidates();
        assertEquals("a", candidates.get(0).name());
        assertEquals(HealthStatus.PROBING, t.snapshot().get(0).getStatus());

        t.recordSuccess("a", 5);
        assertEquals(HealthStatus.PROBING, t.snapshot().get(0).getStatus(),
                "单次侥幸成功不能恢复，需要连续 2 次");
        t.recordSuccess("a", 5);
        assertEquals(HealthStatus.AVAILABLE, t.snapshot().get(0).getStatus());
        log.info("[探测恢复] 节点=a 判定依据=PROBING 连续2次成功，状态=AVAILABLE，高度=5");
    }

    @Test
    void failureDuringProbingSendsBackToUnavailable() throws Exception {
        NodeHealthTracker t = tracker(1, 2, 10, 2);
        UpstreamNode a = node("a");
        t.register(a);
        t.recordFailure("a", FailureReason.CONNECTIVITY, "down");
        Thread.sleep(30);
        t.candidates();
        t.recordSuccess("a", 5);
        t.recordFailure("a", FailureReason.CONNECTIVITY, "又抖了");
        assertEquals(HealthStatus.UNAVAILABLE, t.snapshot().get(0).getStatus(),
                "探测期失败立即打回 UNAVAILABLE");
        log.info("[探测失败] 节点=a 判定依据=PROBING 期间再次失败，状态=UNAVAILABLE");
    }

    @Test
    void laggingNodeIsMarkedAndDeprioritizedButStillUsable() {
        NodeHealthTracker t = tracker(3, 2, 1000, 2);
        UpstreamNode a = node("a");
        UpstreamNode b = node("b");
        t.register(a);
        t.register(b);

        t.recordSuccess("a", 100);
        t.recordSuccess("b", 95); // 落后 5 > 阈值 2
        NodeHealth hb = t.snapshot().get(1);
        assertEquals(HealthStatus.LAGGING, hb.getStatus());
        List<String> names = t.candidates().stream().map(UpstreamNode::name).toList();
        assertEquals(List.of("a", "b"), names, "落后节点排在健康节点后面，但仍可用");

        t.recordSuccess("b", 100); // 追平
        assertEquals(HealthStatus.AVAILABLE, t.snapshot().get(1).getStatus());
        log.info("[滞后] 节点=b 当前高度=95 网络最佳=100 判定依据=落后5>阈值2，状态=LAGGING；追平后=AVAILABLE");
    }

    @Test
    void failureReasonsAreDistinguishable() {
        NodeHealthTracker t = tracker(10, 2, 1000, 2);
        UpstreamNode a = node("a");
        t.register(a);
        t.recordFailure("a", FailureReason.CONNECTIVITY, "连不上");
        assertEquals(FailureReason.CONNECTIVITY, t.snapshot().get(0).getLastReason());
        t.recordFailure("a", FailureReason.RPC_ERROR, "节点报错");
        assertEquals(FailureReason.RPC_ERROR, t.snapshot().get(0).getLastReason());
        t.recordFailure("a", FailureReason.BAD_RESPONSE, "格式坏");
        assertEquals(FailureReason.BAD_RESPONSE, t.snapshot().get(0).getLastReason());
        t.recordFailure("a", FailureReason.LAGGING, "高度落后");
        assertEquals(FailureReason.LAGGING, t.snapshot().get(0).getLastReason());
        assertNotNull(t.snapshot().get(0).getLastDetail());
        log.info("[原因分类] 同一节点依次记录 CONNECTIVITY/RPC_ERROR/BAD_RESPONSE/LAGGING，均可区分");
    }
}
