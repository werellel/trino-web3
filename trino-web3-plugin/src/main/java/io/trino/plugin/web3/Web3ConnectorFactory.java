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

import io.trino.spi.connector.Connector;
import io.trino.spi.connector.ConnectorContext;
import io.trino.spi.connector.ConnectorFactory;
import io.trino.plugin.web3.runtime.ExecutionPolicy;
import io.airlift.units.DataSize;
import io.trino.plugin.web3.runtime.RemoteCacheConfig;

import java.time.Duration;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

public final class Web3ConnectorFactory
        implements ConnectorFactory
{
    public static final String CONNECTOR_NAME = "web3";
    private static final String ETHEREUM_RPC_URL = "web3.ethereum.rpc-url";
    private static final String ETHEREUM_RPC_FALLBACK_URLS = "web3.ethereum.rpc-fallback-urls";
    private static final String SOLANA_RPC_URL = "web3.solana.rpc-url";
    private static final String SOLANA_RPC_FALLBACK_URLS = "web3.solana.rpc-fallback-urls";
    private static final String APTOS_REST_URL = "web3.aptos.rest-url";
    private static final String APTOS_REST_FALLBACK_URLS = "web3.aptos.rest-fallback-urls";
    private static final String MAXIMUM_BLOCKS_PER_SPLIT = "web3.maximum-blocks-per-split";
    private static final String MAXIMUM_BLOCKS_PER_QUERY = "web3.maximum-blocks-per-query";
    private static final String MAXIMUM_TRANSACTION_HASHES_PER_QUERY = "web3.maximum-transaction-hashes-per-query";
    private static final String MAXIMUM_RPC_REQUEST_BYTES = "web3.maximum-rpc-request-bytes";
    private static final String MAXIMUM_RPC_RESPONSE_BYTES = "web3.maximum-rpc-response-bytes";
    private static final String MAXIMUM_RPC_CONCURRENCY = "web3.rpc.maximum-concurrency";
    private static final String MAXIMUM_RPC_QUEUE_SIZE = "web3.rpc.maximum-queue-size";
    private static final String MAXIMUM_RPC_BATCH_SIZE = "web3.rpc.maximum-batch-size";
    private static final String MAXIMUM_RPC_ATTEMPTS = "web3.rpc.maximum-attempts";
    private static final String RPC_REQUESTS_PER_SECOND = "web3.rpc.requests-per-second";
    private static final String RPC_INITIAL_BACKOFF_MILLIS = "web3.rpc.initial-backoff-millis";
    private static final String RPC_MAXIMUM_BACKOFF_MILLIS = "web3.rpc.maximum-backoff-millis";
    private static final String RPC_PROVIDER_COOLDOWN_MILLIS = "web3.rpc.provider-cooldown-millis";
    private static final String RPC_JSON_RPC_BATCH_ENABLED = "web3.rpc.json-rpc-batch-enabled";
    private static final String CACHE_ENABLED = "web3.cache.enabled";
    private static final String CACHE_MAXIMUM_SIZE = "web3.cache.maximum-size";
    private static final String CACHE_MAXIMUM_ENTRY_SIZE = "web3.cache.maximum-entry-size";
    private static final String CACHE_TTL = "web3.cache.ttl";
    private static final long DEFAULT_MAXIMUM_BLOCKS_PER_SPLIT = 100;
    private static final long DEFAULT_MAXIMUM_BLOCKS_PER_QUERY = 10_000;
    private static final int DEFAULT_MAXIMUM_TRANSACTION_HASHES_PER_QUERY = 1_000;
    private static final int DEFAULT_MAXIMUM_RPC_REQUEST_BYTES = 1_048_576;
    private static final int DEFAULT_MAXIMUM_RPC_RESPONSE_BYTES = 16 * 1_048_576;

    @Override
    public String getName()
    {
        return CONNECTOR_NAME;
    }

    @Override
    public Connector create(String catalogName, Map<String, String> config, ConnectorContext context)
    {
        requireNonNull(catalogName, "catalogName is null");
        requireNonNull(config, "config is null");
        requireNonNull(context, "context is null");

        if (!config.keySet().stream().allMatch(Web3ConnectorFactory::isSupportedProperty)) {
            throw new IllegalArgumentException("Unsupported Web3 connector configuration property");
        }

        List<URI> ethereumEndpoints = parseEndpoints(config, ETHEREUM_RPC_URL, ETHEREUM_RPC_FALLBACK_URLS, false);
        if (ethereumEndpoints.size() > 8) {
            throw new IllegalArgumentException("web3.ethereum.rpc-url and fallback URLs must contain at most 8 endpoints");
        }
        List<URI> solanaEndpoints = parseEndpoints(config, SOLANA_RPC_URL, SOLANA_RPC_FALLBACK_URLS, false);
        if (solanaEndpoints.size() > 8) {
            throw new IllegalArgumentException("web3.solana.rpc-url and fallback URLs must contain at most 8 endpoints");
        }
        List<URI> aptosEndpoints = parseEndpoints(config, APTOS_REST_URL, APTOS_REST_FALLBACK_URLS, true);
        if (aptosEndpoints.size() > 8) {
            throw new IllegalArgumentException("web3.aptos.rest-url and fallback URLs must contain at most 8 endpoints");
        }
        long maximumBlocksPerSplit = Optional.ofNullable(config.get(MAXIMUM_BLOCKS_PER_SPLIT))
                .map(value -> parseBoundedPositiveLong(value, MAXIMUM_BLOCKS_PER_SPLIT, 1_000))
                .orElse(DEFAULT_MAXIMUM_BLOCKS_PER_SPLIT);
        long maximumBlocksPerQuery = Optional.ofNullable(config.get(MAXIMUM_BLOCKS_PER_QUERY))
                .map(value -> parseBoundedPositiveLong(value, MAXIMUM_BLOCKS_PER_QUERY, 10_000))
                .orElse(DEFAULT_MAXIMUM_BLOCKS_PER_QUERY);
        int maximumTransactionHashesPerQuery = parseConfiguredLong(
                config,
                MAXIMUM_TRANSACTION_HASHES_PER_QUERY,
                DEFAULT_MAXIMUM_TRANSACTION_HASHES_PER_QUERY,
                10_000);
        int maximumRequestBytes = Optional.ofNullable(config.get(MAXIMUM_RPC_REQUEST_BYTES))
                .map(value -> Math.toIntExact(parseBoundedPositiveLong(value, MAXIMUM_RPC_REQUEST_BYTES, 1_048_576)))
                .orElse(DEFAULT_MAXIMUM_RPC_REQUEST_BYTES);
        int maximumResponseBytes = Optional.ofNullable(config.get(MAXIMUM_RPC_RESPONSE_BYTES))
                .map(value -> Math.toIntExact(parseBoundedPositiveLong(value, MAXIMUM_RPC_RESPONSE_BYTES, 64 * 1_048_576L)))
                .orElse(DEFAULT_MAXIMUM_RPC_RESPONSE_BYTES);
        ExecutionPolicy defaults = ExecutionPolicy.defaults();
        ExecutionPolicy executionPolicy = new ExecutionPolicy(
                parseConfiguredLong(config, MAXIMUM_RPC_CONCURRENCY, defaults.maximumConcurrency(), 64),
                parseConfiguredLong(config, MAXIMUM_RPC_QUEUE_SIZE, defaults.maximumQueueSize(), 4_096),
                parseConfiguredLong(config, MAXIMUM_RPC_BATCH_SIZE, defaults.maximumBatchSize(), 100),
                parseConfiguredLong(config, MAXIMUM_RPC_ATTEMPTS, defaults.maximumAttempts(), 5),
                parseConfiguredLong(config, RPC_REQUESTS_PER_SECOND, defaults.requestsPerSecond(), 10_000),
                Duration.ofMillis(parseConfiguredLong(config, RPC_INITIAL_BACKOFF_MILLIS, defaults.initialBackoff().toMillis(), 30_000)),
                Duration.ofMillis(parseConfiguredLong(config, RPC_MAXIMUM_BACKOFF_MILLIS, defaults.maximumBackoff().toMillis(), 30_000)),
                Duration.ofMillis(parseConfiguredLong(config, RPC_PROVIDER_COOLDOWN_MILLIS, defaults.providerCooldown().toMillis(), 30_000)));
        boolean jsonRpcBatchEnabled = Optional.ofNullable(config.get(RPC_JSON_RPC_BATCH_ENABLED))
                .map(value -> parseBoolean(value, RPC_JSON_RPC_BATCH_ENABLED))
                .orElse(true);
        boolean cacheEnabled = Optional.ofNullable(config.get(CACHE_ENABLED))
                .map(value -> parseBoolean(value, CACHE_ENABLED))
                .orElse(false);
        long cacheMaximumSize = Optional.ofNullable(config.get(CACHE_MAXIMUM_SIZE))
                .map(value -> parseDataSize(value, CACHE_MAXIMUM_SIZE, 1_048_576, 1_073_741_824))
                .orElse(128L * 1_048_576);
        int cacheMaximumEntrySize = Math.toIntExact(Optional.ofNullable(config.get(CACHE_MAXIMUM_ENTRY_SIZE))
                .map(value -> parseDataSize(value, CACHE_MAXIMUM_ENTRY_SIZE, 1_024, 1_073_741_824))
                .orElse(8L * 1_048_576));
        if (cacheEnabled && cacheMaximumEntrySize > cacheMaximumSize) {
            throw new IllegalArgumentException("web3.cache.maximum-entry-size must not exceed web3.cache.maximum-size");
        }
        if (cacheEnabled && cacheMaximumEntrySize > maximumResponseBytes) {
            throw new IllegalArgumentException("web3.cache.maximum-entry-size must not exceed web3.maximum-rpc-response-bytes");
        }
        Optional<Duration> cacheTtl = Optional.ofNullable(config.get(CACHE_TTL))
                .map(value -> parseDuration(value, CACHE_TTL));
        RemoteCacheConfig cacheConfig = new RemoteCacheConfig(cacheEnabled, cacheMaximumSize, cacheMaximumEntrySize, cacheTtl);
        return new Web3Connector(
                maximumBlocksPerSplit,
                maximumBlocksPerQuery,
                maximumTransactionHashesPerQuery,
                maximumRequestBytes,
                maximumResponseBytes,
                ethereumEndpoints,
                solanaEndpoints,
                aptosEndpoints,
                jsonRpcBatchEnabled,
                executionPolicy,
                cacheConfig,
                context.getTypeManager());
    }

    private static boolean isSupportedProperty(String key)
    {
        return key.equals(ETHEREUM_RPC_URL) ||
                key.equals(ETHEREUM_RPC_FALLBACK_URLS) ||
                key.equals(SOLANA_RPC_URL) ||
                key.equals(SOLANA_RPC_FALLBACK_URLS) ||
                key.equals(APTOS_REST_URL) ||
                key.equals(APTOS_REST_FALLBACK_URLS) ||
                key.equals(MAXIMUM_BLOCKS_PER_SPLIT) ||
                key.equals(MAXIMUM_BLOCKS_PER_QUERY) ||
                key.equals(MAXIMUM_TRANSACTION_HASHES_PER_QUERY) ||
                key.equals(MAXIMUM_RPC_REQUEST_BYTES) ||
                key.equals(MAXIMUM_RPC_RESPONSE_BYTES) ||
                key.equals(MAXIMUM_RPC_CONCURRENCY) ||
                key.equals(MAXIMUM_RPC_QUEUE_SIZE) ||
                key.equals(MAXIMUM_RPC_BATCH_SIZE) ||
                key.equals(MAXIMUM_RPC_ATTEMPTS) ||
                key.equals(RPC_REQUESTS_PER_SECOND) ||
                key.equals(RPC_INITIAL_BACKOFF_MILLIS) ||
                key.equals(RPC_MAXIMUM_BACKOFF_MILLIS) ||
                key.equals(RPC_PROVIDER_COOLDOWN_MILLIS) ||
                key.equals(RPC_JSON_RPC_BATCH_ENABLED) ||
                key.equals(CACHE_ENABLED) ||
                key.equals(CACHE_MAXIMUM_SIZE) ||
                key.equals(CACHE_MAXIMUM_ENTRY_SIZE) ||
                key.equals(CACHE_TTL);
    }

    private static List<URI> parseEndpoints(
            Map<String, String> config,
            String primaryProperty,
            String fallbackProperty,
            boolean requireOrigin)
    {
        return Stream.concat(
                        Optional.ofNullable(config.get(primaryProperty))
                                .map(value -> parseEndpoint(value, primaryProperty, requireOrigin))
                                .stream(),
                        Optional.ofNullable(config.get(fallbackProperty))
                                .stream()
                                .flatMap(value -> Stream.of(value.split(",")))
                                .map(String::trim)
                                .filter(value -> !value.isEmpty())
                                .map(value -> parseEndpoint(value, fallbackProperty, requireOrigin)))
                .toList();
    }

    private static URI parseEndpoint(String value, String propertyName, boolean requireOrigin)
    {
        URI uri = parseHttpUri(value, propertyName);
        String path = uri.getRawPath();
        if (requireOrigin && (uri.getRawUserInfo() != null || uri.getRawQuery() != null || !(path.isEmpty() || path.equals("/")))) {
            throw new IllegalArgumentException(propertyName + " must contain HTTP(S) origins without credentials, path, query, or fragment");
        }
        return uri;
    }

    private static URI parseHttpUri(String value, String propertyName)
    {
        URI uri;
        try {
            uri = URI.create(value);
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(propertyName + " contains an invalid URL", e.getCause());
        }
        if (!uri.isAbsolute() || !(uri.getScheme().equals("http") || uri.getScheme().equals("https")) || uri.getHost() == null || uri.getFragment() != null) {
            throw new IllegalArgumentException(propertyName + " must contain absolute HTTP(S) URLs without fragments");
        }
        return uri;
    }

    private static int parseConfiguredLong(Map<String, String> config, String propertyName, long defaultValue, long maximum)
    {
        return Math.toIntExact(Optional.ofNullable(config.get(propertyName))
                .map(value -> parseBoundedPositiveLong(value, propertyName, maximum))
                .orElse(defaultValue));
    }

    private static long parseBoundedPositiveLong(String value, String propertyName, long maximum)
    {
        long parsed = Long.parseLong(value);
        if (parsed < 1 || parsed > maximum) {
            throw new IllegalArgumentException(propertyName + " must be between 1 and " + maximum);
        }
        return parsed;
    }

    private static boolean parseBoolean(String value, String propertyName)
    {
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException(propertyName + " must be true or false");
    }

    private static long parseDataSize(String value, String propertyName, long minimumBytes, long maximumBytes)
    {
        long bytes;
        try {
            bytes = DataSize.valueOf(value).toBytes();
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(propertyName + " must contain a valid data size", e);
        }
        if (bytes < minimumBytes || bytes > maximumBytes) {
            throw new IllegalArgumentException(propertyName + " must be between " + DataSize.ofBytes(minimumBytes) + " and " + DataSize.ofBytes(maximumBytes));
        }
        return bytes;
    }

    private static Duration parseDuration(String value, String propertyName)
    {
        Duration duration;
        try {
            duration = io.airlift.units.Duration.valueOf(value).toJavaTime();
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(propertyName + " must contain a valid duration", e);
        }
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(propertyName + " must be positive");
        }
        return duration;
    }
}
