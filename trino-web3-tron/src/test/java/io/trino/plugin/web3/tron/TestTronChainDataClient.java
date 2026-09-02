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
package io.trino.plugin.web3.tron;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.trino.plugin.web3.adapter.RangeChainSplit;
import io.trino.plugin.web3.runtime.ExecutionPolicy;
import io.trino.plugin.web3.runtime.ProviderCapabilities;
import io.trino.plugin.web3.runtime.ProviderProfile;
import io.trino.plugin.web3.runtime.RemoteCacheConfig;
import io.trino.plugin.web3.runtime.RemoteExecutionRuntime;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

final class TestTronChainDataClient
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void testExecutesNativeBlockRequestAndDecodesTransactions()
            throws Exception
    {
        AtomicReference<JsonNode> request = new AtomicReference<>();
        try (TestingServer server = createServer(exchange -> {
            request.set(OBJECT_MAPPER.readTree(exchange.getRequestBody()));
            respond(exchange, """
                    {
                      "blockID":"hash-10",
                      "block_header":{"raw_data":{"number":10,"timestamp":1700000000000}},
                      "transactions":[{"txID":"tx-10","raw_data":{"contract":[{"type":"TransferContract"}]},"newField":"kept"}]
                    }
                    """);
        });
                RemoteExecutionRuntime runtime = runtime(server.endpoint())) {
            TronChainDataClient client = new TronChainDataClient(runtime);

            var blockRows = client.execute("blocks", new RangeChainSplit("block_number", 10, 10)).future().join();
            var transactionRows = client.execute("transactions", new RangeChainSplit("block_number", 10, 10)).future().join();

            assertThat(request.get().path("num").longValue()).isEqualTo(10);
            assertThat(blockRows).hasSize(1);
            assertThat(blockRows.get(0).value("block_hash").textValue()).isEqualTo("hash-10");
            assertThat(blockRows.get(0).value("transaction_count").longValue()).isEqualTo(1);
            assertThat(transactionRows).hasSize(1);
            assertThat(transactionRows.get(0).value("txid").textValue()).isEqualTo("tx-10");
            assertThat(transactionRows.get(0).value("raw_json").textValue()).contains("\"newField\":\"kept\"");
        }
    }

    private static RemoteExecutionRuntime runtime(URI endpoint)
    {
        return RemoteExecutionRuntime.forRest(
                HttpClient.newHttpClient(),
                List.of(new ProviderProfile("tron", endpoint, new ProviderCapabilities(false))),
                Duration.ofSeconds(2),
                1_024,
                1_024 * 1_024,
                ExecutionPolicy.defaults(),
                RemoteCacheConfig.disabled());
    }

    private static TestingServer createServer(ExchangeHandler handler)
            throws IOException
    {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/wallet/getblockbynum", exchange -> {
            try {
                handler.handle(exchange);
            }
            finally {
                exchange.close();
            }
        });
        server.start();
        return new TestingServer(server);
    }

    private static void respond(HttpExchange exchange, String value)
            throws IOException
    {
        byte[] body = value.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
    }

    @FunctionalInterface
    private interface ExchangeHandler
    {
        void handle(HttpExchange exchange)
                throws IOException;
    }

    private record TestingServer(HttpServer server)
            implements AutoCloseable
    {
        private URI endpoint()
        {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        @Override
        public void close()
        {
            server.stop(0);
        }
    }
}
