package com.github.xingjianluo.chainrpcnodeindexerjava.query;

import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.FailureReason;

/**
 * 所有候选节点都失败时抛出。
 */
public class AllNodesFailedException extends RuntimeException {

    private final FailureReason lastReason;

    public AllNodesFailedException(String message, FailureReason lastReason) {
        super(message);
        this.lastReason = lastReason;
    }

    public FailureReason lastReason() {
        return lastReason;
    }
}
