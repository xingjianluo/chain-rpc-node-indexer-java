package com.github.xingjianluo.chainrpcnodeindexerjava.rpc;

/**
 * 访问上游节点失败，带分类原因。
 */
public class RpcException extends RuntimeException {

    private final FailureReason reason;

    public RpcException(FailureReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RpcException(FailureReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public FailureReason reason() {
        return reason;
    }
}
