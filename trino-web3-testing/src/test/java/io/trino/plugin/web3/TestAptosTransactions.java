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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static io.trino.testing.TestingSession.testSessionBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestAptosTransactions
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
        server.createContext("/v1", exchange -> {
            byte[] body = OBJECT_MAPPER.writeValueAsBytes(Map.of("chain_id", 1));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/v1/transactions", this::handleTransactions);
        server.start();
    }

    @AfterEach
    public void stopServer()
    {
        server.stop(0);
    }

    @Test
    public void testExecutesNativeAptosTransactionQuery()
            throws Exception
    {
        Session session = testSessionBuilder().setCatalog("web3").setSchema("aptos").build();
        try (DistributedQueryRunner queryRunner = DistributedQueryRunner.builder(session).setWorkerCount(1).build()) {
            queryRunner.installPlugin(new Web3Plugin());
            queryRunner.createCatalog("web3", Web3ConnectorFactory.CONNECTOR_NAME, Map.of(
                    "web3.aptos.rest-url", endpoint()));

            var result = queryRunner.execute("""
                    SELECT ledger_version, hash, type, success, vm_status, sender
                    FROM web3.aptos.transactions
                    WHERE ledger_version BETWEEN 10 AND 12
                    ORDER BY ledger_version
                    """);

            assertThat(result.getMaterializedRows()).extracting(row -> row.getFields())
                    .containsExactly(
                            java.util.List.of(10L, "0x000a", "user_transaction", true, "Executed", "0x1"),
                            java.util.Arrays.asList(11L, "0x000b", "state_checkpoint_transaction", true, "Executed", null),
                            java.util.List.of(12L, "0x000c", "user_transaction", false, "Move abort", "0x1"));
            assertThat(requestCount).hasValue(1);
        }
    }

    @Test
    public void testUnboundedAptosScanIsRejectedBeforeRemoteWork()
            throws Exception
    {
        Session session = testSessionBuilder().setCatalog("web3").setSchema("aptos").build();
        try (DistributedQueryRunner queryRunner = DistributedQueryRunner.builder(session).setWorkerCount(1).build()) {
            queryRunner.installPlugin(new Web3Plugin());
            queryRunner.createCatalog("web3", Web3ConnectorFactory.CONNECTOR_NAME, Map.of(
                    "web3.aptos.rest-url", endpoint()));

            assertThatThrownBy(() -> queryRunner.execute("SELECT count(*) FROM web3.aptos.transactions"))
                    .hasStackTraceContaining("aptos.transactions requires a bounded ledger_version predicate");
            assertThat(requestCount).hasValue(0);
        }
    }

    private String endpoint()
    {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handleTransactions(HttpExchange exchange)
            throws IOException
    {
        requestCount.incrementAndGet();
        Map<String, String> query = Arrays.stream(exchange.getRequestURI().getRawQuery().split("&"))
                .map(value -> value.split("=", 2))
                .collect(Collectors.toMap(
                        value -> URLDecoder.decode(value[0], StandardCharsets.UTF_8),
                        value -> URLDecoder.decode(value[1], StandardCharsets.UTF_8)));
        long start = Long.parseLong(query.get("start"));
        int limit = Integer.parseInt(query.get("limit"));
        ArrayNode transactions = OBJECT_MAPPER.createArrayNode();
        for (int index = 0; index < limit; index++) {
            long version = start + index;
            ObjectNode transaction = transactions.addObject();
            transaction.put("version", Long.toString(version));
            transaction.put("hash", "0x%04x".formatted(version));
            transaction.put("type", version == 11 ? "state_checkpoint_transaction" : "user_transaction");
            transaction.put("success", version != 12);
            transaction.put("vm_status", version == 12 ? "Move abort" : "Executed");
            if (version != 11) {
                transaction.put("sender", "0x1");
            }
        }
        byte[] body = OBJECT_MAPPER.writeValueAsBytes(transactions);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
