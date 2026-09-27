package com.github.xingjianluo.chainrpcnodeindexerjava.storage;

/** 持久化层异常（IO 失败、数据损坏等）。 */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
