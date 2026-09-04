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
package io.trino.plugin.web3.evm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.trino.plugin.web3.core.BlockRange;
import io.trino.plugin.web3.runtime.ExecutionPolicy;
import io.trino.plugin.web3.runtime.ProviderProfile;
import io.trino.plugin.web3.runtime.RemoteExecutionRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

final class TestEthereumReceiptsAndLogs
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HASH = "0x" + "a".repeat(64);
    private HttpServer server;

    @BeforeEach
    void setUp()
            throws IOException
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void tearDown()
    {
        server.stop(0);
    }

    @Test
    void testReceiptAndLogDecodingPreservesRawJson()
    {
        try (RemoteExecutionRuntime runtime = new RemoteExecutionRuntime(
                HttpClient.newHttpClient(),
                List.of(new ProviderProfile("primary", URI.create("http://127.0.0.1:" + server.getAddress().getPort()))),
                Duration.ofSeconds(1), 64 * 1024, 64 * 1024, ExecutionPolicy.defaults())) {
            EthereumReceiptClient receipts = new EthereumReceiptClient(runtime);
            EthereumReceiptClient.EthereumReceipt receipt = receipts.getReceipt(HASH).future().join().getFirst();
            assertThat(receipt.transactionHash()).isEqualTo(HASH);
            assertThat(receipt.status()).isEqualTo(1L);
            assertThat(receipt.rawJson()).contains("futureReceiptField");

            EthereumLogClient logs = new EthereumLogClient(runtime);
            EthereumLogClient.EthereumLog log = logs.getLogs(new BlockRange(100, 100)).future().join().getFirst();
            assertThat(log.blockNumber()).isEqualTo(100);
            assertThat(log.topic0()).isEqualTo("0x" + "b".repeat(64));
            assertThat(log.topic1()).isNull();
            assertThat(log.rawJson()).contains("futureLogField");
        }
    }

    private void handle(HttpExchange exchange)
            throws IOException
    {
        JsonNode request = MAPPER.readTree(exchange.getRequestBody());
        ArrayNode responses = MAPPER.createArrayNode();
        for (JsonNode item : request.isArray() ? request : List.of(request)) {
            responses.add(response(item));
        }
        JsonNode body = request.isArray() ? responses : responses.get(0);
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private ObjectNode response(JsonNode request)
    {
        ObjectNode response = MAPPER.createObjectNode().put("jsonrpc", "2.0").put("id", request.path("id").asLong());
        if (request.path("method").asText().equals("eth_getTransactionReceipt")) {
            response.set("result", receipt());
        }
        else {
            response.set("result", MAPPER.createArrayNode().add(log()));
        }
        return response;
    }

    private ObjectNode receipt()
    {
        return MAPPER.createObjectNode()
                .put("transactionHash", HASH)
                .put("transactionIndex", "0x0")
                .put("blockNumber", "0x64")
                .put("blockHash", "0x" + "c".repeat(64))
                .put("from", "0xfrom")
                .put("to", "0xto")
                .put("gasUsed", "0x5208")
                .put("status", "0x1")
                .put("futureReceiptField", "present");
    }

    private ObjectNode log()
    {
        return MAPPER.createObjectNode()
                .put("blockNumber", "0x64")
                .put("blockHash", "0x" + "c".repeat(64))
                .put("transactionHash", HASH)
                .put("transactionIndex", "0x0")
                .put("logIndex", "0x0")
                .put("address", "0xaddress")
                .put("data", "0xdeadbeef")
                .put("removed", false)
                .put("futureLogField", "present")
                .set("topics", MAPPER.createArrayNode().add("0x" + "b".repeat(64)));
    }
}
