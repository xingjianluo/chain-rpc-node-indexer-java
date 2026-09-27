package com.github.xingjianluo.chainrpcnodeindexerjava.sim;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 模拟相关配置（前缀 app.sim）。
 *
 * @param enabled            是否装配模拟节点与管理接口
 * @param initialBlockCount  启动时预生成的区块数（含创世块）
 */
@ConfigurationProperties(prefix = "app.sim")
public record SimProperties(@DefaultValue("true") boolean enabled,
                            @DefaultValue("3") int initialBlockCount) {
}
