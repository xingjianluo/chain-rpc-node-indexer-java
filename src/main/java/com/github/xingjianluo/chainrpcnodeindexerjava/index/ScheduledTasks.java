package com.github.xingjianluo.chainrpcnodeindexerjava.index;

import com.github.xingjianluo.chainrpcnodeindexerjava.config.AppProperties;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.SimulatedChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 后台定时任务：模拟链周期出块 + 索引器周期同步。
 *
 * <p>固定延迟（fixedDelay）保证同一任务不会重入；索引器 tick 本身也加锁，
 * 手动触发或测试直接调用同样安全。
 */
@Component
public class ScheduledTasks {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTasks.class);

    private final SimulatedChain chain;
    private final ChainIndexer indexer;
    private final AppProperties props;

    public ScheduledTasks(SimulatedChain chain, ChainIndexer indexer, AppProperties props) {
        this.chain = chain;
        this.indexer = indexer;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${app.simulated.mine-interval-millis:3000}",
            initialDelayString = "${app.simulated.mine-interval-millis:3000}")
    public void mineBlock() {
        chain.mine(1);
        log.debug("模拟链出块，当前高度 {}", chain.height());
    }

    @Scheduled(fixedDelayString = "${app.indexer.tick-millis:500}", initialDelay = 200)
    public void syncTick() {
        if (!props.getIndexer().isEnabled()) {
            return;
        }
        indexer.tick();
    }
}
