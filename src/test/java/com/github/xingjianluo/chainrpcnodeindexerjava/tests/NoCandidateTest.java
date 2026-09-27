package com.github.xingjianluo.chainrpcnodeindexerjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.xingjianluo.chainrpcnodeindexerjava.health.NodeHealthTracker;
import com.github.xingjianluo.chainrpcnodeindexerjava.query.AllNodesFailedException;
import com.github.xingjianluo.chainrpcnodeindexerjava.query.ChainQueryService;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.RpcException;
import java.util.List;
import org.junit.jupiter.api.Test;

class NoCandidateTest {

    @Test
    void emptyUpstreamsThrowsAllNodesFailed() {
        ChainQueryService service = new ChainQueryService(
                List.of(), new NodeHealthTracker(3, 2, 1000, 2));
        AllNodesFailedException ex = assertThrows(AllNodesFailedException.class,
                service::latestHeight);
        assertEquals(null, ex.lastReason(), "没有任何候选节点时原因为 null，接口仍返回 503");
    }
}
