package com.github.xingjianluo.chainrpcnodeindexerjava.api;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.github.xingjianluo.chainrpcnodeindexerjava.api.dto.BlockDto;
import com.github.xingjianluo.chainrpcnodeindexerjava.api.dto.HealthDto;
import com.github.xingjianluo.chainrpcnodeindexerjava.indexer.BlockIndexer;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.Block;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.BlockResult;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.LatestHeightResult;
import com.github.xingjianluo.chainrpcnodeindexerjava.node.FailoverChainClient;
import com.github.xingjianluo.chainrpcnodeindexerjava.node.HealthRegistry;
import com.github.xingjianluo.chainrpcnodeindexerjava.storage.BlockStore;

/**
 * 链上数据查询接口。
 * <ul>
 *   <li>GET /blocks/latest?source=local|live —— 默认读本地索引（重组期间一致）；
 *       live 直接走上游多节点故障转移，结果带源节点；</li>
 *   <li>GET /blocks/{height}?source=local|live —— 同上；</li>
 *   <li>GET /nodes/health —— 各节点状态、最新高度、连续成败、最近失败原因、本地索引高度。</li>
 * </ul>
 */
@RestController
public class ChainController {

    private static final Logger log = LoggerFactory.getLogger(ChainController.class);
    private static final String LOCAL = "local";

    private final FailoverChainClient client;
    private final BlockStore store;
    private final HealthRegistry registry;
    private final BlockIndexer indexer;

    public ChainController(FailoverChainClient client, BlockStore store,
                           HealthRegistry registry, BlockIndexer indexer) {
        this.client = client;
        this.store = store;
        this.registry = registry;
        this.indexer = indexer;
    }

    @GetMapping("/blocks/latest")
    public ResponseEntity<BlockDto> latestBlock(
            @RequestParam(name = "source", defaultValue = LOCAL) String source) {
        if (LOCAL.equalsIgnoreCase(source)) {
            Block block = store.latest();
            log.info("[api] 查询最新块 source=local indexedHeight={} found={}",
                    indexer.indexedHeight(), block != null);
            return toResponse(block, "local-index", true);
        }
        BlockResult result = client.fetchLatestBlock();
        log.info("[api] 查询最新块 source=live node={} height={}",
                result.nodeId(), result.block() == null ? null : result.block().height());
        return toResponse(result.block(), result.nodeId(), false);
    }

    @GetMapping("/blocks/{height}")
    public ResponseEntity<BlockDto> blockByHeight(
            @PathVariable long height,
            @RequestParam(name = "source", defaultValue = LOCAL) String source) {
        if (height < 0) {
            throw new IllegalArgumentException("height 不能为负: " + height);
        }
        if (LOCAL.equalsIgnoreCase(source)) {
            Block block = store.get(height);
            log.info("[api] 查询块 height={} source=local found={}", height, block != null);
            return toResponse(block, "local-index", true);
        }
        BlockResult result = client.fetchBlock(height);
        log.info("[api] 查询块 height={} source=live node={} found={}",
                height, result.nodeId(), result.block() != null);
        return toResponse(result.block(), result.nodeId(), false);
    }

    @GetMapping("/nodes/health")
    public HealthDto health() {
        List<Object> nodes = registry.snapshot().stream()
                .map(s -> (Object) java.util.Map.of(
                        "nodeId", s.nodeId(),
                        "endpoint", s.endpoint(),
                        "status", s.status(),
                        "latestHeight", s.latestHeight(),
                        "consecutiveFailures", s.consecutiveFailures(),
                        "consecutiveSuccesses", s.consecutiveSuccesses(),
                        "lastReason", s.lastReason() == null ? "" : s.lastReason(),
                        "lastUpdatedEpochMs", s.lastUpdatedEpochMs()))
                .toList();
        HealthDto dto = new HealthDto(indexer.indexedHeight(), nodes);
        log.info("[api] 健康查询 indexedHeight={} nodes={}", indexer.indexedHeight(),
                registry.snapshot().stream().map(s -> s.nodeId() + "=" + s.status()
                        + "@" + s.latestHeight()
                        + (s.lastReason() == null ? "" : "/" + s.lastReason())).toList());
        return dto;
    }

    @GetMapping("/height/latest")
    public Object liveHeight() {
        LatestHeightResult result = client.fetchLatestHeight();
        log.info("[api] 实时集群高度 height={} 来源节点={}", result.height(), result.nodeId());
        return java.util.Map.of("height", result.height(), "sourceNode", result.nodeId());
    }

    private ResponseEntity<BlockDto> toResponse(Block block, String source, boolean indexed) {
        if (block == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new BlockDto(source, indexed, block.height(), block.hash(),
                block.parentHash(), block.timestamp(), block.payload()));
    }
}
