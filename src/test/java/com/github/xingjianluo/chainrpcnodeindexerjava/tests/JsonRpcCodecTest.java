package com.github.xingjianluo.chainrpcnodeindexerjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.chainrpcnodeindexerjava.chain.Block;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.FailureReason;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.JsonRpcCodec;
import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.RpcException;
import org.junit.jupiter.api.Test;

class JsonRpcCodecTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesValidHeightAndBlock() throws Exception {
        JsonNode result = JsonRpcCodec.extractResult(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"height\":7}}");
        assertEquals(7L, JsonRpcCodec.toHeight(result.get("height")));

        String blockResp = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{"
                + "\"height\":7,\"hash\":\"0xabc\",\"parentHash\":\"0xdef\","
                + "\"timestamp\":123,\"payload\":\"x\"}}";
        Block block = JsonRpcCodec.toBlock(JsonRpcCodec.extractResult(blockResp), 7);
        assertEquals("0xabc", block.hash());
        assertEquals("0xdef", block.parentHash());
    }

    @Test
    void rpcErrorIsClassifiedAsRpcError() {
        String resp = "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"boom\"}}";
        RpcException e = assertThrows(RpcException.class, () -> JsonRpcCodec.extractResult(resp));
        assertEquals(FailureReason.RPC_ERROR, e.reason());
    }

    @Test
    void malformedJsonIsBadResponse() {
        RpcException e = assertThrows(RpcException.class,
                () -> JsonRpcCodec.extractResult("not-json-at-all"));
        assertEquals(FailureReason.BAD_RESPONSE, e.reason());
    }

    @Test
    void missingFieldsAndHeightMismatchAreBadResponse() throws Exception {
        String missingHash = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{"
                + "\"height\":1,\"parentHash\":\"0xp\",\"timestamp\":1}}";
        RpcException e1 = assertThrows(RpcException.class,
                () -> JsonRpcCodec.toBlock(JsonRpcCodec.extractResult(missingHash), 1));
        assertEquals(FailureReason.BAD_RESPONSE, e1.reason());

        String mismatch = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{"
                + "\"height\":9,\"hash\":\"0xh\",\"parentHash\":\"0xp\",\"timestamp\":1}}";
        RpcException e2 = assertThrows(RpcException.class,
                () -> JsonRpcCodec.toBlock(JsonRpcCodec.extractResult(mismatch), 1));
        assertEquals(FailureReason.BAD_RESPONSE, e2.reason());
    }

    @Test
    void buildsWellFormedRequest() throws Exception {
        String raw = JsonRpcCodec.buildRequest("chain_getBlockByHeight", java.util.List.of(5L));
        JsonNode node = mapper.readTree(raw);
        assertEquals("2.0", node.path("jsonrpc").asText());
        assertEquals("chain_getBlockByHeight", node.path("method").asText());
        assertEquals(5, node.path("params").get(0).asInt());
    }
}
