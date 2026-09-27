package com.github.xingjianluo.chainrpcnodeindexerjava.node;

import java.util.List;

import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.ChainNode;

/**
 * 在健康节点中选择请求目标，并在失败时提供下一个候选。
 */
public interface NodeSelector {

    /** 按优先级返回健康节点（首选在前）。 */
    List<ChainNode> selectCandidates();

    /** 首选健康节点，没有可用节点时返回 null。 */
    ChainNode first();

    /** 全部已登记节点（含不可用节点），供恢复探测使用。 */
    List<ChainNode> allNodes();
}
