package com.github.xingjianluo.chainrpcnodeindexerjava.node;

import com.github.xingjianluo.chainrpcnodeindexerjava.model.BlockResult;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.LatestHeightResult;

/**
 * 对外屏蔽多节点细节：自动故障转移、滞后剔除，并说明数据来自哪个节点。
 */
public interface FailoverChainClient {

    /**
     * 查询集群最高高度（多节点交叉验证，明显落后的节点不计入）。
     * 全部不可用时抛出 NoHealthyNodeException。
     */
    LatestHeightResult fetchLatestHeight();

    /** 按高度取区块，节点失败自动切换。 */
    BlockResult fetchBlock(long height);

    /** 取最新区块。 */
    BlockResult fetchLatestBlock();
}
