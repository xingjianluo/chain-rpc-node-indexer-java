package com.github.xingjianluo.chainrpcnodeindexerjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.xingjianluo.chainrpcnodeindexerjava.chain.Block;
import com.github.xingjianluo.chainrpcnodeindexerjava.health.HealthStatus;
import com.github.xingjianluo.chainrpcnodeindexerjava.health.NodeHealthTracker;
import com.github.xingjianluo.chainrpcnodeindexerjava.query.AllNodesFailedException;
import com.github.xingjianluo.chainrpcnodeindexerjava.query.ChainQueryService;
import com.github.xingjianluo.chainrpcnodeindexerjava.query.NodeResponse;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.FailureReason;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.SimulatedChain;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.SimulatedUpstreamNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ChainQueryServiceTest {

    private static final Logger log = LoggerFactory.getLogger(ChainQueryServiceTest.class);

    private record Harness(ChainQueryService service, NodeHealthTracker tracker,
                           SimulatedUpstreamNode a, SimulatedUpstreamNode b, SimulatedChain chain) {
    }

    private Harness harness() {
        SimulatedChain chain = new SimulatedChain(10);
        SimulatedUpstreamNode a = new SimulatedUpstreamNode("a", chain, 500);
        SimulatedUpstreamNode b = new SimulatedUpstreamNode("b", chain, 500);
        NodeHealthTracker tracker = new NodeHealthTracker(3, 2, 1000, 2);
        ChainQueryService service = new ChainQueryService(List.of(a, b), tracker);
        return new Harness(service, tracker, a, b, chain);
    }

    @Test
    void healthyQueryReturnsSourceNodeAndHeight() {
        Harness h = harness();
        NodeResponse<Long> resp = h.service.latestHeight();
        assertEquals("a", resp.node(), "默认选中排序第一的节点");
        assertEquals(9L, resp.data());
        assertEquals(9L, resp.nodeHeight());
        log.info("[正常链路] 选中节点={} 当前高度={} 判定依据=AVAILABLE且高度最高", resp.node(), resp.data());
    }

    @Test
    void connectivityTimeoutFailsOverToNextNode() {
        Harness h = harness();
        h.a.failNext(1, FailureReason.CONNECTIVITY);
        NodeResponse<Long> resp = h.service.latestHeight();
        assertEquals("b", resp.node(), "a 超时应切到 b");
        assertEquals(HealthStatus.AVAILABLE,
                h.tracker.snapshot().get(0).getStatus(), "偶发一次超时不应判死 a");
        log.info("[故障转移-超时] a 原因=CONNECTIVITY，切到节点={} 高度={}；a 仍 AVAILABLE",
                resp.node(), resp.data());
    }

    @Test
    void rpcErrorAndBadResponseBothFailOverWithDistinctReasons() {
        // 场景 1：节点返回 JSON-RPC error -> RPC_ERROR
        Harness h1 = harness();
        h1.a.failNext(1, FailureReason.RPC_ERROR);
        assertEquals("b", h1.service.latestHeight().node());
        assertEquals(FailureReason.RPC_ERROR, h1.tracker.snapshot().get(0).getLastReason());

        // 场景 2：节点真的返回结构错误的内容 -> BAD_RESPONSE（独立 harness 避免高度排序影响）
        Harness h2 = harness();
        h2.a.setAlwaysMalformed(true);
        NodeResponse<Block> resp = h2.service.blockByHeight(1);
        assertEquals("b", resp.node());
        assertEquals(1L, resp.data().height());
        assertEquals(FailureReason.BAD_RESPONSE, h2.tracker.snapshot().get(0).getLastReason());
        log.info("[故障转移-错误分类] 选中节点={}；RPC_ERROR 与 BAD_RESPONSE 均被正确区分",
                resp.node());
    }

    @Test
    void allNodesFailedReportsLastReason() {
        Harness h = harness();
        h.a.setAlwaysError(true);
        h.b.failNext(100, FailureReason.CONNECTIVITY);
        AllNodesFailedException ex = assertThrows(AllNodesFailedException.class,
                h.service::latestHeight);
        assertEquals(FailureReason.CONNECTIVITY, ex.lastReason());
        log.info("[全失败] 最终原因={}（a=RPC_ERROR 后 b=CONNECTIVITY）", ex.lastReason());
    }

    @Test
    void laggingNodeIsOnlyChosenWhenHealthyOnesFail() {
        SimulatedChain chain = new SimulatedChain(10);
        SimulatedUpstreamNode a = new SimulatedUpstreamNode("a", chain, 500);
        SimulatedUpstreamNode lag = new SimulatedUpstreamNode("lag", chain, 500);
        lag.setLagBy(5);
        NodeHealthTracker tracker = new NodeHealthTracker(3, 2, 1000, 2);
        ChainQueryService service = new ChainQueryService(List.of(a, lag), tracker);

        // 先建立网络最佳高度（a=9），再让滞后节点被选中汇报 4，触发 LAGGING 判定
        tracker.recordSuccess("a", 9L);
        a.failNext(1, FailureReason.CONNECTIVITY);
        NodeResponse<Long> warmed = service.latestHeight();
        assertEquals("lag", warmed.node());
        assertEquals(HealthStatus.LAGGING, tracker.snapshot().get(1).getStatus());

        // a 恢复后正常请求优先回到健康节点
        assertEquals("a", service.latestHeight().node());

        // a 再次暂时故障：本轮允许落到滞后节点，服务仍可用
        a.failNext(1, FailureReason.CONNECTIVITY);
        NodeResponse<Long> resp = service.latestHeight();
        assertEquals("lag", resp.node());
        assertEquals(4L, resp.data(), "滞后节点看到的高度为 9-5=4");
        log.info("[滞后兜底] 健康节点 a 暂时 CONNECTIVITY，选中滞后节点={} 高度={}",
                resp.node(), resp.data());
        assertTrue(true);
    }
}
