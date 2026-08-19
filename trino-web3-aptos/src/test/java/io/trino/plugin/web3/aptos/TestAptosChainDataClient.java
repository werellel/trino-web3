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
package io.trino.plugin.web3.aptos;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestAptosChainDataClient
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    public void testExecutesBoundedRestRequest()
            throws Exception
    {
        AtomicReference<String> target = new AtomicReference<>();
        try (TestingServer server = createServer(exchange -> {
            target.set(exchange.getRequestURI().toString());
            respond(exchange, """
                    [
                      {"version":"10","hash":"0xaaa","type":"user_transaction","success":true,"vm_status":"Executed","sender":"0x1"},
                      {"version":"11","hash":"0xbbb","type":"block_metadata_transaction","success":true,"vm_status":"Executed"}
                    ]
                    """);
        });
                RemoteExecutionRuntime runtime = runtime(server.endpoint())) {
            var execution = new AptosChainDataClient(runtime)
                    .execute("transactions", new RangeChainSplit("ledger_version", 10, 11));
            var rows = execution.future().join();

            assertThat(target.get()).isEqualTo("/v1/transactions?limit=2&start=10");
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).value("ledger_version").longValue()).isEqualTo(10);
            assertThat(rows.get(0).value("sender").textValue()).isEqualTo("0x1");
            assertThat(rows.get(1).value("sender").isNull()).isTrue();
            assertThat(execution.metrics().requestCount()).isEqualTo(1);
        }
    }

    @Test
    public void testDecodesCompleteContiguousRange()
            throws Exception
    {
        JsonNode response = OBJECT_MAPPER.readTree("""
                [
                  {"version":"20","hash":"0x1","type":"user_transaction","success":false,"vm_status":"Move abort","sender":"0xa"},
                  {"version":"21","hash":"0x2","type":"state_checkpoint_transaction","success":true,"vm_status":"Executed"}
                ]
                """);

        var rows = AptosChainDataClient.decodeTransactions(new RangeChainSplit("ledger_version", 20, 21), response);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).value("success").booleanValue()).isFalse();
        assertThat(rows.get(1).value("type").textValue()).isEqualTo("state_checkpoint_transaction");
    }

    @Test
    public void testRejectsPartialWrongAndOverflowResponses()
            throws Exception
    {
        JsonNode partial = OBJECT_MAPPER.readTree("""
                [{"version":"10","hash":"0x1","type":"user_transaction","success":true,"vm_status":"Executed"}]
                """);
        assertThatThrownBy(() -> AptosChainDataClient.decodeTransactions(
                new RangeChainSplit("ledger_version", 10, 11),
                partial))
                .hasMessage("Aptos transactions response does not contain the requested ledger version range");

        JsonNode wrongVersion = OBJECT_MAPPER.readTree("""
                [{"version":"11","hash":"0x1","type":"user_transaction","success":true,"vm_status":"Executed"}]
                """);
        assertThatThrownBy(() -> AptosChainDataClient.decodeTransactions(
                new RangeChainSplit("ledger_version", 10, 10),
                wrongVersion))
                .hasMessage("Aptos transaction ledger version does not match its request");

        JsonNode overflow = OBJECT_MAPPER.readTree("""
                [{"version":"18446744073709551615","hash":"0x1","type":"user_transaction","success":true,"vm_status":"Executed"}]
                """);
        assertThatThrownBy(() -> AptosChainDataClient.decodeTransactions(
                new RangeChainSplit("ledger_version", 10, 10),
                overflow))
                .hasMessage("Aptos transaction has an invalid ledger version");
    }

    @Test
    public void testRejectsMalformedRequiredFieldsWithoutPayloadDisclosure()
            throws Exception
    {
        String secret = "do-not-leak-aptos-payload";
        JsonNode malformed = OBJECT_MAPPER.readTree("""
                [{"version":"10","hash":"0x1","type":"user_transaction","success":"%s","vm_status":"Executed"}]
                """.formatted(secret));

        assertThatThrownBy(() -> AptosChainDataClient.decodeTransactions(
                new RangeChainSplit("ledger_version", 10, 10),
                malformed))
                .hasMessage("Aptos transaction has an invalid success")
                .hasMessageNotContaining(secret);
    }

    private static RemoteExecutionRuntime runtime(URI endpoint)
    {
        ProviderProfile provider = new ProviderProfile("aptos", endpoint, new ProviderCapabilities(false));
        return RemoteExecutionRuntime.forRest(
                HttpClient.newHttpClient(),
                List.of(provider),
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
        server.createContext("/", exchange -> {
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
