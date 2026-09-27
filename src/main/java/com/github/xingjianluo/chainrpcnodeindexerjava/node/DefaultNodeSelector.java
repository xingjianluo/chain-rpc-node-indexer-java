package com.github.xingjianluo.chainrpcnodeindexerjava.node;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.xingjianluo.chainrpcnodeindexerjava.model.NodeState;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.ChainNode;

/**
 * 选择规则：
 * <ol>
 *   <li>只从 {@link NodeHealthStatus#HEALTHY} 节点中选择，UNAVAILABLE 不会被选中；</li>
 *   <li>已知最新高度更高者优先（未知高度 -1 排最后）；</li>
 *   <li>高度相同时按节点 ID 字典序，保证选择结果确定、可在日志中断言。</li>
 * </ol>
 * 故障转移时调用方按 {@link #selectCandidates()} 顺序依次尝试。
 */
public class DefaultNodeSelector implements NodeSelector {

    private static final Logger log = LoggerFactory.getLogger(DefaultNodeSelector.class);

    private final HealthRegistry registry;
    private final Map<String, ChainNode> nodesById = new LinkedHashMap<>();

    public DefaultNodeSelector(HealthRegistry registry, List<ChainNode> nodes) {
        this.registry = registry;
        for (ChainNode node : nodes) {
            nodesById.put(node.id(), node);
        }
    }

    /** 全部节点（含不可用节点），按 ID 排序，供恢复探测使用。 */
    public List<ChainNode> allNodes() {
        return nodesById.values().stream()
                .sorted(Comparator.comparing(ChainNode::id))
                .toList();
    }

    @Override
    public List<ChainNode> selectCandidates() {
        List<ChainNode> candidates = registry.healthy().stream()
                .sorted(Comparator
                        .comparingLong(NodeState::latestHeight).reversed()
                        .thenComparing(NodeState::nodeId))
                .map(s -> nodesById.get(s.nodeId()))
                .filter(n -> n != null)
                .toList();
        if (log.isDebugEnabled()) {
            log.debug("[selector] 候选节点顺序={}",
                    candidates.stream().map(ChainNode::id).toList());
        }
        return candidates;
    }

    @Override
    public ChainNode first() {
        List<ChainNode> candidates = selectCandidates();
        return candidates.isEmpty() ? null : candidates.get(0);
    }
}
