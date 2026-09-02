/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.web3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.trino.Session;
import io.trino.testing.DistributedQueryRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Map;

import static io.trino.testing.TestingSession.testSessionBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestBitcoinTables
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String BLOCK_HASH = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
    private static final String PREVIOUS_HASH = "1111111111111111111111111111111111111111111111111111111111111111";
    private static final String TRANSACTION_HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private HttpServer server;

    @BeforeEach
    public void setUp()
            throws IOException
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    public void tearDown()
    {
        server.stop(0);
    }

    @Test
    public void testExecutesNativeBitcoinTables()
            throws Exception
    {
        Session session = testSessionBuilder().setCatalog("web3").setSchema("bitcoin").build();
        try (DistributedQueryRunner queryRunner = DistributedQueryRunner.builder(session).setWorkerCount(1).build()) {
            queryRunner.installPlugin(new Web3Plugin());
            queryRunner.createCatalog("web3", Web3ConnectorFactory.CONNECTOR_NAME, Map.of(
                    "web3.bitcoin.rpc-url", endpoint(),
                    "web3.rpc.json-rpc-batch-enabled", "false"));

            assertThat(queryRunner.execute("SHOW TABLES FROM web3.bitcoin").getOnlyColumn())
                    .containsExactly("blocks", "inputs", "outputs", "transactions");
            assertThat(queryRunner.execute("SELECT height, hash, previous_block_hash, time, transaction_count FROM web3.bitcoin.blocks WHERE height = 100").getMaterializedRows())
                    .extracting(row -> row.getFields())
                    .containsExactly(
                            java.util.List.of(100L, BLOCK_HASH, PREVIOUS_HASH, 1_700_000_000L, 1L));
            assertThat(queryRunner.execute("SELECT txid, block_height, version, lock_time, input_count, output_count FROM web3.bitcoin.transactions WHERE block_height = 100").getMaterializedRows())
                    .extracting(row -> row.getFields())
                    .containsExactly(java.util.List.of(TRANSACTION_HASH, 100L, 2L, 0L, 2L, 2L));
            assertThat(queryRunner.execute("SELECT input_index, previous_txid, previous_vout, coinbase, sequence FROM web3.bitcoin.inputs WHERE block_height = 100 ORDER BY input_index").getMaterializedRows())
                    .extracting(row -> row.getFields())
                    .containsExactly(
                            Arrays.asList(0L, PREVIOUS_HASH, 1L, null, 4_294_967_295L),
                            Arrays.asList(1L, null, null, "03abcd", 4_294_967_295L));
            assertThat(queryRunner.execute("SELECT output_index, value_satoshis, script_pubkey_hex, address FROM web3.bitcoin.outputs WHERE block_height = 100 ORDER BY output_index").getMaterializedRows())
                    .extracting(row -> row.getFields())
                    .containsExactly(
                            java.util.List.of(0L, 123_456_789L, "0014abcd", "bc1qexample"),
                            Arrays.asList(1L, 1L, "6a01ff", null));
        }
    }

    @Test
    public void testRejectsUnboundedBitcoinScan()
            throws Exception
    {
        Session session = testSessionBuilder().setCatalog("web3").setSchema("bitcoin").build();
        try (DistributedQueryRunner queryRunner = DistributedQueryRunner.builder(session).setWorkerCount(1).build()) {
            queryRunner.installPlugin(new Web3Plugin());
            queryRunner.createCatalog("web3", Web3ConnectorFactory.CONNECTOR_NAME, Map.of("web3.bitcoin.rpc-url", endpoint()));

            assertThatThrownBy(() -> queryRunner.execute("SELECT * FROM web3.bitcoin.outputs"))
                    .hasStackTraceContaining("bitcoin.outputs requires a bounded block_height predicate");
        }
    }

    private String endpoint()
    {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange)
            throws IOException
    {
        JsonNode document = OBJECT_MAPPER.readTree(exchange.getRequestBody());
        boolean batch = document.isArray();
        ArrayNode responses = OBJECT_MAPPER.createArrayNode();
        Iterable<JsonNode> requests = batch ? document : java.util.List.of(document);
        for (JsonNode request : requests) {
            ObjectNode response = responses.addObject();
            response.put("jsonrpc", "2.0");
            response.put("id", request.path("id").asLong());
            response.set("result", result(request));
        }
        JsonNode output = batch ? responses : responses.get(0);
        byte[] body = OBJECT_MAPPER.writeValueAsBytes(output);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static JsonNode result(JsonNode request)
    {
        return switch (request.path("method").asText()) {
            case "getnetworkinfo" -> OBJECT_MAPPER.createObjectNode().put("subversion", "/Satoshi:29.3.0/");
            case "getblockhash" -> OBJECT_MAPPER.getNodeFactory().textNode(BLOCK_HASH);
            case "getblock" -> block(request.path("params").get(1).asInt());
            default -> throw new IllegalArgumentException("unexpected Bitcoin RPC method");
        };
    }

    private static ObjectNode block(int verbosity)
    {
        ObjectNode block = OBJECT_MAPPER.createObjectNode();
        block.put("hash", BLOCK_HASH);
        block.put("height", 100);
        block.put("previousblockhash", PREVIOUS_HASH);
        block.put("time", 1_700_000_000L);
        block.put("nTx", 1);
        if (verbosity == 1) {
            block.putArray("tx").add(TRANSACTION_HASH);
        }
        else {
            block.set("tx", OBJECT_MAPPER.createArrayNode().add(transaction()));
        }
        return block;
    }

    private static ObjectNode transaction()
    {
        ObjectNode transaction = OBJECT_MAPPER.createObjectNode();
        transaction.put("txid", TRANSACTION_HASH);
        transaction.put("version", 2);
        transaction.put("locktime", 0);
        ArrayNode inputs = transaction.putArray("vin");
        inputs.addObject().put("txid", PREVIOUS_HASH).put("vout", 1).put("sequence", 4_294_967_295L);
        inputs.addObject().put("coinbase", "03abcd").put("sequence", 4_294_967_295L);
        ArrayNode outputs = transaction.putArray("vout");
        outputs.addObject().put("n", 0).put("value", 1.23456789).putObject("scriptPubKey")
                .put("hex", "0014abcd").put("address", "bc1qexample");
        outputs.addObject().put("n", 1).put("value", 0.00000001).putObject("scriptPubKey")
                .put("hex", "6a01ff");
        return transaction;
    }
}
