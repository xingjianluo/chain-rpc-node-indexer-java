package com.github.xingjianluo.chainrpcnodeindexerjava.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 应用配置（前缀 app）。
 *
 * @param nodes              上游节点列表
 * @param dataDir            区块持久化目录（服务重启后从这里恢复）
 * @param requestTimeoutMs   单次 RPC 请求超时
 * @param failureThreshold   连续失败多少次判为不可用
 * @param recoverySuccesses  不可用节点需连续成功多少次恢复
 * @param lagThresholdBlocks 落后集群最高高度多少块视为滞后
 * @param pollIntervalMs     索引器轮询间隔
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(List<NodeProperties> nodes,
                            @DefaultValue("./data/blocks") String dataDir,
                            @DefaultValue("1000") long requestTimeoutMs,
                            @DefaultValue("3") int failureThreshold,
                            @DefaultValue("2") int recoverySuccesses,
                            @DefaultValue("5") long lagThresholdBlocks,
                            @DefaultValue("1000") long pollIntervalMs) {

    public AppProperties {
        if (nodes == null) {
            nodes = List.of();
        }
    }
}
