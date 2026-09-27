package com.github.xingjianluo.chainrpcnodeindexerjava.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.chainrpcnodeindexerjava.health.NodeHealthTracker;
import com.github.xingjianluo.chainrpcnodeindexerjava.index.ChainIndexer;
import com.github.xingjianluo.chainrpcnodeindexerjava.query.ChainQueryService;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.HttpJsonRpcNode;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.SimulatedChain;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.SimulatedUpstreamNode;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.UpstreamNode;
import com.github.xingjianluo.chainrpcnodeindexerjava.store.FileBlockStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Bean 装配。
 *
 * <p>未显式配置 {@code app.upstreams} 时默认起 3 个本地模拟节点（共享一条内存链），
 * 其中第三个预置 3 个高度的滞后，方便直接观察选主与 LAGGING 标记。
 * 也支持 {@code type=http} 的真实 JSON-RPC 节点（需自行提供服务地址）。
 */
@Configuration
public class AppConfig {

    @Bean(destroyMethod = "")
    public SimulatedChain simulatedChain(AppProperties props) {
        return new SimulatedChain(props.getSimulated().getChainHeight());
    }

    @Bean
    public List<UpstreamNode> upstreamNodes(AppProperties props, SimulatedChain chain) {
        List<UpstreamNode> result = new ArrayList<>();
        List<AppProperties.Upstream> configs = props.getUpstreams();
        if (configs.isEmpty()) {
            result.add(new SimulatedUpstreamNode("node-a", chain, 1000));
            result.add(new SimulatedUpstreamNode("node-b", chain, 1000));
            SimulatedUpstreamNode lagging = new SimulatedUpstreamNode("node-c", chain, 1000);
            lagging.setLagBy(3);
            result.add(lagging);
            return result;
        }
        for (AppProperties.Upstream u : configs) {
            if ("http".equalsIgnoreCase(u.getType())) {
                if (u.getUrl() == null || u.getUrl().isBlank()) {
                    throw new IllegalArgumentException("http 类型上游必须配置 url: " + u.getName());
                }
                result.add(new HttpJsonRpcNode(u.getName(), u.getUrl(), u.getTimeoutMillis()));
            } else {
                SimulatedUpstreamNode node =
                        new SimulatedUpstreamNode(u.getName(), chain, u.getTimeoutMillis());
                node.setLagBy(u.getLagBy());
                result.add(node);
            }
        }
        return result;
    }

    @Bean
    public NodeHealthTracker nodeHealthTracker(AppProperties props) {
        AppProperties.Health h = props.getHealth();
        return new NodeHealthTracker(h.getFailThreshold(), h.getRecoverSuccesses(),
                h.getProbeCooldownMillis(), h.getLagThreshold());
    }

    @Bean
    public ChainQueryService chainQueryService(List<UpstreamNode> nodes, NodeHealthTracker tracker) {
        return new ChainQueryService(nodes, tracker);
    }

    @Bean
    public FileBlockStore fileBlockStore(AppProperties props, ObjectMapper objectMapper) {
        Path file = Path.of(props.getDataDir(), "blocks.json");
        return new FileBlockStore(file, objectMapper);
    }

    @Bean
    public ChainIndexer chainIndexer(ChainQueryService queryService, FileBlockStore store,
                                     AppProperties props) {
        return new ChainIndexer(queryService, store, props.getIndexer().getMaxBlocksPerTick());
    }
}
