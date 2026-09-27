package com.github.xingjianluo.chainrpcnodeindexerjava.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.Block;
import com.github.xingjianluo.chainrpcnodeindexerjava.model.ChainErrorReason;

/**
 * {@link DefaultChainNode} 单测：高度/区块字段解析，坏字段归类 BAD_RESPONSE。
 */
class DefaultChainNodeTest {

    static class StubClient implements JsonRpcClient {
        JsonNode result;
        RpcException toThrow;
        String lastMethod;

        @Override
        public String id() {
            return "node-a";
        }

        @Override
        public String endpoint() {
            return "http://a";
        }

        @Override
        public JsonNode call(String method, Object params) {
            lastMethod = method;
            if (toThrow != null) {
                throw toThrow;
            }
            return result;
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String s) {
        try {
            return mapper.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void parsesDecimalAndHexHeight() {
        StubClient c = new StubClient();
        DefaultChainNode node = new DefaultChainNode(c);

        c.result = json("123");
        assertThat(node.fetchLatestHeight()).isEqualTo(123);

        c.result = json("\"0xa\"");
        assertThat(node.fetchLatestHeight()).isEqualTo(10);
    }

    @Test
    void parsesBlock() {
        StubClient c = new StubClient();
        c.result = json("{\"number\":7,\"hash\":\"0xh7\",\"parentHash\":\"0xh6\","
                + "\"timestamp\":1700,\"payload\":\"hello\"}");
        Block b = new DefaultChainNode(c).fetchBlock(7);
        assertThat(b.height()).isEqualTo(7);
        assertThat(b.hash()).isEqualTo("0xh7");
        assertThat(b.parentHash()).isEqualTo("0xh6");
        assertThat(b.timestamp()).isEqualTo(1700);
        assertThat(b.payload()).isEqualTo("hello");
    }

    @Test
    void missingBlockHashMappedToBadResponse() {
        StubClient c = new StubClient();
        c.result = json("{\"number\":7,\"parentHash\":\"0xh6\",\"timestamp\":1}");
        assertThatThrownBy(() -> new DefaultChainNode(c).fetchBlock(7))
                .isInstanceOf(RpcException.class)
                .satisfies(e -> assertThat(((RpcException) e).reason())
                        .isEqualTo(ChainErrorReason.BAD_RESPONSE));
    }

    @Test
    void nonNumericHeightMappedToBadResponse() {
        StubClient c = new StubClient();
        c.result = json("\"oops\"");
        assertThatThrownBy(() -> new DefaultChainNode(c).fetchLatestHeight())
                .isInstanceOf(RpcException.class)
                .satisfies(e -> assertThat(((RpcException) e).reason())
                        .isEqualTo(ChainErrorReason.BAD_RESPONSE));
    }

    @Test
    void nullBlockResultMeansHeightNotAvailable() {
        StubClient c = new StubClient();
        c.result = json("null");
        assertThat(new DefaultChainNode(c).fetchBlock(99)).isNull();
    }
}
