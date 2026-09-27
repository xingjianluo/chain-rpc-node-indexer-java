package com.github.xingjianluo.chainrpcnodeindexerjava.indexer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import com.github.xingjianluo.chainrpcnodeindexerjava.node.RecoveryProbe;

/**
 * 定时任务：
 * <ul>
 *   <li>每 {@code app.poll-interval-ms} 驱动一轮索引追赶（内含重组检测）；</li>
 *   <li>同一节奏下先对 UNAVAILABLE 节点做恢复探测（连续多次成功才恢复）。</li>
 * </ul>
 * 两者在同一调度方法内串行执行，避免与索引抓取竞争。
 */
public class IndexerScheduler {

    private static final Logger log = LoggerFactory.getLogger(IndexerScheduler.class);

    private final BlockIndexer indexer;
    private final RecoveryProbe recoveryProbe;

    public IndexerScheduler(BlockIndexer indexer, RecoveryProbe recoveryProbe) {
        this.indexer = indexer;
        this.recoveryProbe = recoveryProbe;
    }

    @Scheduled(fixedDelayString = "${app.poll-interval-ms:1000}")
    public void tick() {
        try {
            if (recoveryProbe != null) {
                recoveryProbe.probeOnce();
            }
            indexer.catchUpOnce();
        } catch (RuntimeException e) {
            // 调度任务绝不能因异常静默死亡
            log.warn("[scheduler] 本轮调度异常（不影响后续调度）: {}", e.toString(), e);
        }
    }
}
