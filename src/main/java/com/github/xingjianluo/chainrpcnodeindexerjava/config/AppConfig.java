package com.github.xingjianluo.chainrpcnodeindexerjava.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.xingjianluo.chainrpcnodeindexerjava.indexer.BlockIndexer;
import com.github.xingjianluo.chainrpcnodeindexerjava.indexer.DefaultBlockIndexer;
import com.github.xingjianluo.chainrpcnodeindexerjava.indexer.IndexerScheduler;
import com.github.xingjianluo.chainrpcnodeindexerjava.node.DefaultFailoverChainClient;
import com.github.xingjianluo.chainrpcnodeindexerjava.node.DefaultHealthRegistry;
import com.github.xingjianluo.chainrpcnodeindexerjava.node.DefaultNodeSelector;
import com.github.xingjianluo.chainrpcnodeindexerjava.node.FailoverChainClient;
import com.github.xingjianluo.chainrpcnodeindexerjava.node.HealthRegistry;
import com.github.xingjianluo.chainrpcnodeindexerjava.node.NodeSelector;
import com.github.xingjianluo.chainrpcnodeindexerjava.node.RecoveryProbe;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.ChainNode;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.DefaultChainNode;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.HttpJsonRpcClient;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.HttpRpcTransport;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.JsonRpcClient;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.RpcTransport;
import com.github.xingjianluo.chainrpcnodeindexerjava.storage.FileBlockStore;

/**
 * 主装配。
 */
@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    @Bean
    public RpcTransport rpcTransport() {
        return new HttpRpcTransport();
    }

    @Bean
    public List<ChainNode> chainNodes(AppProperties props, RpcTransport transport) {
        List<ChainNode> nodes = new ArrayList<>();
        for (NodeProperties p : props.nodes()) {
            if (!p.enabled()) {
                log.info("[config] 节点 {} 已禁用，跳过", p.id());
                continue;
            }
            JsonRpcClient rpc = new HttpJsonRpcClient(
                    p.id(), p.endpoint(), transport, props.requestTimeoutMs());
            nodes.add(new DefaultChainNode(rpc));
        }
        if (nodes.isEmpty()) {
            log.warn("[config] 没有配置任何启用的上游节点");
        }
        return List.copyOf(nodes);
    }

    @Bean
    public HealthRegistry healthRegistry(AppProperties props) {
        return new DefaultHealthRegistry(props.nodes(),
                props.failureThreshold(), props.recoverySuccesses());
    }

    @Bean
    public NodeSelector nodeSelector(HealthRegistry registry, List<ChainNode> nodes) {
        return new DefaultNodeSelector(registry, nodes);
    }

    @Bean
    public FailoverChainClient failoverChainClient(NodeSelector selector,
                                                   HealthRegistry registry,
                                                   AppProperties props) {
        return new DefaultFailoverChainClient(selector, registry, props.lagThresholdBlocks());
    }

    @Bean
    public FileBlockStore fileBlockStore(AppProperties props) {
        return new FileBlockStore(Path.of(props.dataDir()));
    }

    @Bean
    public BlockIndexer blockIndexer(FileBlockStore store, FailoverChainClient client) {
        return new DefaultBlockIndexer(store, client);
    }

    @Bean
    public RecoveryProbe recoveryProbe(HealthRegistry registry, NodeSelector selector) {
        return new RecoveryProbe(registry, selector);
    }

    @Bean
    public IndexerScheduler indexerScheduler(BlockIndexer indexer, RecoveryProbe probe) {
        return new IndexerScheduler(indexer, probe);
    }
}
