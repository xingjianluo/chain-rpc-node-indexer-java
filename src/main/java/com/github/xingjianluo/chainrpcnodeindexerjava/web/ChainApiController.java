package com.github.xingjianluo.chainrpcnodeindexerjava.web;

import com.github.xingjianluo.chainrpcnodeindexerjava.chain.Block;
import com.github.xingjianluo.chainrpcnodeindexerjava.health.NodeHealth;
import com.github.xingjianluo.chainrpcnodeindexerjava.health.NodeHealthTracker;
import com.github.xingjianluo.chainrpcnodeindexerjava.index.ChainIndexer;
import com.github.xingjianluo.chainrpcnodeindexerjava.index.ChainSnapshot;
import com.github.xingjianluo.chainrpcnodeindexerjava.query.ChainQueryService;
import com.github.xingjianluo.chainrpcnodeindexerjava.query.NodeResponse;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对外 REST 接口。
 *
 * <ul>
 *     <li>{@code GET /api/height}            最新高度（带来源节点）；</li>
 *     <li>{@code GET /api/blocks/{height}}   上游单块查询（带故障转移与来源节点）；</li>
 *     <li>{@code GET /api/chain/head}        本地索引链头；</li>
 *     <li>{@code GET /api/chain/blocks/{h}}  本地索引区块（重组期间也保持一致）；</li>
 *     <li>{@code GET /api/nodes}             各节点健康状态与判定依据。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class ChainApiController {

    private final ChainQueryService queryService;
    private final ChainIndexer indexer;
    private final NodeHealthTracker tracker;

    public ChainApiController(ChainQueryService queryService, ChainIndexer indexer,
                              NodeHealthTracker tracker) {
        this.queryService = queryService;
        this.indexer = indexer;
        this.tracker = tracker;
    }

    @GetMapping("/height")
    public Map<String, Object> height() {
        NodeResponse<Long> resp = queryService.latestHeight();
        return Map.of(
                "height", resp.data(),
                "sourceNode", resp.node(),
                "nodeHeight", resp.nodeHeight());
    }

    @GetMapping("/blocks/{height}")
    public Map<String, Object> blockFromUpstream(@PathVariable long height) {
        NodeResponse<Block> resp = queryService.blockByHeight(height);
        return Map.of(
                "source", "upstream",
                "sourceNode", resp.node(),
                "nodeHeight", resp.nodeHeight(),
                "block", resp.data());
    }

    @GetMapping("/chain/head")
    public Map<String, Object> chainHead() {
        ChainSnapshot snapshot = indexer.snapshot();
        return Map.of(
                "indexedHeight", snapshot.headHeight(),
                "headHash", snapshot.headHash() == null ? "" : snapshot.headHash());
    }

    @GetMapping("/chain/blocks/{height}")
    public Map<String, Object> indexedBlock(@PathVariable long height) {
        ChainSnapshot snapshot = indexer.snapshot();
        Block block = snapshot.atHeight(height);
        if (block == null) {
            return Map.of("found", false, "height", height, "indexedHeight", snapshot.headHeight());
        }
        return Map.of("found", true, "block", block);
    }

    @GetMapping("/nodes")
    public List<Map<String, Object>> nodes() {
        return tracker.snapshot().stream().map(ChainApiController::healthView).toList();
    }

    private static Map<String, Object> healthView(NodeHealth h) {
        return Map.of(
                "node", h.getName(),
                "status", h.getStatus().name(),
                "latestHeight", h.getLatestHeight(),
                "consecutiveFailures", h.getConsecutiveFailures(),
                "consecutiveSuccesses", h.getConsecutiveSuccesses(),
                "lastReason", h.getLastReason() == null ? "" : h.getLastReason().name(),
                "lastDetail", h.getLastDetail());
    }
}
