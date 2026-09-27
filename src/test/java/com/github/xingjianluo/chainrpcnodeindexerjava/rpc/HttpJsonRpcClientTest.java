package com.github.xingjianluo.chainrpcnodeindexerjava.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.ChainErrorReason;

/**
 * {@link HttpJsonRpcClient} 单测：区分 NODE_ERROR（JSON-RPC error）
 * 与 BAD_RESPONSE（坏 JSON / 缺字段）。网络类异常由 transport 抛出，这里直接验证透传。
 */
class HttpJsonRpcClientTest {

    /** 返回固定字符串或直接抛异常的假传输。 */
    static class StubTransport implements RpcTransport {
        String response;
        RpcException toThrow;
        String capturedBody;

        @Override
        public String post(String id, String endpoint, String requestBody, long timeoutMs) {
            capturedBody = requestBody;
            if (toThrow != null) {
                throw toThrow;
            }
            return response;
        }
    }

    private HttpJsonRpcClient client(StubTransport transport) {
        return new HttpJsonRpcClient("node-a", "http://a", transport, 1000, new ObjectMapper());
    }

    @Test
    void parsesValidResult() {
        StubTransport t = new StubTransport();
        t.response = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":42}";
        JsonNode result = client(t).call("chain_blockNumber", null);
        assertThat(result.asInt()).isEqualTo(42);
        // 请求体是合法 JSON-RPC 信封
        assertThat(t.capturedBody).contains("\"method\":\"chain_blockNumber\"")
                .contains("\"jsonrpc\":\"2.0\"");
    }

    @Test
    void rpcErrorMappedToNodeError() {
        StubTransport t = new StubTransport();
        t.response = "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"boom\"}}";
        assertThatThrownBy(() -> client(t).call("chain_blockNumber", null))
                .isInstanceOf(RpcException.class)
                .satisfies(e -> assertThat(((RpcException) e).reason())
                        .isEqualTo(ChainErrorReason.NODE_ERROR));
    }

    @Test
    void malformedJsonMappedToBadResponse() {
        StubTransport t = new StubTransport();
        t.response = "{not-json";
        assertThatThrownBy(() -> client(t).call("chain_blockNumber", null))
                .isInstanceOf(RpcException.class)
                .satisfies(e -> assertThat(((RpcException) e).reason())
                        .isEqualTo(ChainErrorReason.BAD_RESPONSE));
    }

    @Test
    void missingResultMappedToBadResponse() {
        StubTransport t = new StubTransport();
        t.response = "{\"jsonrpc\":\"2.0\",\"id\":1}";
        assertThatThrownBy(() -> client(t).call("chain_blockNumber", null))
                .isInstanceOf(RpcException.class)
                .satisfies(e -> assertThat(((RpcException) e).reason())
                        .isEqualTo(ChainErrorReason.BAD_RESPONSE));
    }

    @Test
    void transportNetworkFailurePassedThroughWithReason() {
        StubTransport t = new StubTransport();
        t.toThrow = new RpcException(ChainErrorReason.NETWORK_UNREACHABLE, "连接被拒");
        assertThatThrownBy(() -> client(t).call("chain_blockNumber", null))
                .isInstanceOf(RpcException.class)
                .satisfies(e -> assertThat(((RpcException) e).reason())
                        .isEqualTo(ChainErrorReason.NETWORK_UNREACHABLE));
    }
}
