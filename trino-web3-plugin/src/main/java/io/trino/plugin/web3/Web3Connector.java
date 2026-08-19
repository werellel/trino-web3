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

import io.trino.plugin.web3.adapter.ExecutableChainRegistry;
import io.trino.plugin.web3.evm.EthereumChainAdapter;
import io.trino.plugin.web3.runtime.ExecutionPolicy;
import io.trino.plugin.web3.runtime.ProviderCapabilities;
import io.trino.plugin.web3.runtime.ProviderProfile;
import io.trino.plugin.web3.runtime.RemoteExecutionRuntime;
import io.trino.plugin.web3.runtime.RemoteCacheConfig;
import io.trino.spi.connector.Connector;
import io.trino.spi.connector.ConnectorMetadata;
import io.trino.spi.connector.ConnectorPageSourceProvider;
import io.trino.spi.connector.ConnectorSplitManager;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorTransactionHandle;
import io.trino.spi.transaction.IsolationLevel;
import io.trino.spi.type.TypeManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

public final class Web3Connector
        implements Connector
{
    private final ConnectorMetadata metadata;
    private final ConnectorSplitManager splitManager;
    private final ConnectorPageSourceProvider pageSourceProvider;
    private final RemoteExecutionRuntime runtime;

    public Web3Connector()
    {
        this(
                100,
                10_000,
                1_000,
                1_048_576,
                16 * 1_048_576,
                List.of(),
                true,
                ExecutionPolicy.defaults(),
                RemoteCacheConfig.disabled(),
                createComponents(1_000, Web3Metadata::resolveBuiltInType));
    }

    public Web3Connector(
            long maximumBlocksPerSplit,
            long maximumBlocksPerQuery,
            int maximumTransactionHashesPerQuery,
            int maximumRequestBytes,
            int maximumResponseBytes,
            List<URI> ethereumRpcEndpoints,
            boolean jsonRpcBatchEnabled,
            ExecutionPolicy executionPolicy,
            RemoteCacheConfig cacheConfig,
            TypeManager typeManager)
    {
        this(
                maximumBlocksPerSplit,
                maximumBlocksPerQuery,
                maximumTransactionHashesPerQuery,
                maximumRequestBytes,
                maximumResponseBytes,
                ethereumRpcEndpoints,
                jsonRpcBatchEnabled,
                executionPolicy,
                cacheConfig,
                createComponents(
                        maximumTransactionHashesPerQuery,
                        requireNonNull(typeManager, "typeManager is null")::fromSqlType));
    }

    private Web3Connector(
            long maximumBlocksPerSplit,
            long maximumBlocksPerQuery,
            int maximumTransactionHashesPerQuery,
            int maximumRequestBytes,
            int maximumResponseBytes,
            List<URI> ethereumRpcEndpoints,
            boolean jsonRpcBatchEnabled,
            ExecutionPolicy executionPolicy,
            RemoteCacheConfig cacheConfig,
            ConnectorComponents components)
    {
        requireNonNull(components, "components is null");
        metadata = components.metadata();
        splitManager = new Web3SplitManager(
                maximumBlocksPerSplit,
                maximumBlocksPerQuery,
                maximumTransactionHashesPerQuery,
                components.adapters());
        runtime = ethereumRpcEndpoints.isEmpty() ? null : createRuntime(ethereumRpcEndpoints, maximumRequestBytes, maximumResponseBytes, jsonRpcBatchEnabled, executionPolicy, cacheConfig);
        pageSourceProvider = Optional.ofNullable(runtime)
                .<ConnectorPageSourceProvider>map(value -> new Web3PageSourceProvider(components.adapters(), value, components.typeResolver()))
                .orElseGet(() -> (transaction, session, split, table, columns, dynamicFilter) -> {
                    throw new IllegalStateException("web3.ethereum.rpc-url must be configured before querying Ethereum data");
                });
    }

    private static RemoteExecutionRuntime createRuntime(
            List<URI> endpoints,
            int maximumRequestBytes,
            int maximumResponseBytes,
            boolean jsonRpcBatchEnabled,
            ExecutionPolicy executionPolicy,
            RemoteCacheConfig cacheConfig)
    {
        return new RemoteExecutionRuntime(
                HttpClient.newHttpClient(),
                java.util.stream.IntStream.range(0, endpoints.size())
                        .mapToObj(index -> new ProviderProfile(
                                index == 0 ? "primary" : "fallback-" + index,
                                endpoints.get(index),
                                new ProviderCapabilities(jsonRpcBatchEnabled)))
                        .toList(),
                Duration.ofSeconds(10),
                maximumRequestBytes,
                maximumResponseBytes,
                executionPolicy,
                cacheConfig);
    }

    private static ConnectorComponents createComponents(int maximumTransactionHashesPerQuery, Function<String, io.trino.spi.type.Type> typeResolver)
    {
        ExecutableChainRegistry adapters = ExecutableChainRegistry.of(new EthereumChainAdapter());
        return new ConnectorComponents(
                adapters,
                new Web3Metadata(maximumTransactionHashesPerQuery, adapters.descriptors(), typeResolver),
                typeResolver);
    }

    @Override
    public ConnectorTransactionHandle beginTransaction(IsolationLevel isolationLevel, boolean readOnly, boolean autoCommit)
    {
        requireNonNull(isolationLevel, "isolationLevel is null");
        return Web3TransactionHandle.INSTANCE;
    }

    @Override
    public ConnectorMetadata getMetadata(ConnectorSession session, ConnectorTransactionHandle transactionHandle)
    {
        requireNonNull(session, "session is null");
        requireNonNull(transactionHandle, "transactionHandle is null");
        return metadata;
    }

    @Override
    public ConnectorSplitManager getSplitManager()
    {
        return splitManager;
    }

    @Override
    public ConnectorPageSourceProvider getPageSourceProvider()
    {
        return pageSourceProvider;
    }

    @Override
    public void shutdown()
    {
        if (runtime != null) {
            runtime.close();
        }
    }

    private record ConnectorComponents(
            ExecutableChainRegistry adapters,
            ConnectorMetadata metadata,
            Function<String, io.trino.spi.type.Type> typeResolver)
    {
        private ConnectorComponents
        {
            requireNonNull(adapters, "adapters is null");
            requireNonNull(metadata, "metadata is null");
            requireNonNull(typeResolver, "typeResolver is null");
        }
    }
}
