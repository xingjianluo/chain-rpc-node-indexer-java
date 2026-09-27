package com.github.xingjianluo.chainrpcnodeindexerjava.node;

/**
 * 所有上游节点都不可用时抛出。
 */
public class NoHealthyNodeException extends RuntimeException {

    public NoHealthyNodeException(String message) {
        super(message);
    }
}
