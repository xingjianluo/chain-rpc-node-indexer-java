package com.github.xingjianluo.chainrpcnodeindexerjava.store;

import com.github.xingjianluo.chainrpcnodeindexerjava.index.ChainSnapshot;

/**
 * 区块持久化：重启后可恢复，提交必须原子（对外不暴露半写状态）。
 */
public interface BlockStore {

    ChainSnapshot load();

    void commit(ChainSnapshot snapshot);
}
