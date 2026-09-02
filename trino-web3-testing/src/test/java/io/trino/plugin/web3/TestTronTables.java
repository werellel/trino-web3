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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.trino.testing.TestingSession.testSessionBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestTronTables
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private HttpServer server;
    private AtomicInteger blockRequests;

    @BeforeEach
    public void startServer()
            throws IOException
    {
        blockRequests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/wallet/getnowblock", this::handleIdentity);
        server.createContext("/wallet/getblockbynum", this::handleBlock);
        server.start();
    }

    @AfterEach
    public void stopServer()
    {
        server.stop(0);
    }

    @Test
    public void testExecutesNativeTronQueries()
            throws Exception
    {
        Session session = testSessionBuilder().setCatalog("web3").setSchema("tron").build();
        try (DistributedQueryRunner queryRunner = DistributedQueryRunner.builder(session).setWorkerCount(1).build()) {
            queryRunner.installPlugin(new Web3Plugin());
            queryRunner.createCatalog("web3", Web3ConnectorFactory.CONNECTOR_NAME, Map.of("web3.tron.api-url", endpoint()));

            assertThat(queryRunner.execute("SELECT block_number, block_hash, transaction_count FROM web3.tron.blocks WHERE block_number = 10").getMaterializedRows())
                    .extracting(row -> row.getFields())
                    .containsExactly(java.util.List.of(10L, "hash-10", 1L));
            assertThat(queryRunner.execute("SELECT txid, block_number, contract_count FROM web3.tron.transactions WHERE block_number = 10").getMaterializedRows())
                    .extracting(row -> row.getFields())
                    .containsExactly(java.util.List.of("tx-10", 10L, 1L));
            assertThat(blockRequests).hasValue(2);
        }
    }

    @Test
    public void testUnboundedTronScanIsRejectedBeforeRemoteWork()
            throws Exception
    {
        Session session = testSessionBuilder().setCatalog("web3").setSchema("tron").build();
        try (DistributedQueryRunner queryRunner = DistributedQueryRunner.builder(session).setWorkerCount(1).build()) {
            queryRunner.installPlugin(new Web3Plugin());
            queryRunner.createCatalog("web3", Web3ConnectorFactory.CONNECTOR_NAME, Map.of("web3.tron.api-url", endpoint()));

            assertThatThrownBy(() -> queryRunner.execute("SELECT count(*) FROM web3.tron.blocks"))
                    .hasStackTraceContaining("tron.blocks requires a bounded block_number predicate");
            assertThat(blockRequests).hasValue(0);
        }
    }

    private String endpoint()
    {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handleIdentity(HttpExchange exchange)
            throws IOException
    {
        ObjectNode response = OBJECT_MAPPER.createObjectNode().put("blockID", "hash-10");
        response.putObject("block_header").putObject("raw_data").put("number", 10);
        respond(exchange, response);
    }

    private void handleBlock(HttpExchange exchange)
            throws IOException
    {
        blockRequests.incrementAndGet();
        JsonNode request = OBJECT_MAPPER.readTree(exchange.getRequestBody());
        long number = request.path("num").longValue();
        ObjectNode response = OBJECT_MAPPER.createObjectNode().put("blockID", "hash-" + number);
        response.putObject("block_header").putObject("raw_data").put("number", number).put("timestamp", 1_700_000_000_000L);
        response.putArray("transactions").addObject()
                .put("txID", "tx-" + number)
                .putObject("raw_data").putArray("contract").addObject().put("type", "TransferContract");
        respond(exchange, response);
    }

    private static void respond(HttpExchange exchange, JsonNode response)
            throws IOException
    {
        byte[] body = OBJECT_MAPPER.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
