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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.trino.testing.TestingSession.testSessionBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestEthereumCache
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String BLOCK_HASH = hash('a');
    private static final String FIRST_TRANSACTION_HASH = hash('c');
    private static final String SECOND_TRANSACTION_HASH = hash('d');

    private final AtomicInteger blockDataRequests = new AtomicInteger();
    private final AtomicInteger transactionDataRequests = new AtomicInteger();
    private HttpServer server;

    @BeforeEach
    public void setUp()
            throws IOException
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handleRequest);
        server.start();
    }

    @AfterEach
    public void tearDown()
    {
        server.stop(0);
    }

    @Test
    public void testFinalizedBlockAndTransactionHashQueriesUseWorkerCache()
            throws Exception
    {
        Session session = testSessionBuilder().setCatalog("web3").setSchema("ethereum").build();
        try (DistributedQueryRunner queryRunner = DistributedQueryRunner.builder(session).setWorkerCount(1).build()) {
            queryRunner.installPlugin(new Web3Plugin());
            queryRunner.createCatalog("web3", Web3ConnectorFactory.CONNECTOR_NAME, Map.of(
                    "web3.ethereum.rpc-url", "http://127.0.0.1:" + server.getAddress().getPort(),
                    "web3.cache.enabled", "true",
                    "web3.cache.maximum-size", "1MB",
                    "web3.cache.maximum-entry-size", "64kB"));

            String blockSql = "SELECT block_hash FROM web3.ethereum.blocks WHERE block_number = 10";
            for (int index = 0; index < 4; index++) {
                assertThat(queryRunner.execute(blockSql).getOnlyColumn()).containsExactly(BLOCK_HASH);
            }
            assertThat(blockDataRequests.get()).isBetween(1, 2);

            String equalitySql = "SELECT hash, block_number FROM web3.ethereum.transactions WHERE hash = '" + FIRST_TRANSACTION_HASH + "'";
            for (int index = 0; index < 4; index++) {
                assertThat(queryRunner.execute(equalitySql).getMaterializedRows())
                        .extracting(row -> row.getField(0), row -> row.getField(1))
                        .containsExactly(org.assertj.core.groups.Tuple.tuple(FIRST_TRANSACTION_HASH, 10L));
            }
            assertThat(transactionDataRequests.get()).isBetween(1, 2);

            String combinedPredicateSql = equalitySql + " AND block_number = 11";
            assertThat(queryRunner.execute(combinedPredicateSql).getMaterializedRows()).isEmpty();

            String inSql = "SELECT hash FROM web3.ethereum.transactions WHERE hash IN ('" + FIRST_TRANSACTION_HASH + "', '" + SECOND_TRANSACTION_HASH + "')";
            for (int index = 0; index < 4; index++) {
                assertThat(queryRunner.execute(inSql).getOnlyColumn())
                        .containsExactlyInAnyOrder(FIRST_TRANSACTION_HASH, SECOND_TRANSACTION_HASH);
            }
            assertThat(transactionDataRequests.get()).isBetween(2, 4);

            String uppercaseHash = "0x" + FIRST_TRANSACTION_HASH.substring(2).toUpperCase(java.util.Locale.ENGLISH);
            assertThat(queryRunner.execute("SELECT hash FROM web3.ethereum.transactions WHERE hash = '" + uppercaseHash + "'").getMaterializedRows())
                    .isEmpty();

            queryRunner.createCatalog("limited", Web3ConnectorFactory.CONNECTOR_NAME, Map.of(
                    "web3.ethereum.rpc-url", "http://127.0.0.1:" + server.getAddress().getPort(),
                    "web3.maximum-transaction-hashes-per-query", "1"));
            assertThatThrownBy(() -> queryRunner.execute("SELECT hash FROM limited.ethereum.transactions WHERE hash IN ('" + FIRST_TRANSACTION_HASH + "', '" + SECOND_TRANSACTION_HASH + "')"))
                    .hasMessageContaining("hash predicate exceeds the configured query limit of 1");
        }
    }

    private void handleRequest(HttpExchange exchange)
            throws IOException
    {
        JsonNode requestDocument = OBJECT_MAPPER.readTree(exchange.getRequestBody());
        Iterable<JsonNode> requests = requestDocument.isArray() ? requestDocument : List.of(requestDocument);
        ArrayNode responses = OBJECT_MAPPER.createArrayNode();
        for (JsonNode request : requests) {
            responses.add(response(request));
        }
        byte[] body = OBJECT_MAPPER.writeValueAsBytes(requestDocument.isArray() ? responses : responses.get(0));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private ObjectNode response(JsonNode request)
    {
        String method = request.path("method").asText();
        String identifier = request.path("params").get(0).asText();
        ObjectNode response = OBJECT_MAPPER.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("id", request.path("id").asLong());
        if (method.equals("eth_getBlockByNumber") && (identifier.equals("safe") || identifier.equals("finalized"))) {
            response.set("result", block(20, false));
            return response;
        }
        if (method.equals("eth_getBlockByNumber") || method.equals("eth_getBlockByHash")) {
            blockDataRequests.incrementAndGet();
            response.set("result", block(10, request.path("params").get(1).asBoolean()));
            return response;
        }
        if (method.equals("eth_getTransactionByHash")) {
            transactionDataRequests.incrementAndGet();
            response.set("result", transaction(identifier));
            return response;
        }
        response.putObject("error").put("code", -32601).put("message", "Method not found");
        return response;
    }

    private static ObjectNode block(long number, boolean fullTransactions)
    {
        ObjectNode block = OBJECT_MAPPER.createObjectNode()
                .put("number", "0x" + Long.toHexString(number))
                .put("hash", number == 10 ? BLOCK_HASH : hash('b'));
        if (fullTransactions) {
            block.putArray("transactions").add(transaction(FIRST_TRANSACTION_HASH));
        }
        return block;
    }

    private static ObjectNode transaction(String transactionHash)
    {
        return OBJECT_MAPPER.createObjectNode()
                .put("hash", transactionHash)
                .put("blockNumber", "0xa")
                .put("blockHash", BLOCK_HASH)
                .put("from", "0xfrom")
                .put("to", "0xto");
    }

    private static String hash(char value)
    {
        return "0x" + String.valueOf(value).repeat(64);
    }
}
