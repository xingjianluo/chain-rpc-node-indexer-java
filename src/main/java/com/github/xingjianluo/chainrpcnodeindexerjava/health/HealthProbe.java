package com.github.xingjianluo.chainrpcnodeindexerjava.health;

import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.FailureReason;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.RpcException;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.UpstreamNode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 周期性主动探测所有已注册节点的最新高度。
 *
 * <p>作用：
 * <ul>
 *     <li>让“从不被选中的节点”也有机会暴露其滞后，及时标 LAGGING；</li>
 *     <li>让 UNAVAILABLE 节点在冷却到期后被探活，按连续成功规则恢复；</li>
 *     <li>探测同样走 {@link NodeHealthTracker} 记账，偶发一次失败不会判死。</li>
 * </ul>
 */
@Component
public class HealthProbe {

    private static final Logger log = LoggerFactory.getLogger(HealthProbe.class);

    private final List<UpstreamNode> nodes;
    private final NodeHealthTracker tracker;

    public HealthProbe(List<UpstreamNode> nodes, NodeHealthTracker tracker) {
        this.nodes = nodes;
        this.tracker = tracker;
    }

    @Scheduled(fixedDelayString = "${app.health.probe-interval-millis:2000}", initialDelay = 300)
    public void probeAll() {
        for (UpstreamNode node : nodes) {
            probeOne(node);
        }
    }

    /** 对单个节点做一次探活；返回是否成功。 */
    public boolean probeOne(UpstreamNode node) {
        try {
            long height = node.getLatestHeight();
            tracker.recordSuccess(node.name(), height);
            log.debug("探活成功 节点={} 高度={}", node.name(), height);
            return true;
        } catch (RpcException e) {
            tracker.recordFailure(node.name(), e.reason(), "探活失败: " + e.getMessage());
            log.warn("探活失败 节点={} 原因={} 详情={}", node.name(), e.reason(), e.getMessage());
            return false;
        } catch (RuntimeException e) {
            tracker.recordFailure(node.name(), FailureReason.BAD_RESPONSE,
                    "探活出现未预期异常: " + e);
            return false;
        }
    }
}
