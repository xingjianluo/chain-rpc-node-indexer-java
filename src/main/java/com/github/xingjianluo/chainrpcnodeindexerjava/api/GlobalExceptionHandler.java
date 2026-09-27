package com.github.xingjianluo.chainrpcnodeindexerjava.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.github.xingjianluo.chainrpcnodeindexerjava.api.dto.ErrorDto;
import com.github.xingjianluo.chainrpcnodeindexerjava.node.NoHealthyNodeException;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.RpcException;
import com.github.xingjianluo.chainrpcnodeindexerjava.storage.StorageException;

/**
 * 统一错误映射：
 * <ul>
 *   <li>无健康节点 -> 503 NO_HEALTHY_NODE；</li>
 *   <li>上游 RPC 失败 -> 502 UPSTREAM_RPC_ERROR，reason 为四分类之一；</li>
 *   <li>存储异常 -> 500 STORAGE_ERROR；</li>
 *   <li>参数非法 -> 400 BAD_REQUEST。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NoHealthyNodeException.class)
    public ResponseEntity<ErrorDto> noHealthy(NoHealthyNodeException e) {
        log.warn("[api] 无可用节点: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorDto("NO_HEALTHY_NODE", null, e.getMessage()));
    }

    @ExceptionHandler(RpcException.class)
    public ResponseEntity<ErrorDto> rpc(RpcException e) {
        log.warn("[api] 上游 RPC 失败 reason={}: {}", e.reason(), e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorDto("UPSTREAM_RPC_ERROR", e.reason().name(), e.getMessage()));
    }

    @ExceptionHandler(StorageException.class)
    public ResponseEntity<ErrorDto> storage(StorageException e) {
        log.error("[api] 存储异常: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorDto("STORAGE_ERROR", null, e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorDto> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(new ErrorDto("BAD_REQUEST", null, e.getMessage()));
    }
}
