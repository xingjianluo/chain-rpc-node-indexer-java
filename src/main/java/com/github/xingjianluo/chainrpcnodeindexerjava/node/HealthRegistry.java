package com.github.xingjianluo.chainrpcnodeindexerjava.node;

import java.util.List;

import com.github.xingjianluo.chainrpcnodeindexerjava.model.ChainErrorReason;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.NodeHealthStatus;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.NodeState;

/**
 * 维护每个上游节点的健康状态、连续成败计数与最新高度。
 * 实现需线程安全。
 */
public interface HealthRegistry {

    /** 登记一次失败，返回该节点最新状态。 */
    NodeState recordFailure(String nodeId, ChainErrorReason reason);

    /**
     * 登记一次成功并更新最新高度。
     * UNAVAILABLE 节点单次成功不会立刻恢复，需连续成功达到阈值。
     */
    NodeState recordSuccess(String nodeId, long latestHeight);

    /** 仅更新高度信息（不改变成败计数），返回最新状态。 */
    NodeState updateHeight(String nodeId, long latestHeight);

    /** 查询单个节点状态，未登记返回 null。 */
    NodeState stateOf(String nodeId);

    /** 全部节点状态快照。 */
    List<NodeState> snapshot();

    /** 健康的节点快照列表。 */
    List<NodeState> healthy();

    /** 判断节点当前是否可用（HEALTHY）。 */
    boolean isAvailable(String nodeId);

    /** 直接置状态（测试/管理接口用）。 */
    NodeState mark(String nodeId, NodeHealthStatus status, ChainErrorReason reason);

    /** 全部节点重置为 HEALTHY、清空计数与已记录原因（测试隔离用）。 */
    void reset();
}
