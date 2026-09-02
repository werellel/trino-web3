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

import static io.trino.testing.TestingSession.testSessionBuilder;
import static org.assertj.core.api.Assertions.assertThat;

public class TestCosmosTables
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private HttpServer server;

    @BeforeEach
    public void startServer()
            throws IOException
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cosmos/base/tendermint/v1beta1/blocks/latest", this::handle);
        server.createContext("/cosmos/base/tendermint/v1beta1/blocks/10", this::handle);
        server.start();
    }

    @AfterEach
    public void stopServer()
    {
        server.stop(0);
    }

    @Test
    public void testExecutesNativeCosmosBlockAndTransactionQueries()
            throws Exception
    {
        Session session = testSessionBuilder().setCatalog("web3").setSchema("cosmos").build();
        try (DistributedQueryRunner queryRunner = DistributedQueryRunner.builder(session).setWorkerCount(1).build()) {
            queryRunner.installPlugin(new Web3Plugin());
            queryRunner.createCatalog("web3", Web3ConnectorFactory.CONNECTOR_NAME, Map.of("web3.cosmos.rest-url", endpoint()));

            assertThat(queryRunner.execute("SELECT height, hash, chain_id, transaction_count FROM web3.cosmos.blocks WHERE height = 10").getMaterializedRows())
                    .extracting(row -> row.getFields())
                    .containsExactly(java.util.List.of(10L, "HASH10", "cosmoshub-4", 1L));
            assertThat(queryRunner.execute("SELECT height, index, tx_base64 FROM web3.cosmos.transactions WHERE height = 10").getMaterializedRows())
                    .extracting(row -> row.getFields())
                    .containsExactly(java.util.List.of(10L, 0L, "dHgx"));
        }
    }

    private String endpoint()
    {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange)
            throws IOException
    {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.putObject("block_id").put("hash", "HASH10");
        ObjectNode block = response.putObject("block");
        block.putObject("header")
                .put("chain_id", "cosmoshub-4")
                .put("height", "10")
                .put("time", "2024-01-01T00:00:00Z");
        block.putObject("data").putArray("txs").add("dHgx");
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
