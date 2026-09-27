package com.github.xingjianluo.chainrpcnodeindexerjava.config;

/**
 * 单个上游 JSON-RPC 节点配置。
 *
 * @param id       节点唯一标识，用于说明数据来源
 * @param endpoint 节点 JSON-RPC 地址，例如 http://127.0.0.1:8080/sim/node-a
 * @param enabled  是否启用
 */
public record NodeProperties(String id, String endpoint, boolean enabled) {

    public NodeProperties {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("app.node[].id 不能为空");
        }
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("app.node[].endpoint 不能为空，节点: " + id);
        }
    }
}
