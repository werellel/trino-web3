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
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.trino.testing.TestingSession.testSessionBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestAptosEvents
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private HttpServer server;
    private AtomicInteger requestCount;
    private AtomicReference<String> requestTarget;

    @BeforeEach
    public void startServer()
            throws IOException
    {
        requestCount = new AtomicInteger();
        requestTarget = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1", exchange -> {
            byte[] body = OBJECT_MAPPER.writeValueAsBytes(Map.of("chain_id", 1));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/v1/accounts/0x1/events/7", this::handleEvents);
        server.start();
    }

    @AfterEach
    public void stopServer()
    {
        server.stop(0);
    }

    @Test
    public void testExecutesNativeAptosEventQuery()
            throws Exception
    {
        Session session = testSessionBuilder().setCatalog("web3").setSchema("aptos").build();
        try (DistributedQueryRunner queryRunner = DistributedQueryRunner.builder(session).setWorkerCount(1).build()) {
            queryRunner.installPlugin(new Web3Plugin());
            queryRunner.createCatalog("web3", Web3ConnectorFactory.CONNECTOR_NAME, Map.of(
                    "web3.aptos.rest-url", endpoint()));

            var result = queryRunner.execute("""
                    SELECT account_address, creation_number, sequence_number, event_type, data
                    FROM web3.aptos.events
                    WHERE account_address = '0x1'
                      AND creation_number = '7'
                      AND sequence_number BETWEEN 10 AND 11
                    ORDER BY sequence_number
                    """);

            assertThat(result.getMaterializedRows()).extracting(row -> row.getFields())
                    .containsExactly(
                            java.util.List.of("0x1", "7", 10L, "0x1::coin::WithdrawEvent", "{\"amount\":\"100\"}"),
                            java.util.List.of("0x1", "7", 11L, "0x1::coin::WithdrawEvent", "{\"amount\":\"200\"}"));
            assertThat(requestTarget).hasValue("/v1/accounts/0x1/events/7?limit=2&start=10");
            assertThat(requestCount).hasValue(1);
        }
    }

    @Test
    public void testRejectsUnboundedEventScanBeforeRemoteWork()
            throws Exception
    {
        Session session = testSessionBuilder().setCatalog("web3").setSchema("aptos").build();
        try (DistributedQueryRunner queryRunner = DistributedQueryRunner.builder(session).setWorkerCount(1).build()) {
            queryRunner.installPlugin(new Web3Plugin());
            queryRunner.createCatalog("web3", Web3ConnectorFactory.CONNECTOR_NAME, Map.of(
                    "web3.aptos.rest-url", endpoint()));

            assertThatThrownBy(() -> queryRunner.execute("""
                    SELECT count(*)
                    FROM web3.aptos.events
                    WHERE account_address = '0x1' AND creation_number = '7'
                    """))
                    .hasStackTraceContaining("aptos.events requires a bounded sequence_number predicate");
            assertThat(requestCount).hasValue(0);
        }
    }

    private String endpoint()
    {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handleEvents(HttpExchange exchange)
            throws IOException
    {
        requestCount.incrementAndGet();
        requestTarget.set(exchange.getRequestURI().toString());
        Map<String, String> query = Arrays.stream(exchange.getRequestURI().getRawQuery().split("&"))
                .map(value -> value.split("=", 2))
                .collect(Collectors.toMap(
                        value -> URLDecoder.decode(value[0], StandardCharsets.UTF_8),
                        value -> URLDecoder.decode(value[1], StandardCharsets.UTF_8)));
        long start = Long.parseLong(query.get("start"));
        int limit = Integer.parseInt(query.get("limit"));
        ArrayNode events = OBJECT_MAPPER.createArrayNode();
        for (int index = 0; index < limit; index++) {
            long sequenceNumber = start + index;
            ObjectNode event = events.addObject();
            ObjectNode guid = event.putObject("guid");
            guid.put("account_address", "0x1");
            guid.put("creation_number", "7");
            event.put("sequence_number", Long.toString(sequenceNumber));
            event.put("type", "0x1::coin::WithdrawEvent");
            event.putObject("data").put("amount", Long.toString((sequenceNumber - 9) * 100));
        }
        byte[] body = OBJECT_MAPPER.writeValueAsBytes(events);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
