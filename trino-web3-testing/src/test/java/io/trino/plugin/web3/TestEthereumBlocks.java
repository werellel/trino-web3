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
import io.trino.plugin.web3.core.BlockRange;
import io.trino.plugin.web3.core.Web3ColumnHandle;
import io.trino.plugin.web3.core.Web3Split;
import io.trino.plugin.web3.core.Web3TableHandle;
import io.trino.plugin.web3.evm.EthereumBlockClient;
import io.trino.plugin.web3.evm.EthereumTransactionClient;
import io.trino.plugin.web3.runtime.ExecutionPolicy;
import io.trino.plugin.web3.runtime.ProviderProfile;
import io.trino.plugin.web3.runtime.RemoteExecutionRuntime;
import io.trino.Session;
import io.trino.testing.MaterializedResult;
import io.trino.testing.DistributedQueryRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static io.trino.testing.TestingSession.testSessionBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestEthereumBlocks
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private HttpServer server;
    private Duration responseDelay;
    private final AtomicInteger rpcRequestCount = new AtomicInteger();

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
    public void testBoundedBlockQuery()
            throws Exception
    {
        Session session = testSessionBuilder()
                .setCatalog("web3")
                .setSchema("ethereum")
                .build();

        try (DistributedQueryRunner queryRunner = DistributedQueryRunner.builder(session)
                .setWorkerCount(1)
                .build()) {
            queryRunner.installPlugin(new Web3Plugin());
            queryRunner.createCatalog("web3", Web3ConnectorFactory.CONNECTOR_NAME, Map.of(
                    "web3.ethereum.rpc-url", "http://127.0.0.1:" + server.getAddress().getPort()));

            assertThat(queryRunner.execute("SHOW TABLES FROM web3.ethereum").getOnlyColumn())
                    .containsExactly("blocks", "transactions");
            assertThat(queryRunner.execute("SHOW COLUMNS FROM web3.ethereum.blocks").getMaterializedRows())
                    .extracting(row -> row.getField(0), row -> row.getField(1))
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("block_number", "bigint"),
                            org.assertj.core.groups.Tuple.tuple("block_hash", "varchar"),
                            org.assertj.core.groups.Tuple.tuple("raw_json", "varchar"));

            MaterializedResult result = queryRunner.execute("""
                    SELECT block_number, block_hash
                    FROM web3.ethereum.blocks
                    WHERE block_number BETWEEN 23000000 AND 23000002
                    ORDER BY block_number
                    """);
            assertThat(result.getMaterializedRows())
                    .extracting(row -> row.getField(0), row -> row.getField(1))
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(23000000L, "0x15ef3c0"),
                            org.assertj.core.groups.Tuple.tuple(23000001L, "0x15ef3c1"),
                            org.assertj.core.groups.Tuple.tuple(23000002L, "0x15ef3c2"));
            assertThat(queryRunner.execute("""
                    SELECT block_hash
                    FROM web3.ethereum.blocks
                    WHERE block_number = 23000000
                    """).getOnlyColumn()).containsExactly("0x15ef3c0");
            assertThat(queryRunner.execute("""
                    SELECT raw_json
                    FROM web3.ethereum.blocks
                    WHERE block_number = 23000000
                    """).getOnlyColumn().findFirst().map(String.class::cast).orElseThrow()).contains("\"futureBlockField\":\"present\"");

            MaterializedResult transactions = queryRunner.execute("""
                    SELECT hash, block_number, from_address, to_address
                    FROM web3.ethereum.transactions
                    WHERE block_number BETWEEN 23000000 AND 23000001
                    ORDER BY block_number
                    """);
            assertThat(transactions.getMaterializedRows())
                    .extracting(row -> row.getField(0), row -> row.getField(1), row -> row.getField(2), row -> row.getField(3))
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("0xtx15ef3c0", 23000000L, "0xfrom", "0xto"),
                            org.assertj.core.groups.Tuple.tuple("0xtx15ef3c1", 23000001L, "0xfrom", "0xto"));
            assertThat(queryRunner.execute("""
                    SELECT raw_json
                    FROM web3.ethereum.transactions
                    WHERE block_number = 23000000
                    """).getOnlyColumn().findFirst().map(String.class::cast).orElseThrow()).contains("\"futureTransactionField\":\"present\"");

            assertThatThrownBy(() -> queryRunner.execute("SELECT * FROM web3.ethereum.blocks"))
                    .hasMessageContaining("requires a bounded block_number predicate");
        }
    }

    @Test
    public void testPageSourceCancellationCancelsRpcFuture()
            throws Exception
    {
        responseDelay = Duration.ofSeconds(1);
        RemoteExecutionRuntime rpcRuntime = new RemoteExecutionRuntime(
                HttpClient.newHttpClient(),
                java.util.List.of(new ProviderProfile("primary", URI.create("http://127.0.0.1:" + server.getAddress().getPort()))),
                Duration.ofSeconds(5),
                1_024,
                1_024,
                ExecutionPolicy.defaults());
        Web3PageSourceProvider provider = new Web3PageSourceProvider(
                new EthereumBlockClient(rpcRuntime),
                new EthereumTransactionClient(rpcRuntime));
        BlockRange range = new BlockRange(23000000, 23000000);
        io.trino.spi.connector.ConnectorPageSource pageSource = provider.createPageSource(
                null,
                null,
                new Web3Split(range),
                new Web3TableHandle("ethereum", "blocks", Optional.of(range)),
                java.util.List.of(new Web3ColumnHandle("block_number", 0)),
                null);

        CompletableFuture<?> blocked = pageSource.isBlocked();
        pageSource.close();

        assertThat(pageSource.isFinished()).isTrue();
        assertThat(blocked.isCancelled()).isTrue();
        rpcRuntime.close();
    }

    @Test
    public void testDistributedQueryFallsBackFromUnavailablePrimary()
            throws Exception
    {
        HttpServer unavailablePrimary = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        unavailablePrimary.createContext("/", exchange -> {
            JsonNode requestDocument = OBJECT_MAPPER.readTree(exchange.getRequestBody());
            JsonNode request = requestDocument.isArray() ? requestDocument.get(0) : requestDocument;
            if (request.path("method").asText().equals("eth_chainId")) {
                writeChainId(exchange, requestDocument);
                return;
            }
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        unavailablePrimary.start();
        Session session = testSessionBuilder().setCatalog("web3").setSchema("ethereum").build();
        try (DistributedQueryRunner queryRunner = DistributedQueryRunner.builder(session).setWorkerCount(1).build()) {
            queryRunner.installPlugin(new Web3Plugin());
            queryRunner.createCatalog("web3", Web3ConnectorFactory.CONNECTOR_NAME, Map.of(
                    "web3.ethereum.rpc-url", "http://127.0.0.1:" + unavailablePrimary.getAddress().getPort(),
                    "web3.ethereum.rpc-fallback-urls", "http://127.0.0.1:" + server.getAddress().getPort(),
                    "web3.rpc.initial-backoff-millis", "1",
                    "web3.rpc.provider-cooldown-millis", "1"));

            assertThat(queryRunner.execute("SELECT block_hash FROM web3.ethereum.blocks WHERE block_number = 23000000").getOnlyColumn())
                    .containsExactly("0x15ef3c0");
        }
        finally {
            unavailablePrimary.stop(0);
        }
    }

    @Test
    public void testPageSourceExposesRpcMetrics()
            throws Exception
    {
        try (RemoteExecutionRuntime rpcRuntime = new RemoteExecutionRuntime(
                HttpClient.newHttpClient(),
                java.util.List.of(new ProviderProfile("primary", URI.create("http://127.0.0.1:" + server.getAddress().getPort()))),
                Duration.ofSeconds(1),
                1_024,
                1_024,
                ExecutionPolicy.defaults())) {
            Web3PageSourceProvider provider = new Web3PageSourceProvider(
                    new EthereumBlockClient(rpcRuntime),
                    new EthereumTransactionClient(rpcRuntime));
            BlockRange range = new BlockRange(23000000, 23000000);
            io.trino.spi.connector.ConnectorPageSource pageSource = provider.createPageSource(
                    null,
                    null,
                    new Web3Split(range),
                    new Web3TableHandle("ethereum", "blocks", Optional.of(range)),
                    java.util.List.of(new Web3ColumnHandle("block_number", 0)),
                    null);

            pageSource.isBlocked().join();
            assertThat(pageSource.getMemoryUsage()).isPositive();
            assertThat(pageSource.getNextSourcePage()).isNotNull();
            assertThat(pageSource.getMemoryUsage()).isZero();
            assertThat(pageSource.getMetrics().getMetrics()).containsOnlyKeys(
                    Web3Metrics.RPC_REQUESTS,
                    Web3Metrics.RPC_FAILURES,
                    Web3Metrics.RPC_RETRIES,
                    Web3Metrics.RPC_THROTTLED,
                    Web3Metrics.RPC_IN_FLIGHT,
                    Web3Metrics.RPC_FAILOVERS,
                    Web3Metrics.RPC_LATENCY_NANOS,
                    Web3Metrics.RPC_BATCHES,
                    Web3Metrics.RPC_BATCH_ITEMS,
                    Web3Metrics.CACHE_HITS,
                    Web3Metrics.CACHE_MISSES,
                    Web3Metrics.CACHE_REVALIDATIONS,
                    Web3Metrics.CACHE_BYTES_READ,
                    Web3Metrics.CACHE_BYTES_WRITTEN);
            assertThat(((io.trino.spi.metrics.Count<?>) pageSource.getMetrics().getMetrics().get(Web3Metrics.RPC_REQUESTS)).getTotal()).isEqualTo(1);
            assertThat(((io.trino.spi.metrics.Count<?>) pageSource.getMetrics().getMetrics().get(Web3Metrics.RPC_BATCHES)).getTotal()).isEqualTo(1);
            assertThat(((io.trino.spi.metrics.Count<?>) pageSource.getMetrics().getMetrics().get(Web3Metrics.RPC_BATCH_ITEMS)).getTotal()).isEqualTo(1);
            assertThat(((io.trino.spi.metrics.Count<?>) pageSource.getMetrics().getMetrics().get(Web3Metrics.RPC_IN_FLIGHT)).getTotal()).isZero();
        }
    }

    @Test
    public void testNonBatchProviderUsesSingleRequestEnvelope()
            throws Exception
    {
        Session session = testSessionBuilder().setCatalog("web3").setSchema("ethereum").build();
        try (DistributedQueryRunner queryRunner = DistributedQueryRunner.builder(session).setWorkerCount(1).build()) {
            queryRunner.installPlugin(new Web3Plugin());
            queryRunner.createCatalog("web3", Web3ConnectorFactory.CONNECTOR_NAME, Map.of(
                    "web3.ethereum.rpc-url", "http://127.0.0.1:" + server.getAddress().getPort(),
                    "web3.rpc.json-rpc-batch-enabled", "false"));

            assertThat(queryRunner.execute("SELECT block_number FROM web3.ethereum.blocks WHERE block_number BETWEEN 23000000 AND 23000001").getOnlyColumn())
                    .containsExactlyInAnyOrder(23000000L, 23000001L);
            assertThat(rpcRequestCount).hasValue(2);
        }
    }

    private void handleRequest(HttpExchange exchange)
            throws IOException
    {
        rpcRequestCount.incrementAndGet();
        if (responseDelay != null) {
            try {
                Thread.sleep(responseDelay);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        JsonNode requestDocument = OBJECT_MAPPER.readTree(exchange.getRequestBody());
        Iterable<JsonNode> requests = requestDocument.isArray() ? requestDocument : java.util.List.of(requestDocument);
        ArrayNode responses = OBJECT_MAPPER.createArrayNode();
        for (JsonNode request : requests) {
            ObjectNode response = OBJECT_MAPPER.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.put("id", request.path("id").asLong());
            if (request.path("method").asText().equals("eth_chainId")) {
                response.put("result", "0x1");
                responses.insert(0, response);
                continue;
            }
            String quantity = request.path("params").get(0).asText();
            long blockNumber = Long.parseUnsignedLong(quantity.substring(2), 16);
            ObjectNode block = response.putObject("result");
            block.put("number", quantity);
            block.put("hash", "0x" + Long.toHexString(blockNumber));
            block.put("futureBlockField", "present");
            if (request.path("params").get(1).asBoolean()) {
                ArrayNode transactions = block.putArray("transactions");
                ObjectNode transaction = transactions.addObject();
                transaction.put("hash", "0xtx" + Long.toHexString(blockNumber));
                transaction.put("blockNumber", quantity);
                transaction.put("from", "0xfrom");
                transaction.put("to", "0xto");
                transaction.put("futureTransactionField", "present");
            }
            responses.insert(0, response);
        }
        byte[] body = OBJECT_MAPPER.writeValueAsBytes(requestDocument.isArray() ? responses : responses.get(0));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static void writeChainId(HttpExchange exchange, JsonNode requestDocument)
            throws IOException
    {
        JsonNode request = requestDocument.isArray() ? requestDocument.get(0) : requestDocument;
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", request.path("id").asLong());
        response.put("result", "0x1");
        JsonNode responseDocument = requestDocument.isArray() ? OBJECT_MAPPER.createArrayNode().add(response) : response;
        byte[] body = OBJECT_MAPPER.writeValueAsBytes(responseDocument);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
