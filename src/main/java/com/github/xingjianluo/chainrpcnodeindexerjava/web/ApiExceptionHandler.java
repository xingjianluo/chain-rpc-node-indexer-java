package com.github.xingjianluo.chainrpcnodeindexerjava.web;

import com.github.xingjianluo.chainrpcnodeindexerjava.query.AllNodesFailedException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 把“所有节点都失败”翻译成 503，错误体里带最后一个失败原因分类。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AllNodesFailedException.class)
    public ResponseEntity<Map<String, Object>> allNodesFailed(AllNodesFailedException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "error", "all_nodes_failed",
                "reason", e.lastReason() == null ? "UNKNOWN" : e.lastReason().name(),
                "message", e.getMessage()));
    }
}
