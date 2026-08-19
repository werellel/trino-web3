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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestEthereumBlockCache
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final long BLOCK_NUMBER = 10;

    private final AtomicInteger blockDataRequests = new AtomicInteger();
    private final AtomicInteger finalityRequests = new AtomicInteger();
    private final AtomicReference<String> canonicalHash = new AtomicReference<>(hash('a'));
    private HttpServer server;
    private ExecutorService serverExecutor;
    private long safeBlock = BLOCK_NUMBER;
    private long finalizedBlock = BLOCK_NUMBER;
    private boolean finalitySupported = true;
    private boolean malformedBlock;
    private boolean missingBlock;
    private boolean partialBatchFailure;
    private int blockHttpStatus;
    private Duration blockResponseDelay;
    private CountDownLatch requestStarted;
    private CountDownLatch releaseRequest;

    @BeforeEach
    public void setUp()
            throws IOException
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newFixedThreadPool(4);
        server.setExecutor(serverExecutor);
        server.createContext("/", this::handleRequest);
        server.start();
    }

    @AfterEach
    public void tearDown()
    {
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    @Test
    public void testFinalizedBlockUsesCanonicalHashCache()
    {
        try (RemoteExecutionRuntime runtime = runtime()) {
            EthereumBlockClient client = new EthereumBlockClient(runtime);

            assertThat(client.getBlocks(new BlockRange(BLOCK_NUMBER, BLOCK_NUMBER)).future().join())
                    .containsExactly(new EthereumBlockClient.EthereumBlock(BLOCK_NUMBER, hash('a')));
            RemoteExecution<List<EthereumBlockClient.EthereumBlock>> warm = client.getBlocks(new BlockRange(BLOCK_NUMBER, BLOCK_NUMBER));
            assertThat(warm.future().join())
                    .containsExactly(new EthereumBlockClient.EthereumBlock(BLOCK_NUMBER, hash('a')));

            assertThat(blockDataRequests).hasValue(1);
            assertThat(finalityRequests).hasValue(2);
            assertThat(warm.metrics().cacheHitCount()).isEqualTo(2);
            assertThat(warm.metrics().requestCount()).isZero();
            assertThat(runtime.cacheMetrics().entryCount()).isEqualTo(2);
        }
    }

    @Test
    public void testFinalitySnapshotExpiresAndIsRefreshed()
    {
        AtomicLong nanoTime = new AtomicLong();
        EthereumFinalityResolver resolver = new EthereumFinalityResolver(Duration.ofSeconds(1), nanoTime::get);
        try (RemoteExecutionRuntime runtime = runtime()) {
            EthereumBlockClient client = new EthereumBlockClient(runtime, resolver);

            assertThat(client.getBlocks(new BlockRange(BLOCK_NUMBER, BLOCK_NUMBER)).future().join()).hasSize(1);
            assertThat(client.getBlocks(new BlockRange(BLOCK_NUMBER, BLOCK_NUMBER)).future().join()).hasSize(1);
            assertThat(finalityRequests).hasValue(2);

            nanoTime.addAndGet(Duration.ofSeconds(1).toNanos());
            assertThat(client.getBlocks(new BlockRange(BLOCK_NUMBER, BLOCK_NUMBER)).future().join()).hasSize(1);

            assertThat(finalityRequests).hasValue(4);
            assertThat(blockDataRequests).hasValue(1);
        }
    }

    @Test
    public void testFinalityWorksWithMaximumBatchSizeOne()
    {
        ExecutionPolicy defaults = ExecutionPolicy.defaults();
        ExecutionPolicy singleItemBatches = new ExecutionPolicy(
                defaults.maximumConcurrency(),
                defaults.maximumQueueSize(),
                1,
                defaults.maximumAttempts(),
                defaults.requestsPerSecond(),
                defaults.initialBackoff(),
                defaults.maximumBackoff(),
                defaults.providerCooldown());
        try (RemoteExecutionRuntime runtime = runtime(Duration.ofSeconds(1), singleItemBatches)) {
            EthereumBlockClient client = new EthereumBlockClient(runtime);

            assertThat(client.getBlocks(new BlockRange(BLOCK_NUMBER, BLOCK_NUMBER)).future().join()).hasSize(1);
            assertThat(client.getBlocks(new BlockRange(BLOCK_NUMBER, BLOCK_NUMBER)).future().join()).hasSize(1);

            assertThat(finalityRequests).hasValue(2);
            assertThat(blockDataRequests).hasValue(1);
        }
    }

    @Test
    public void testNearHeadReorgRevalidatesBlockNumber()
    {
        safeBlock = BLOCK_NUMBER - 1;
        finalizedBlock = BLOCK_NUMBER - 1;
        try (RemoteExecutionRuntime runtime = runtime()) {
            EthereumBlockClient client = new EthereumBlockClient(runtime);

            assertThat(client.getBlocks(new BlockRange(BLOCK_NUMBER, BLOCK_NUMBER)).future().join().getFirst().hash()).isEqualTo(hash('a'));
            canonicalHash.set(hash('b'));
            RemoteExecution<List<EthereumBlockClient.EthereumBlock>> reorged = client.getBlocks(new BlockRange(BLOCK_NUMBER, BLOCK_NUMBER));

            assertThat(reorged.future().join().getFirst().hash()).isEqualTo(hash('b'));
            assertThat(blockDataRequests).hasValue(2);
            assertThat(reorged.metrics().cacheRevalidationCount()).isOne();
        }
    }

    @Test
    public void testUnsupportedFinalityFallsBackToHead()
    {
        finalitySupported = false;
        try (RemoteExecutionRuntime runtime = runtime()) {
            EthereumBlockClient client = new EthereumBlockClient(runtime);

            assertThat(client.getBlocks(new BlockRange(BLOCK_NUMBER, BLOCK_NUMBER)).future().join()).hasSize(1);
            assertThat(client.getBlocks(new BlockRange(BLOCK_NUMBER, BLOCK_NUMBER)).future().join()).hasSize(1);

            assertThat(blockDataRequests).hasValue(2);
            assertThat(finalityRequests).hasValue(2);
        }
    }

    @Test
    public void testInvertedFinalityFallsBackToHead()
    {
        safeBlock = BLOCK_NUMBER - 1;
        finalizedBlock = BLOCK_NUMBER;
        try (RemoteExecutionRuntime runtime = runtime()) {
            EthereumBlockClient client = new EthereumBlockClient(runtime);

            assertThat(client.getBlocks(new BlockRange(BLOCK_NUMBER, BLOCK_NUMBER)).future().join()).hasSize(1);
            assertThat(client.getBlocks(new BlockRange(BLOCK_NUMBER, BLOCK_NUMBER)).future().join()).hasSize(1);

            assertThat(blockDataRequests).hasValue(2);
            assertThat(runtime.cacheMetrics().entryCount()).isOne();
        }
    }

    @Test
    public void testInvalidPayloadIsNotAdmitted()
    {
        malformedBlock = true;
        try (RemoteExecutionRuntime runtime = runtime()) {
            EthereumBlockClient client = new EthereumBlockClient(runtime);

            assertThatThrownBy(() -> client.getBlocks(new BlockRange(BLOCK_NUMBER, BLOCK_NUMBER)).future().join())
                    .hasRootCauseMessage("Ethereum block hash is not a 32-byte hexadecimal hash");
            assertThatThrownBy(() -> client.getBlocks(new BlockRange(BLOCK_NUMBER, BLOCK_NUMBER)).future().join())
                    .hasRootCauseMessage("Ethereum block hash is not a 32-byte hexadecimal hash");

            assertThat(blockDataRequests).hasValue(2);
            assertThat(runtime.cacheMetrics().entryCount()).isZero();
        }
    }

    @Test
    public void testMissingBlockIsNotNegativeCached()
    {
        missingBlock = true;
        try (RemoteExecutionRuntime runtime = runtime()) {
            EthereumBlockClient client = new EthereumBlockClient(runtime);

            assertThatThrownBy(() -> client.getBlocks(new BlockRange(BLOCK_NUMBER, BLOCK_NUMBER)).future().join())
                    .hasRootCauseMessage("Ethereum block is missing for 10");
            assertThatThrownBy(() -> client.getBlocks(new BlockRange(BLOCK_NUMBER, BLOCK_NUMBER)).future().join())
                    .hasRootCauseMessage("Ethereum block is missing for 10");

            assertThat(blockDataRequests).hasValue(2);
            assertThat(runtime.cacheMetrics().entryCount()).isZero();
        }
    }

    @Test
    public void testHttp429IsNotCached()
    {
        blockHttpStatus = 429;
        try (RemoteExecutionRuntime runtime = runtime(Duration.ofSeconds(1), singleAttemptPolicy())) {
            EthereumBlockClient client = new EthereumBlockClient(runtime);

            assertThatThrownBy(() -> client.getBlocks(new BlockRange(BLOCK_NUMBER, BLOCK_NUMBER)).future().join())
                    .hasRootCauseMessage("JSON-RPC endpoint returned HTTP 429");
            assertThatThrownBy(() -> client.getBlocks(new BlockRange(BLOCK_NUMBER, BLOCK_NUMBER)).future().join())
                    .hasRootCauseMessage("JSON-RPC endpoint returned HTTP 429");

            assertThat(blockDataRequests).hasValue(2);
            assertThat(runtime.cacheMetrics().entryCount()).isZero();
        }
    }

    @Test
    public void testTimeoutIsNotCached()
    {
        blockResponseDelay = Duration.ofMillis(150);
        try (RemoteExecutionRuntime runtime = runtime(Duration.ofMillis(50), singleAttemptPolicy())) {
            EthereumBlockClient client = new EthereumBlockClient(runtime);

            assertThatThrownBy(() -> client.getBlocks(new BlockRange(BLOCK_NUMBER, BLOCK_NUMBER)).future().join())
                    .hasRootCauseInstanceOf(java.net.http.HttpTimeoutException.class);
            assertThatThrownBy(() -> client.getBlocks(new BlockRange(BLOCK_NUMBER, BLOCK_NUMBER)).future().join())
                    .hasRootCauseInstanceOf(java.net.http.HttpTimeoutException.class);

            assertThat(blockDataRequests).hasValue(2);
            assertThat(runtime.cacheMetrics().entryCount()).isZero();
        }
    }

    @Test
    public void testPartialBatchFailureAdmitsNothing()
    {
        partialBatchFailure = true;
        try (RemoteExecutionRuntime runtime = runtime(Duration.ofSeconds(1), singleAttemptPolicy())) {
            EthereumBlockClient client = new EthereumBlockClient(runtime);
            BlockRange range = new BlockRange(BLOCK_NUMBER, BLOCK_NUMBER + 1);

            assertThatThrownBy(() -> client.getBlocks(range).future().join())
                    .hasRootCauseMessage("JSON-RPC endpoint returned error code -32602 for id 1");
            assertThatThrownBy(() -> client.getBlocks(range).future().join())
                    .hasRootCauseMessage("JSON-RPC endpoint returned error code -32602 for id 1");

            assertThat(blockDataRequests).hasValue(4);
            assertThat(runtime.cacheMetrics().entryCount()).isZero();
        }
    }

    @Test
    public void testCancellationDuringFinalityResolutionDoesNotAdmitCacheEntry()
            throws Exception
    {
        requestStarted = new CountDownLatch(1);
        releaseRequest = new CountDownLatch(1);
        try (RemoteExecutionRuntime runtime = runtime()) {
            EthereumBlockClient client = new EthereumBlockClient(runtime);
            RemoteExecution<List<EthereumBlockClient.EthereumBlock>> execution = client.getBlocks(new BlockRange(BLOCK_NUMBER, BLOCK_NUMBER));
            assertThat(requestStarted.await(1, TimeUnit.SECONDS)).isTrue();

            assertThat(execution.future().cancel(true)).isTrue();
            releaseRequest.countDown();

            assertThat(execution.future()).isCancelled();
            assertThat(runtime.cacheMetrics().entryCount()).isZero();
        }
        finally {
            releaseRequest.countDown();
        }
    }

    private RemoteExecutionRuntime runtime()
    {
        return runtime(Duration.ofSeconds(1), ExecutionPolicy.defaults());
    }

    private RemoteExecutionRuntime runtime(Duration requestTimeout, ExecutionPolicy policy)
    {
        return new RemoteExecutionRuntime(
                HttpClient.newHttpClient(),
                List.of(new ProviderProfile("primary", URI.create("http://127.0.0.1:" + server.getAddress().getPort()))),
                requestTimeout,
                4_096,
                64 * 1_024,
                policy,
                new RemoteCacheConfig(true, 1_048_576, 64 * 1_024, Optional.empty()));
    }

    private static ExecutionPolicy singleAttemptPolicy()
    {
        ExecutionPolicy defaults = ExecutionPolicy.defaults();
        return new ExecutionPolicy(
                defaults.maximumConcurrency(),
                defaults.maximumQueueSize(),
                defaults.maximumBatchSize(),
                1,
                defaults.requestsPerSecond(),
                defaults.initialBackoff(),
                defaults.maximumBackoff(),
                defaults.providerCooldown());
    }

    private void handleRequest(HttpExchange exchange)
            throws IOException
    {
        JsonNode requestDocument = OBJECT_MAPPER.readTree(exchange.getRequestBody());
        if (requestStarted != null) {
            requestStarted.countDown();
            try {
                if (!releaseRequest.await(1, TimeUnit.SECONDS)) {
                    throw new IOException("test request was not released");
                }
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }
        List<JsonNode> requests = new ArrayList<>();
        if (requestDocument.isArray()) {
            requestDocument.forEach(requests::add);
        }
        else {
            requests.add(requestDocument);
        }
        long dataRequestCount = requests.stream().filter(TestEthereumBlockCache::isDataRequest).count();
        if (dataRequestCount > 0 && blockHttpStatus != 0) {
            blockDataRequests.addAndGet(Math.toIntExact(dataRequestCount));
            exchange.sendResponseHeaders(blockHttpStatus, -1);
            exchange.close();
            return;
        }
        if (dataRequestCount > 0 && blockResponseDelay != null) {
            blockDataRequests.addAndGet(Math.toIntExact(dataRequestCount));
            try {
                Thread.sleep(blockResponseDelay);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }
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
        if ((identifier.equals("safe") || identifier.equals("finalized")) && !finalitySupported) {
            finalityRequests.incrementAndGet();
            response.putObject("error").put("code", -39001).put("message", "Unknown block");
            return response;
        }
        if (identifier.equals("safe") || identifier.equals("finalized")) {
            finalityRequests.incrementAndGet();
            long number = identifier.equals("safe") ? safeBlock : finalizedBlock;
            response.set("result", block(number, hash(identifier.equals("safe") ? 's' : 'f')));
            return response;
        }
        if (!method.equals("eth_getBlockByNumber") && !method.equals("eth_getBlockByHash")) {
            response.putObject("error").put("code", -32601).put("message", "Method not found");
            return response;
        }
        if (blockResponseDelay == null) {
            blockDataRequests.incrementAndGet();
        }
        if (partialBatchFailure && identifier.equals("0xb")) {
            response.putObject("error").put("code", -32602).put("message", "Invalid params");
            return response;
        }
        if (missingBlock) {
            response.putNull("result");
        }
        else {
            long requestedBlock = method.equals("eth_getBlockByHash") ? BLOCK_NUMBER : Long.parseUnsignedLong(identifier.substring(2), 16);
            String requestedHash = requestedBlock == BLOCK_NUMBER ? canonicalHash.get() : hash('b');
            response.set("result", block(requestedBlock, malformedBlock ? "0xinvalid" : requestedHash));
        }
        return response;
    }

    private static boolean isDataRequest(JsonNode request)
    {
        String identifier = request.path("params").get(0).asText();
        return !identifier.equals("safe") && !identifier.equals("finalized");
    }

    private static ObjectNode block(long number, String blockHash)
    {
        return OBJECT_MAPPER.createObjectNode()
                .put("number", "0x" + Long.toHexString(number))
                .put("hash", blockHash);
    }

    private static String hash(char value)
    {
        return "0x" + String.valueOf(value).repeat(64);
    }
}
