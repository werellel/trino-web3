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

public class TestSolanaTables
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private HttpServer server;
    private AtomicInteger requestCount;

    @BeforeEach
    public void startServer()
            throws IOException
    {
        requestCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handleRequest);
        server.start();
    }

    @AfterEach
    public void stopServer()
    {
        server.stop(0);
    }

    @Test
    public void testExecutesNativeSolanaTables()
            throws Exception
    {
        Session session = testSessionBuilder().setCatalog("web3").setSchema("solana").build();
        try (DistributedQueryRunner queryRunner = DistributedQueryRunner.builder(session).setWorkerCount(1).build()) {
            queryRunner.installPlugin(new Web3Plugin());
            queryRunner.createCatalog("web3", Web3ConnectorFactory.CONNECTOR_NAME, Map.of(
                    "web3.solana.rpc-url", endpoint(),
                    "web3.cache.enabled", "true"));

            assertThat(queryRunner.execute("""
                    SELECT slot, blockhash, parent_slot, block_time
                    FROM web3.solana.blocks
                    WHERE slot BETWEEN 10 AND 11
                    ORDER BY slot
                    """).getMaterializedRows()).extracting(row -> row.getFields())
                    .containsExactly(java.util.Arrays.asList(10L, "block-10", 9L, 1_000L));
            assertThat(queryRunner.execute("""
                    SELECT slot, signature, success, fee
                    FROM web3.solana.transactions
                    WHERE slot = 10
                    """).getMaterializedRows()).extracting(row -> row.getFields())
                    .containsExactly(List.of(10L, "signature-10", true, 5_000L));
            assertThat(queryRunner.execute("""
                    SELECT slot, transaction_signature, instruction_index, program_id, account_indices, data
                    FROM web3.solana.instructions
                    WHERE slot = 10
                    """).getMaterializedRows()).extracting(row -> row.getFields())
                    .containsExactly(List.of(10L, "signature-10", 0L, "program-10", "[0]", "3Bxs"));
            assertThat(requestCount).hasValueGreaterThanOrEqualTo(3);

            String blockQuery = "SELECT blockhash FROM web3.solana.blocks WHERE slot = 10";
            int requestsBeforeRepeatedScan = requestCount.get();
            assertThat(queryRunner.execute(blockQuery).getOnlyColumn()).containsExactly("block-10");
            assertThat(queryRunner.execute(blockQuery).getOnlyColumn()).containsExactly("block-10");
            assertThat(requestCount).hasValue(requestsBeforeRepeatedScan + 2);
        }
    }

    @Test
    public void testRejectsUnboundedSolanaScanBeforeRemoteWork()
            throws Exception
    {
        Session session = testSessionBuilder().setCatalog("web3").setSchema("solana").build();
        try (DistributedQueryRunner queryRunner = DistributedQueryRunner.builder(session).setWorkerCount(1).build()) {
            queryRunner.installPlugin(new Web3Plugin());
            queryRunner.createCatalog("web3", Web3ConnectorFactory.CONNECTOR_NAME, Map.of(
                    "web3.solana.rpc-url", endpoint()));

            assertThatThrownBy(() -> queryRunner.execute("SELECT count(*) FROM web3.solana.instructions"))
                    .hasStackTraceContaining("solana.instructions requires a bounded slot predicate");
            assertThat(requestCount).hasValue(0);
        }
    }

    private String endpoint()
    {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handleRequest(HttpExchange exchange)
            throws IOException
    {
        requestCount.incrementAndGet();
        JsonNode request = OBJECT_MAPPER.readTree(exchange.getRequestBody());
        List<JsonNode> requests = request.isArray() ?
                java.util.stream.StreamSupport.stream(request.spliterator(), false).toList() :
                List.of(request);
        ArrayNode responses = OBJECT_MAPPER.createArrayNode();
        for (JsonNode item : requests) {
            assertThat(item.path("method").asText()).isEqualTo("getBlock");
            assertThat(item.path("params").get(1).path("commitment").asText()).isEqualTo("finalized");
            long slot = item.path("params").get(0).longValue();
            ObjectNode response = responses.addObject();
            response.put("jsonrpc", "2.0");
            response.set("id", item.get("id"));
            if (slot == 11) {
                response.putNull("result");
            }
            else {
                response.set("result", block(slot));
            }
        }
        byte[] response = OBJECT_MAPPER.writeValueAsBytes(request.isArray() ? responses : responses.get(0));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private static ObjectNode block(long slot)
    {
        ObjectNode block = OBJECT_MAPPER.createObjectNode();
        block.put("blockhash", "block-" + slot);
        block.put("parentSlot", slot - 1);
        block.put("blockTime", slot * 100);
        ArrayNode transactions = block.putArray("transactions");
        ObjectNode transaction = transactions.addObject();
        transaction.putObject("meta").putNull("err").put("fee", 5_000);
        ObjectNode transactionValue = transaction.putObject("transaction");
        transactionValue.putArray("signatures").add("signature-" + slot);
        ObjectNode message = transactionValue.putObject("message");
        message.putArray("accountKeys").add("payer-" + slot).add("program-" + slot);
        ObjectNode instruction = message.putArray("instructions").addObject();
        instruction.put("programIdIndex", 1);
        instruction.putArray("accounts").add(0);
        instruction.put("data", "3Bxs");
        return block;
    }
}
