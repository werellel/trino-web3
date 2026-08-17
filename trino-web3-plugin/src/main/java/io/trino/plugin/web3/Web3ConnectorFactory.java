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

import java.net.URI;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public final class Web3ConnectorFactory
        implements ConnectorFactory
{
    public static final String CONNECTOR_NAME = "web3";
    private static final String ETHEREUM_RPC_URL = "web3.ethereum.rpc-url";
    private static final String MAXIMUM_BLOCKS_PER_SPLIT = "web3.maximum-blocks-per-split";
    private static final String MAXIMUM_BLOCKS_PER_QUERY = "web3.maximum-blocks-per-query";
    private static final String MAXIMUM_RPC_REQUEST_BYTES = "web3.maximum-rpc-request-bytes";
    private static final String MAXIMUM_RPC_RESPONSE_BYTES = "web3.maximum-rpc-response-bytes";
    private static final long DEFAULT_MAXIMUM_BLOCKS_PER_SPLIT = 100;
    private static final long DEFAULT_MAXIMUM_BLOCKS_PER_QUERY = 10_000;
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

        Optional<URI> endpoint = Optional.ofNullable(config.get(ETHEREUM_RPC_URL)).map(Web3ConnectorFactory::parseHttpUri);
        long maximumBlocksPerSplit = Optional.ofNullable(config.get(MAXIMUM_BLOCKS_PER_SPLIT))
                .map(value -> parseBoundedPositiveLong(value, MAXIMUM_BLOCKS_PER_SPLIT, 1_000))
                .orElse(DEFAULT_MAXIMUM_BLOCKS_PER_SPLIT);
        long maximumBlocksPerQuery = Optional.ofNullable(config.get(MAXIMUM_BLOCKS_PER_QUERY))
                .map(value -> parseBoundedPositiveLong(value, MAXIMUM_BLOCKS_PER_QUERY, 10_000))
                .orElse(DEFAULT_MAXIMUM_BLOCKS_PER_QUERY);
        int maximumRequestBytes = Optional.ofNullable(config.get(MAXIMUM_RPC_REQUEST_BYTES))
                .map(value -> Math.toIntExact(parseBoundedPositiveLong(value, MAXIMUM_RPC_REQUEST_BYTES, 1_048_576)))
                .orElse(DEFAULT_MAXIMUM_RPC_REQUEST_BYTES);
        int maximumResponseBytes = Optional.ofNullable(config.get(MAXIMUM_RPC_RESPONSE_BYTES))
                .map(value -> Math.toIntExact(parseBoundedPositiveLong(value, MAXIMUM_RPC_RESPONSE_BYTES, 64 * 1_048_576L)))
                .orElse(DEFAULT_MAXIMUM_RPC_RESPONSE_BYTES);
        return new Web3Connector(maximumBlocksPerSplit, maximumBlocksPerQuery, maximumRequestBytes, maximumResponseBytes, endpoint);
    }

    private static boolean isSupportedProperty(String key)
    {
        return key.equals(ETHEREUM_RPC_URL) ||
                key.equals(MAXIMUM_BLOCKS_PER_SPLIT) ||
                key.equals(MAXIMUM_BLOCKS_PER_QUERY) ||
                key.equals(MAXIMUM_RPC_REQUEST_BYTES) ||
                key.equals(MAXIMUM_RPC_RESPONSE_BYTES);
    }

    private static URI parseHttpUri(String value)
    {
        URI uri = URI.create(value);
        if (!uri.isAbsolute() || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
            throw new IllegalArgumentException(ETHEREUM_RPC_URL + " must be an absolute HTTP(S) URL");
        }
        return uri;
    }

    private static long parseBoundedPositiveLong(String value, String propertyName, long maximum)
    {
        long parsed = Long.parseLong(value);
        if (parsed < 1 || parsed > maximum) {
            throw new IllegalArgumentException(propertyName + " must be between 1 and " + maximum);
        }
        return parsed;
    }
}
