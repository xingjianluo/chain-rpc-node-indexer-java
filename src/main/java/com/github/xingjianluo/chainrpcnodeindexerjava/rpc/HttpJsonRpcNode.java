package com.github.xingjianluo.chainrpcnodeindexerjava.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.xingjianluo.chainrpcnodeindexerjava.chain.Block;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;

/**
 * 基于 JDK {@link HttpClient} 的真实 HTTP JSON-RPC 节点。
 *
 * <p>失败分类：
 * <ul>
 *     <li>连接被拒/读超时 → {@link FailureReason#CONNECTIVITY}；</li>
 *     <li>HTTP 非 2xx 或 JSON-RPC error → {@link FailureReason#RPC_ERROR}；</li>
 *     <li>响应体无法解析 → {@link FailureReason#BAD_RESPONSE}。</li>
 * </ul>
 */
public class HttpJsonRpcNode implements UpstreamNode {

    private final String name;
    private final String url;
    private final long timeoutMillis;
    private final HttpClient client;

    public HttpJsonRpcNode(String name, String url, long timeoutMillis) {
        this.name = name;
        this.url = url;
        this.timeoutMillis = timeoutMillis;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMillis))
                .build();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public long timeoutMillis() {
        return timeoutMillis;
    }

    @Override
    public long getLatestHeight() {
        JsonNode result = invoke("chain_getLatestHeight", List.of());
        return JsonRpcCodec.toHeight(result.get("height"));
    }

    @Override
    public Block getBlockByHeight(long height) {
        JsonNode result = invoke("chain_getBlockByHeight", List.of(height));
        return JsonRpcCodec.toBlock(result, height);
    }

    private JsonNode invoke(String method, List<?> params) {
        String body = JsonRpcCodec.buildRequest(method, params);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMillis))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        final HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new RpcException(FailureReason.CONNECTIVITY,
                    "[" + name + "] 请求超时（" + timeoutMillis + "ms）: " + method, e);
        } catch (java.net.ConnectException e) {
            throw new RpcException(FailureReason.CONNECTIVITY,
                    "[" + name + "] 连接被拒绝: " + url, e);
        } catch (Exception e) {
            throw new RpcException(FailureReason.CONNECTIVITY,
                    "[" + name + "] 网络访问失败: " + e.getMessage(), e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new RpcException(FailureReason.RPC_ERROR,
                    "[" + name + "] HTTP " + response.statusCode());
        }
        return JsonRpcCodec.extractResult(response.body());
    }
}
