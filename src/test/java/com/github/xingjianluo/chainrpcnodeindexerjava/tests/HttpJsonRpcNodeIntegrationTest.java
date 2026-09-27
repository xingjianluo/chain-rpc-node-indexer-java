package com.github.xingjianluo.chainrpcnodeindexerjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.xingjianluo.chainrpcnodeindexerjava.chain.Block;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.FailureReason;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.HttpJsonRpcNode;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.RpcException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用 JDK 内置 HttpServer 模拟真实 HTTP JSON-RPC 上游，验证 {@link HttpJsonRpcNode}
 * 的网络层错误分类。不依赖任何外部服务。
 */
class HttpJsonRpcNodeIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(HttpJsonRpcNodeIntegrationTest.class);

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startWith(String responseBody, int statusCode) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @Test
    void parsesValidResponseOverHttp() throws IOException {
        String url = startWith("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"height\":42}}", 200);
        HttpJsonRpcNode node = new HttpJsonRpcNode("http-ok", url, 2000);
        assertEquals(42L, node.getLatestHeight());

        String block = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"height\":5,"
                + "\"hash\":\"0xh\",\"parentHash\":\"0xp\",\"timestamp\":1,\"payload\":\"d\"}}";
        String url2 = startWith(block, 200);
        HttpJsonRpcNode node2 = new HttpJsonRpcNode("http-block", url2, 2000);
        Block b = node2.getBlockByHeight(5);
        assertEquals("0xh", b.hash());
        log.info("[HTTP正常] 经真实 HTTP 拿到高度=42 与区块 hash=0xh");
    }

    @Test
    void connectionRefusedIsConnectivity() {
        // 1 端口几乎不可能在监听
        HttpJsonRpcNode node = new HttpJsonRpcNode("dead", "http://127.0.0.1:1/rpc", 500);
        RpcException e = assertThrows(RpcException.class, node::getLatestHeight);
        assertEquals(FailureReason.CONNECTIVITY, e.reason());
        log.info("[HTTP连不上] 原因分类=CONNECTIVITY");
    }

    @Test
    void http500AndJsonRpcErrorAreRpcError() throws IOException {
        String url = startWith("internal error", 500);
        HttpJsonRpcNode node = new HttpJsonRpcNode("http-500", url, 2000);
        RpcException e1 = assertThrows(RpcException.class, node::getLatestHeight);
        assertEquals(FailureReason.RPC_ERROR, e1.reason());

        String url2 = startWith(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"bad\"}}", 200);
        HttpJsonRpcNode node2 = new HttpJsonRpcNode("http-err", url2, 2000);
        RpcException e2 = assertThrows(RpcException.class, node2::getLatestHeight);
        assertEquals(FailureReason.RPC_ERROR, e2.reason());
        log.info("[HTTP报错] HTTP500/JSON-RPC error 均分类=RPC_ERROR");
    }

    @Test
    void malformedHttpBodyIsBadResponse() throws IOException {
        String url = startWith("<<<not json>>>", 200);
        HttpJsonRpcNode node = new HttpJsonRpcNode("http-bad", url, 2000);
        RpcException e = assertThrows(RpcException.class, node::getLatestHeight);
        assertEquals(FailureReason.BAD_RESPONSE, e.reason());
        log.info("[HTTP坏响应] 非 JSON 内容分类=BAD_RESPONSE");
    }
}
