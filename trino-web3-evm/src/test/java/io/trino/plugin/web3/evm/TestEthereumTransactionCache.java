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
import io.trino.plugin.web3.runtime.RemoteCacheConfig;
import io.trino.plugin.web3.runtime.RemoteExecution;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestEthereumTransactionCache
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String TRANSACTION_HASH = hash('c');

    private final AtomicInteger transactionRequests = new AtomicInteger();
    private final AtomicInteger blockRequests = new AtomicInteger();
    private HttpServer server;
    private long transactionBlock = 10;
    private long finalizedBlock = 10;
    private boolean pendingTransaction;
    private boolean missingTransaction;
    private boolean mismatchedTransactionBlockHash;

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
    public void testFinalizedTransactionHashIsCached()
    {
        try (RemoteExecutionRuntime runtime = runtime()) {
            EthereumTransactionClient client = new EthereumTransactionClient(runtime);

            String uppercaseHash = "0x" + TRANSACTION_HASH.substring(2).toUpperCase(java.util.Locale.ENGLISH);
            assertThat(client.getTransaction(uppercaseHash).future().join()).hasSize(1);
            RemoteExecution<List<EthereumTransactionClient.EthereumTransaction>> warm = client.getTransaction(TRANSACTION_HASH);
            assertThat(warm.future().join()).hasSize(1);

            assertThat(transactionRequests).hasValue(1);
            assertThat(warm.metrics().cacheHitCount()).isOne();
        }
    }

    @Test
    public void testNearHeadTransactionIsRevalidatedAfterReorg()
    {
        finalizedBlock = 9;
        try (RemoteExecutionRuntime runtime = runtime()) {
            EthereumTransactionClient client = new EthereumTransactionClient(runtime);

            assertThat(client.getTransaction(TRANSACTION_HASH).future().join().getFirst().blockNumber()).isEqualTo(10);
            transactionBlock = 11;
            RemoteExecution<List<EthereumTransactionClient.EthereumTransaction>> moved = client.getTransaction(TRANSACTION_HASH);

            assertThat(moved.future().join().getFirst().blockNumber()).isEqualTo(11);
            assertThat(transactionRequests).hasValue(2);
            assertThat(moved.metrics().cacheRevalidationCount()).isOne();
        }
    }

    @Test
    public void testFinalizedFullBlockTransactionsAreCachedByBlockHash()
    {
        try (RemoteExecutionRuntime runtime = runtime()) {
            EthereumTransactionClient client = new EthereumTransactionClient(runtime);

            assertThat(client.getTransactions(new BlockRange(10, 10)).future().join()).hasSize(1);
            assertThat(client.getTransactions(new BlockRange(10, 10)).future().join()).hasSize(1);

            assertThat(blockRequests).hasValue(1);
        }
    }

    @Test
    public void testPendingTransactionIsReturnedAndRevalidated()
    {
        pendingTransaction = true;
        try (RemoteExecutionRuntime runtime = runtime()) {
            EthereumTransactionClient client = new EthereumTransactionClient(runtime);

            assertThat(client.getTransaction(TRANSACTION_HASH).future().join().getFirst().blockNumber()).isNull();
            assertThat(client.getTransaction(TRANSACTION_HASH).future().join().getFirst().blockNumber()).isNull();

            assertThat(transactionRequests).hasValue(2);
        }
    }

    @Test
    public void testMissingTransactionIsNotNegativeCached()
    {
        missingTransaction = true;
        try (RemoteExecutionRuntime runtime = runtime()) {
            EthereumTransactionClient client = new EthereumTransactionClient(runtime);

            assertThat(client.getTransaction(TRANSACTION_HASH).future().join()).isEmpty();
            assertThat(client.getTransaction(TRANSACTION_HASH).future().join()).isEmpty();

            assertThat(transactionRequests).hasValue(2);
            assertThat(runtime.cacheMetrics().entryCount()).isZero();
        }
    }

    @Test
    public void testInvalidFullBlockTransactionIsNotAdmitted()
    {
        mismatchedTransactionBlockHash = true;
        try (RemoteExecutionRuntime runtime = runtime()) {
            EthereumTransactionClient client = new EthereumTransactionClient(runtime);

            assertThatThrownBy(() -> client.getTransactions(new BlockRange(10, 10)).future().join())
                    .hasRootCauseMessage("Ethereum transaction block hash does not match its block response");
            assertThatThrownBy(() -> client.getTransactions(new BlockRange(10, 10)).future().join())
                    .hasRootCauseMessage("Ethereum transaction block hash does not match its block response");

            assertThat(blockRequests).hasValue(2);
            assertThat(runtime.cacheMetrics().entryCount()).isZero();
        }
    }

    private RemoteExecutionRuntime runtime()
    {
        return new RemoteExecutionRuntime(
                HttpClient.newHttpClient(),
                List.of(new ProviderProfile("primary", URI.create("http://127.0.0.1:" + server.getAddress().getPort()))),
                Duration.ofSeconds(1),
                8_192,
                64 * 1_024,
                ExecutionPolicy.defaults(),
                new RemoteCacheConfig(true, 1_048_576, 64 * 1_024, Optional.empty()));
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
        JsonNode responseDocument = requestDocument.isArray() ? responses : responses.get(0);
        byte[] body = OBJECT_MAPPER.writeValueAsBytes(responseDocument);
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
        if (identifier.equals("safe") || identifier.equals("finalized")) {
            long number = identifier.equals("safe") ? Math.max(finalizedBlock, transactionBlock) : finalizedBlock;
            response.set("result", block(number, false));
            return response;
        }
        if (method.equals("eth_getTransactionByHash")) {
            transactionRequests.incrementAndGet();
            if (missingTransaction) {
                response.putNull("result");
            }
            else {
                response.set("result", transaction());
            }
            return response;
        }
        if (method.equals("eth_getBlockByNumber") || method.equals("eth_getBlockByHash")) {
            blockRequests.incrementAndGet();
            response.set("result", block(10, true));
            return response;
        }
        response.putObject("error").put("code", -32601).put("message", "Method not found");
        return response;
    }

    private ObjectNode block(long number, boolean withTransactions)
    {
        ObjectNode block = OBJECT_MAPPER.createObjectNode()
                .put("number", "0x" + Long.toHexString(number))
                .put("hash", hash(number == 10 ? 'a' : 'b'));
        if (withTransactions) {
            block.putArray("transactions").add(transaction());
        }
        return block;
    }

    private ObjectNode transaction()
    {
        ObjectNode transaction = OBJECT_MAPPER.createObjectNode()
                .put("hash", TRANSACTION_HASH)
                .put("from", "0xfrom")
                .put("to", "0xto");
        if (pendingTransaction) {
            transaction.putNull("blockNumber");
            transaction.putNull("blockHash");
        }
        else {
            transaction.put("blockNumber", "0x" + Long.toHexString(transactionBlock));
            transaction.put("blockHash", mismatchedTransactionBlockHash ? hash('e') : hash(transactionBlock == 10 ? 'a' : 'b'));
        }
        return transaction;
    }

    private static String hash(char value)
    {
        return "0x" + String.valueOf(value).repeat(64);
    }
}
