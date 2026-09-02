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

public class TestSuiTables
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private HttpServer server;

    @BeforeEach
    public void startServer()
            throws IOException
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    public void stopServer()
    {
        server.stop(0);
    }

    @Test
    public void testExecutesNativeSuiQueries()
            throws Exception
    {
        Session session = testSessionBuilder().setCatalog("web3").setSchema("sui").build();
        try (DistributedQueryRunner queryRunner = DistributedQueryRunner.builder(session).setWorkerCount(1).build()) {
            queryRunner.installPlugin(new Web3Plugin());
            queryRunner.createCatalog("web3", Web3ConnectorFactory.CONNECTOR_NAME, Map.of("web3.sui.rpc-url", endpoint()));

            assertThat(queryRunner.execute("SELECT checkpoint_sequence_number, digest, transaction_count FROM web3.sui.checkpoints WHERE checkpoint_sequence_number = 10").getMaterializedRows())
                    .extracting(row -> row.getFields())
                    .containsExactly(java.util.List.of(10L, "checkpoint-10", 1L));
            assertThat(queryRunner.execute("SELECT checkpoint_sequence_number, digest, sender, status FROM web3.sui.transactions WHERE checkpoint_sequence_number = 10").getMaterializedRows())
                    .extracting(row -> row.getFields())
                    .containsExactly(java.util.List.of(10L, "tx-10", "0xabc", "success"));
        }
    }

    private String endpoint()
    {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange)
            throws IOException
    {
        JsonNode request = OBJECT_MAPPER.readTree(exchange.getRequestBody());
        if (request.isArray()) {
            var response = OBJECT_MAPPER.createArrayNode();
            for (JsonNode item : request) {
                response.add(response(item));
            }
            respond(exchange, response);
            return;
        }
        respond(exchange, response(request));
    }

    private ObjectNode response(JsonNode request)
    {
        String method = request.path("method").asText();
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", request.get("id"));
        ObjectNode result = OBJECT_MAPPER.createObjectNode();
        if (method.equals("sui_getChainIdentifier")) {
            response.put("result", "sui-local");
        }
        else if (method.equals("sui_getCheckpoint")) {
            long sequence = Long.parseLong(request.path("params").get(0).asText());
            result.put("sequenceNumber", sequence).put("digest", "checkpoint-" + sequence).put("epoch", 3).put("timestampMs", 1_700_000_000_000L);
            result.putArray("transactions").add("tx-" + sequence);
            response.set("result", result);
        }
        else if (method.equals("sui_getTransactionBlock")) {
            String digest = request.path("params").get(0).asText();
            result.put("digest", digest).putObject("transaction").putObject("data").put("sender", "0xabc");
            result.putObject("effects").putObject("status").put("status", "success");
            response.set("result", result);
        }
        else {
            response.putObject("error").put("code", -32601).put("message", "method not found");
        }
        return response;
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
