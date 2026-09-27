package com.github.xingjianluo.chainrpcnodeindexerjava.api.dto;

import java.util.List;

/** 节点健康总览。 */
public record HealthDto(long indexedHeight, List<?> nodes) {
}
