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

import io.trino.plugin.web3.evm.EthereumBlockClient;
import io.trino.plugin.web3.evm.EthereumTransactionClient;
import io.trino.plugin.web3.runtime.JsonRpcClient;
import io.trino.spi.connector.Connector;
import io.trino.spi.connector.ConnectorMetadata;
import io.trino.spi.connector.ConnectorPageSourceProvider;
import io.trino.spi.connector.ConnectorSplitManager;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorTransactionHandle;
import io.trino.spi.transaction.IsolationLevel;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public final class Web3Connector
        implements Connector
{
    private final ConnectorMetadata metadata = new Web3Metadata();
    private final ConnectorSplitManager splitManager;
    private final ConnectorPageSourceProvider pageSourceProvider;

    public Web3Connector()
    {
        this(100, 10_000, 1_048_576, 16 * 1_048_576, Optional.empty());
    }

    public Web3Connector(
            long maximumBlocksPerSplit,
            long maximumBlocksPerQuery,
            int maximumRequestBytes,
            int maximumResponseBytes,
            Optional<URI> ethereumRpcEndpoint)
    {
        splitManager = new Web3SplitManager(maximumBlocksPerSplit, maximumBlocksPerQuery);
        pageSourceProvider = ethereumRpcEndpoint
                .<ConnectorPageSourceProvider>map(endpoint -> createPageSourceProvider(endpoint, maximumRequestBytes, maximumResponseBytes))
                .orElseGet(() -> (transaction, session, split, table, columns, dynamicFilter) -> {
                    throw new IllegalStateException("web3.ethereum.rpc-url must be configured before querying ethereum.blocks");
                });
    }

    private static Web3PageSourceProvider createPageSourceProvider(URI endpoint, int maximumRequestBytes, int maximumResponseBytes)
    {
        JsonRpcClient jsonRpcClient = new JsonRpcClient(
                HttpClient.newHttpClient(), endpoint, Duration.ofSeconds(10), maximumRequestBytes, maximumResponseBytes);
        return new Web3PageSourceProvider(
                new EthereumBlockClient(jsonRpcClient),
                new EthereumTransactionClient(jsonRpcClient));
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
}
