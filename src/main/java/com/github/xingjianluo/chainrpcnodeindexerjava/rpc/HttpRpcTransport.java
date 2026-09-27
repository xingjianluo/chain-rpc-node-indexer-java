package com.github.xingjianluo.chainrpcnodeindexerjava.rpc;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 JDK {@link HttpClient} 的 JSON-RPC 传输实现。
 * <p>
 * 失败原因划分在传输层只做两类：
 * <ul>
 *   <li>{@link com.github.xingjianluo.chainrpcnodeindexerjava.model.ChainErrorReason#NETWORK_UNREACHABLE}
 *       —— 连不上、超时、IO 中断（抛 {@link java.net.http.HttpConnectTimeoutException}、
 *       {@link HttpTimeoutException}、{@link IOException}）；</li>
 *   <li>{@link com.github.xingjianluo.chainrpcnodeindexerjava.model.ChainErrorReason#NODE_ERROR}
 *       —— 节点有响应但 HTTP 状态码非 2xx。</li>
 * </ul>
 * 响应体内容是否合法（BAD_RESPONSE）由上层 JSON 解析处判定。
 */
public class HttpRpcTransport implements RpcTransport {

    private static final Logger log = LoggerFactory.getLogger(HttpRpcTransport.class);

    private final HttpClient httpClient;

    public HttpRpcTransport() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    public HttpRpcTransport(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public String post(String id, String endpoint, String requestBody, long timeoutMs) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofMillis(Math.max(1, timeoutMs)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            // HttpConnectTimeoutException 是其子类：连接建立超时也算网络连不上
            log.warn("[transport] node={} endpoint={} 请求超时({}ms): {}",
                    id, endpoint, timeoutMs, e.toString());
            throw new RpcException(
                    com.github.xingjianluo.chainrpcnodeindexerjava.model.ChainErrorReason.NETWORK_UNREACHABLE,
                    "节点 " + id + " 请求超时: " + e.getMessage(), e);
        } catch (IOException e) {
            // ConnectException（Connection refused）、SocketException、HttpConnectTimeoutException(父类为 IOException)
            // 均归入“网络连不上”。
            log.warn("[transport] node={} endpoint={} 网络不可达: {}", id, endpoint, e.toString());
            throw new RpcException(
                    com.github.xingjianluo.chainrpcnodeindexerjava.model.ChainErrorReason.NETWORK_UNREACHABLE,
                    "节点 " + id + " 网络不可达: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RpcException(
                    com.github.xingjianluo.chainrpcnodeindexerjava.model.ChainErrorReason.NETWORK_UNREACHABLE,
                    "节点 " + id + " 请求被中断", e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("[transport] node={} 返回 HTTP {}: {}", id, response.statusCode(),
                    abbreviate(response.body()));
            throw new RpcException(
                    com.github.xingjianluo.chainrpcnodeindexerjava.model.ChainErrorReason.NODE_ERROR,
                    "节点 " + id + " 返回 HTTP " + response.statusCode());
        }
        return response.body();
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 120 ? s : s.substring(0, 120) + "...";
    }
}
