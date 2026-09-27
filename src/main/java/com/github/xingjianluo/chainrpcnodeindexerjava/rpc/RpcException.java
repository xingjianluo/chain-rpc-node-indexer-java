package com.github.xingjianluo.chainrpcnodeindexerjava.rpc;

import com.github.xingjianluo.chainrpcnodeindexerjava.model.ChainErrorReason;

/**
 * 调用上游节点失败，携带可区分的失败原因分类。
 */
public class RpcException extends RuntimeException {

    private final ChainErrorReason reason;

    public RpcException(ChainErrorReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RpcException(ChainErrorReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public ChainErrorReason reason() {
        return reason;
    }
}
