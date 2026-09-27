package com.github.xingjianluo.chainrpcnodeindexerjava.api.dto;

/** 统一错误响应。 */
public record ErrorDto(String error, String reason, String message) {
}
