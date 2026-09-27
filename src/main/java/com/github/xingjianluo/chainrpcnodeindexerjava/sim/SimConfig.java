package com.github.xingjianluo.chainrpcnodeindexerjava.sim;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 模拟组件装配。app.sim.enabled=false 时整个模拟层不加载，
 * 此时上游节点可指向任意外部 JSON-RPC 节点。
 */
@Configuration
@EnableConfigurationProperties(SimProperties.class)
@ConditionalOnProperty(prefix = "app.sim", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class SimConfig {

    private static final Logger log = LoggerFactory.getLogger(SimConfig.class);

    @Bean
    public SimulatedChain simulatedChain(SimProperties properties) {
        SimulatedChain chain = new SimulatedChain();
        chain.initialize(properties.initialBlockCount());
        log.info("[sim] 模拟链初始化完成，初始块数={}，链尖高度={}",
                properties.initialBlockCount(), chain.tipHeight());
        return chain;
    }

    @Bean
    public SimBehaviorRegistry simBehaviorRegistry() {
        return new SimBehaviorRegistry();
    }

    @Bean
    public SimNodeController simNodeController(SimulatedChain chain,
                                               SimBehaviorRegistry registry) {
        return new SimNodeController(chain, registry);
    }

    @Bean
    public SimAdminController simAdminController(SimulatedChain chain,
                                                 SimBehaviorRegistry registry) {
        return new SimAdminController(chain, registry);
    }
}
