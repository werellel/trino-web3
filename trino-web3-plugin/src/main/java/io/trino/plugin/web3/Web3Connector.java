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

import io.trino.plugin.web3.aptos.AptosChainAdapter;
import io.trino.plugin.web3.aptos.AptosTestnetChainAdapter;
import io.trino.plugin.web3.adapter.ExecutableChainRegistry;
import io.trino.plugin.web3.adapter.EndpointIdentityVerifier;
import io.trino.plugin.web3.bitcoin.BitcoinChainAdapter;
import io.trino.plugin.web3.bitcoin.BitcoinTestnetChainAdapter;
import io.trino.plugin.web3.bitcoincash.BitcoinCashChainAdapter;
import io.trino.plugin.web3.bitcoincash.BitcoinCashTestnetChainAdapter;
import io.trino.plugin.web3.dogecoin.DogecoinChainAdapter;
import io.trino.plugin.web3.dogecoin.DogecoinTestnetChainAdapter;
import io.trino.plugin.web3.evm.EthereumChainAdapter;
import io.trino.plugin.web3.evm.BaseChainAdapter;
import io.trino.plugin.web3.evm.ArbitrumChainAdapter;
import io.trino.plugin.web3.evm.BnbChainAdapter;
import io.trino.plugin.web3.evm.PolygonChainAdapter;
import io.trino.plugin.web3.evm.AvalancheChainAdapter;
import io.trino.plugin.web3.evm.OptimismChainAdapter;
import io.trino.plugin.web3.evm.GnosisChainAdapter;
import io.trino.plugin.web3.evm.KaiaChainAdapter;
import io.trino.plugin.web3.evm.ArcChainAdapter;
import io.trino.plugin.web3.evm.StoryChainAdapter;
import io.trino.plugin.web3.evm.BobaChainAdapter;
import io.trino.plugin.web3.evm.CeloChainAdapter;
import io.trino.plugin.web3.evm.HyperEvmChainAdapter;
import io.trino.plugin.web3.evm.AbstractChainAdapter;
import io.trino.plugin.web3.evm.AnimeChainAdapter;
import io.trino.plugin.web3.evm.ApeChainChainAdapter;
import io.trino.plugin.web3.evm.DegenChainAdapter;
import io.trino.plugin.web3.evm.InkChainAdapter;
import io.trino.plugin.web3.evm.JovayChainAdapter;
import io.trino.plugin.web3.evm.CrossFiChainAdapter;
import io.trino.plugin.web3.evm.LineaChainAdapter;
import io.trino.plugin.web3.evm.EthereumSepoliaChainAdapter;
import io.trino.plugin.web3.evm.BaseSepoliaChainAdapter;
import io.trino.plugin.web3.evm.OptimismSepoliaChainAdapter;
import io.trino.plugin.web3.evm.ArbitrumSepoliaChainAdapter;
import io.trino.plugin.web3.evm.BnbTestnetChainAdapter;
import io.trino.plugin.web3.evm.PolygonAmoyChainAdapter;
import io.trino.plugin.web3.evm.AvalancheFujiChainAdapter;
import io.trino.plugin.web3.evm.GnosisChiadoChainAdapter;
import io.trino.plugin.web3.evm.KaiaKairosChainAdapter;
import io.trino.plugin.web3.evm.ArcTestnetChainAdapter;
import io.trino.plugin.web3.evm.StoryAeneidChainAdapter;
import io.trino.plugin.web3.evm.BobaSepoliaChainAdapter;
import io.trino.plugin.web3.evm.CeloSepoliaChainAdapter;
import io.trino.plugin.web3.evm.HyperEvmTestnetChainAdapter;
import io.trino.plugin.web3.evm.AbstractSepoliaChainAdapter;
import io.trino.plugin.web3.evm.AnimeTestnetChainAdapter;
import io.trino.plugin.web3.evm.ApeChainCurtisChainAdapter;
import io.trino.plugin.web3.evm.InkSepoliaChainAdapter;
import io.trino.plugin.web3.evm.JovaySepoliaChainAdapter;
import io.trino.plugin.web3.evm.CrossFiTestnetChainAdapter;
import io.trino.plugin.web3.evm.LineaSepoliaChainAdapter;
import io.trino.plugin.web3.tron.TronChainAdapter;
import io.trino.plugin.web3.tron.TronNileChainAdapter;
import io.trino.plugin.web3.tron.TronShastaChainAdapter;
import io.trino.plugin.web3.sui.SuiChainAdapter;
import io.trino.plugin.web3.sui.SuiTestnetChainAdapter;
import io.trino.plugin.web3.cosmos.CosmosChainAdapter;
import io.trino.plugin.web3.cosmos.OsmosisChainAdapter;
import io.trino.plugin.web3.cosmos.InjectiveChainAdapter;
import io.trino.plugin.web3.cosmos.CosmosTestnetChainAdapter;
import io.trino.plugin.web3.cosmos.OsmosisTestnetChainAdapter;
import io.trino.plugin.web3.cosmos.InjectiveTestnetChainAdapter;
import io.trino.plugin.web3.litecoin.LitecoinChainAdapter;
import io.trino.plugin.web3.litecoin.LitecoinTestnetChainAdapter;
import io.trino.plugin.web3.solana.SolanaChainAdapter;
import io.trino.plugin.web3.solana.SolanaDevnetChainAdapter;
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
import io.trino.spi.connector.SystemTable;
import io.trino.spi.transaction.IsolationLevel;
import io.trino.spi.type.TypeManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

public final class Web3Connector
        implements Connector
{
    private static final Set<String> ADDITIONAL_REST_SCHEMAS = Set.of(
            "cosmos_testnet", "osmosis_testnet", "injective_testnet",
            "aptos_testnet", "tron_nile", "tron_shasta");
    private final ConnectorMetadata metadata;
    private final ConnectorSplitManager splitManager;
    private final ConnectorPageSourceProvider pageSourceProvider;
    private final Map<String, RemoteExecutionRuntime> runtimes;
    private final Set<SystemTable> systemTables;

    private static List<URI> emptyEndpoints()
    {
        return List.of();
    }

    public Web3Connector()
    {
        this(
                100,
                10_000,
                1_000,
                1_048_576,
                16 * 1_048_576,
                emptyEndpoints(),
                emptyEndpoints(),
                emptyEndpoints(),
                emptyEndpoints(),
                emptyEndpoints(),
                emptyEndpoints(),
                emptyEndpoints(),
                emptyEndpoints(),
                emptyEndpoints(),
                emptyEndpoints(),
                emptyEndpoints(),
                emptyEndpoints(),
                emptyEndpoints(),
                emptyEndpoints(),
                emptyEndpoints(),
                emptyEndpoints(),
                emptyEndpoints(),
                emptyEndpoints(),
                true,
                ExecutionPolicy.defaults(),
                RemoteCacheConfig.disabled(),
                createComponents(1_000, Web3Metadata::resolveBuiltInType),
                Map.of());
    }

    public Web3Connector(
            long maximumBlocksPerSplit,
            long maximumBlocksPerQuery,
            int maximumTransactionHashesPerQuery,
            int maximumRequestBytes,
            int maximumResponseBytes,
            List<URI> ethereumRpcEndpoints,
            List<URI> solanaRpcEndpoints,
            List<URI> aptosRestEndpoints,
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
                emptyEndpoints(),
                emptyEndpoints(),
                emptyEndpoints(),
                emptyEndpoints(),
                emptyEndpoints(),
                emptyEndpoints(),
                solanaRpcEndpoints,
                aptosRestEndpoints,
                emptyEndpoints(),
                emptyEndpoints(),
                emptyEndpoints(),
                emptyEndpoints(),
                emptyEndpoints(),
                emptyEndpoints(),
                emptyEndpoints(),
                emptyEndpoints(),
                emptyEndpoints(),
                jsonRpcBatchEnabled,
                executionPolicy,
                cacheConfig,
                typeManager);
    }

    public Web3Connector(
            long maximumBlocksPerSplit,
            long maximumBlocksPerQuery,
            int maximumTransactionHashesPerQuery,
            int maximumRequestBytes,
            int maximumResponseBytes,
            List<URI> ethereumRpcEndpoints,
            List<URI> solanaRpcEndpoints,
            List<URI> aptosRestEndpoints,
            List<URI> bitcoinRpcEndpoints,
            List<URI> litecoinRpcEndpoints,
            List<URI> dogecoinRpcEndpoints,
            List<URI> bitcoinCashRpcEndpoints,
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
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                solanaRpcEndpoints,
                aptosRestEndpoints,
                emptyEndpoints(),
                emptyEndpoints(),
                emptyEndpoints(),
                emptyEndpoints(),
                emptyEndpoints(),
                bitcoinRpcEndpoints,
                litecoinRpcEndpoints,
                dogecoinRpcEndpoints,
                bitcoinCashRpcEndpoints,
                jsonRpcBatchEnabled,
                executionPolicy,
                cacheConfig,
                createComponents(
                        maximumTransactionHashesPerQuery,
                        requireNonNull(typeManager, "typeManager is null")::fromSqlType),
                Map.of());
    }

    public Web3Connector(
            long maximumBlocksPerSplit,
            long maximumBlocksPerQuery,
            int maximumTransactionHashesPerQuery,
            int maximumRequestBytes,
            int maximumResponseBytes,
            List<URI> ethereumRpcEndpoints,
            List<URI> baseRpcEndpoints,
            List<URI> optimismRpcEndpoints,
            List<URI> arbitrumRpcEndpoints,
            List<URI> bnbRpcEndpoints,
            List<URI> polygonRpcEndpoints,
            List<URI> avalancheRpcEndpoints,
            List<URI> solanaRpcEndpoints,
            List<URI> aptosRestEndpoints,
            List<URI> tronApiEndpoints,
            List<URI> suiRpcEndpoints,
            List<URI> cosmosRestEndpoints,
            List<URI> osmosisRestEndpoints,
            List<URI> injectiveRestEndpoints,
            List<URI> bitcoinRpcEndpoints,
            List<URI> litecoinRpcEndpoints,
            List<URI> dogecoinRpcEndpoints,
            List<URI> bitcoinCashRpcEndpoints,
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
                baseRpcEndpoints,
                optimismRpcEndpoints,
                arbitrumRpcEndpoints,
                bnbRpcEndpoints,
                polygonRpcEndpoints,
                avalancheRpcEndpoints,
                solanaRpcEndpoints,
                aptosRestEndpoints,
                tronApiEndpoints,
                suiRpcEndpoints,
                cosmosRestEndpoints,
                osmosisRestEndpoints,
                injectiveRestEndpoints,
                bitcoinRpcEndpoints,
                litecoinRpcEndpoints,
                dogecoinRpcEndpoints,
                bitcoinCashRpcEndpoints,
                jsonRpcBatchEnabled,
                executionPolicy,
                cacheConfig,
                createComponents(
                        maximumTransactionHashesPerQuery,
                        requireNonNull(typeManager, "typeManager is null")::fromSqlType),
                Map.of());
    }

    public Web3Connector(
            long maximumBlocksPerSplit,
            long maximumBlocksPerQuery,
            int maximumTransactionHashesPerQuery,
            int maximumRequestBytes,
            int maximumResponseBytes,
            List<URI> ethereumRpcEndpoints,
            List<URI> baseRpcEndpoints,
            List<URI> optimismRpcEndpoints,
            List<URI> arbitrumRpcEndpoints,
            List<URI> bnbRpcEndpoints,
            List<URI> polygonRpcEndpoints,
            List<URI> avalancheRpcEndpoints,
            List<URI> solanaRpcEndpoints,
            List<URI> aptosRestEndpoints,
            List<URI> tronApiEndpoints,
            List<URI> suiRpcEndpoints,
            List<URI> cosmosRestEndpoints,
            List<URI> osmosisRestEndpoints,
            List<URI> injectiveRestEndpoints,
            List<URI> bitcoinRpcEndpoints,
            List<URI> litecoinRpcEndpoints,
            List<URI> dogecoinRpcEndpoints,
            List<URI> bitcoinCashRpcEndpoints,
            boolean jsonRpcBatchEnabled,
            ExecutionPolicy executionPolicy,
            RemoteCacheConfig cacheConfig,
            TypeManager typeManager,
            Map<String, List<URI>> additionalEvmRpcEndpoints)
    {
        this(
                maximumBlocksPerSplit,
                maximumBlocksPerQuery,
                maximumTransactionHashesPerQuery,
                maximumRequestBytes,
                maximumResponseBytes,
                ethereumRpcEndpoints,
                baseRpcEndpoints,
                optimismRpcEndpoints,
                arbitrumRpcEndpoints,
                bnbRpcEndpoints,
                polygonRpcEndpoints,
                avalancheRpcEndpoints,
                solanaRpcEndpoints,
                aptosRestEndpoints,
                tronApiEndpoints,
                suiRpcEndpoints,
                cosmosRestEndpoints,
                osmosisRestEndpoints,
                injectiveRestEndpoints,
                bitcoinRpcEndpoints,
                litecoinRpcEndpoints,
                dogecoinRpcEndpoints,
                bitcoinCashRpcEndpoints,
                jsonRpcBatchEnabled,
                executionPolicy,
                cacheConfig,
                createComponents(
                        maximumTransactionHashesPerQuery,
                        requireNonNull(typeManager, "typeManager is null")::fromSqlType),
                additionalEvmRpcEndpoints);
    }

    private Web3Connector(
            long maximumBlocksPerSplit,
            long maximumBlocksPerQuery,
            int maximumTransactionHashesPerQuery,
            int maximumRequestBytes,
            int maximumResponseBytes,
            List<URI> ethereumRpcEndpoints,
            List<URI> baseRpcEndpoints,
            List<URI> optimismRpcEndpoints,
            List<URI> arbitrumRpcEndpoints,
            List<URI> bnbRpcEndpoints,
            List<URI> polygonRpcEndpoints,
            List<URI> avalancheRpcEndpoints,
            List<URI> solanaRpcEndpoints,
            List<URI> aptosRestEndpoints,
            List<URI> tronApiEndpoints,
            List<URI> suiRpcEndpoints,
            List<URI> cosmosRestEndpoints,
            List<URI> osmosisRestEndpoints,
            List<URI> injectiveRestEndpoints,
            List<URI> bitcoinRpcEndpoints,
            List<URI> litecoinRpcEndpoints,
            List<URI> dogecoinRpcEndpoints,
            List<URI> bitcoinCashRpcEndpoints,
            boolean jsonRpcBatchEnabled,
            ExecutionPolicy executionPolicy,
            RemoteCacheConfig cacheConfig,
            ConnectorComponents components,
            Map<String, List<URI>> additionalEvmRpcEndpoints)
    {
        requireNonNull(components, "components is null");
        requireNonNull(additionalEvmRpcEndpoints, "additionalEvmRpcEndpoints is null");
        metadata = components.metadata();
        splitManager = new Web3SplitManager(
                maximumBlocksPerSplit,
                maximumBlocksPerQuery,
                maximumTransactionHashesPerQuery,
                components.adapters());
        HttpClient httpClient = HttpClient.newHttpClient();
        Map<String, RemoteExecutionRuntime> configuredRuntimes = new LinkedHashMap<>();
        try {
            if (!ethereumRpcEndpoints.isEmpty()) {
                configuredRuntimes.put("ethereum", createJsonRpcRuntime(
                        httpClient,
                        ethereumRpcEndpoints,
                        maximumRequestBytes,
                        maximumResponseBytes,
                        jsonRpcBatchEnabled,
                        executionPolicy,
                        cacheConfig));
            }
            if (!baseRpcEndpoints.isEmpty()) {
                configuredRuntimes.put("base", createJsonRpcRuntime(httpClient, baseRpcEndpoints, maximumRequestBytes, maximumResponseBytes, jsonRpcBatchEnabled, executionPolicy, cacheConfig));
            }
            if (!optimismRpcEndpoints.isEmpty()) {
                configuredRuntimes.put("optimism", createJsonRpcRuntime(httpClient, optimismRpcEndpoints, maximumRequestBytes, maximumResponseBytes, jsonRpcBatchEnabled, executionPolicy, cacheConfig));
            }
            if (!arbitrumRpcEndpoints.isEmpty()) {
                configuredRuntimes.put("arbitrum", createJsonRpcRuntime(httpClient, arbitrumRpcEndpoints, maximumRequestBytes, maximumResponseBytes, jsonRpcBatchEnabled, executionPolicy, cacheConfig));
            }
            if (!bnbRpcEndpoints.isEmpty()) {
                configuredRuntimes.put("bnb", createJsonRpcRuntime(httpClient, bnbRpcEndpoints, maximumRequestBytes, maximumResponseBytes, jsonRpcBatchEnabled, executionPolicy, cacheConfig));
            }
            if (!polygonRpcEndpoints.isEmpty()) {
                configuredRuntimes.put("polygon", createJsonRpcRuntime(httpClient, polygonRpcEndpoints, maximumRequestBytes, maximumResponseBytes, jsonRpcBatchEnabled, executionPolicy, cacheConfig));
            }
            if (!avalancheRpcEndpoints.isEmpty()) {
                configuredRuntimes.put("avalanche", createJsonRpcRuntime(httpClient, avalancheRpcEndpoints, maximumRequestBytes, maximumResponseBytes, jsonRpcBatchEnabled, executionPolicy, cacheConfig));
            }
            if (!solanaRpcEndpoints.isEmpty()) {
                configuredRuntimes.put("solana", createJsonRpcRuntime(
                        httpClient,
                        solanaRpcEndpoints,
                        maximumRequestBytes,
                        maximumResponseBytes,
                        jsonRpcBatchEnabled,
                        executionPolicy,
                        cacheConfig));
            }
            if (!aptosRestEndpoints.isEmpty()) {
                configuredRuntimes.put("aptos", createRestRuntime(
                        httpClient,
                        aptosRestEndpoints,
                        maximumRequestBytes,
                        maximumResponseBytes,
                        executionPolicy,
                        cacheConfig));
            }
            if (!tronApiEndpoints.isEmpty()) {
                configuredRuntimes.put("tron", createRestRuntime(
                        httpClient,
                        tronApiEndpoints,
                        maximumRequestBytes,
                        maximumResponseBytes,
                        executionPolicy,
                        cacheConfig));
            }
            if (!suiRpcEndpoints.isEmpty()) {
                configuredRuntimes.put("sui", createJsonRpcRuntime(
                        httpClient,
                        suiRpcEndpoints,
                        maximumRequestBytes,
                        maximumResponseBytes,
                        jsonRpcBatchEnabled,
                        executionPolicy,
                        cacheConfig));
            }
            if (!cosmosRestEndpoints.isEmpty()) {
                configuredRuntimes.put("cosmos", createRestRuntime(httpClient, cosmosRestEndpoints, maximumRequestBytes, maximumResponseBytes, executionPolicy, cacheConfig));
            }
            if (!osmosisRestEndpoints.isEmpty()) {
                configuredRuntimes.put("osmosis", createRestRuntime(httpClient, osmosisRestEndpoints, maximumRequestBytes, maximumResponseBytes, executionPolicy, cacheConfig));
            }
            if (!injectiveRestEndpoints.isEmpty()) {
                configuredRuntimes.put("injective", createRestRuntime(httpClient, injectiveRestEndpoints, maximumRequestBytes, maximumResponseBytes, executionPolicy, cacheConfig));
            }
            additionalEvmRpcEndpoints.forEach((schemaName, endpoints) -> {
                if (!endpoints.isEmpty()) {
                    configuredRuntimes.put(schemaName, ADDITIONAL_REST_SCHEMAS.contains(schemaName)
                            ? createRestRuntime(httpClient, endpoints, maximumRequestBytes, maximumResponseBytes, executionPolicy, cacheConfig)
                            : createJsonRpcRuntime(httpClient, endpoints, maximumRequestBytes, maximumResponseBytes, jsonRpcBatchEnabled, executionPolicy, cacheConfig));
                }
            });
            if (!bitcoinRpcEndpoints.isEmpty()) {
                configuredRuntimes.put("bitcoin", createJsonRpcRuntime(
                        httpClient,
                        bitcoinRpcEndpoints,
                        maximumRequestBytes,
                        maximumResponseBytes,
                        jsonRpcBatchEnabled,
                        executionPolicy,
                        cacheConfig));
            }
            if (!litecoinRpcEndpoints.isEmpty()) {
                configuredRuntimes.put("litecoin", createJsonRpcRuntime(
                        httpClient,
                        litecoinRpcEndpoints,
                        maximumRequestBytes,
                        maximumResponseBytes,
                        jsonRpcBatchEnabled,
                        executionPolicy,
                        cacheConfig));
            }
            if (!dogecoinRpcEndpoints.isEmpty()) {
                configuredRuntimes.put("dogecoin", createJsonRpcRuntime(
                        httpClient,
                        dogecoinRpcEndpoints,
                        maximumRequestBytes,
                        maximumResponseBytes,
                        jsonRpcBatchEnabled,
                        executionPolicy,
                        cacheConfig));
            }
            if (!bitcoinCashRpcEndpoints.isEmpty()) {
                configuredRuntimes.put("bitcoincash", createJsonRpcRuntime(
                        httpClient,
                        bitcoinCashRpcEndpoints,
                        maximumRequestBytes,
                        maximumResponseBytes,
                        jsonRpcBatchEnabled,
                        executionPolicy,
                        cacheConfig));
            }
            verifyEndpointIdentities(components.adapters(), configuredRuntimes);
            runtimes = Map.copyOf(configuredRuntimes);
            pageSourceProvider = Web3PageSourceProvider.forRuntimes(components.adapters(), runtimes, components.typeResolver());
            systemTables = Web3SystemTables.create(components.adapters(), runtimes);
        }
        catch (RuntimeException e) {
            configuredRuntimes.values().forEach(RemoteExecutionRuntime::close);
            throw e;
        }
    }

    private static RemoteExecutionRuntime createJsonRpcRuntime(
            HttpClient httpClient,
            List<URI> endpoints,
            int maximumRequestBytes,
            int maximumResponseBytes,
            boolean jsonRpcBatchEnabled,
            ExecutionPolicy executionPolicy,
            RemoteCacheConfig cacheConfig)
    {
        return new RemoteExecutionRuntime(
                httpClient,
                providerProfiles(endpoints, jsonRpcBatchEnabled),
                Duration.ofSeconds(10),
                maximumRequestBytes,
                maximumResponseBytes,
                executionPolicy,
                cacheConfig);
    }

    private static RemoteExecutionRuntime createRestRuntime(
            HttpClient httpClient,
            List<URI> endpoints,
            int maximumRequestBytes,
            int maximumResponseBytes,
            ExecutionPolicy executionPolicy,
            RemoteCacheConfig cacheConfig)
    {
        return RemoteExecutionRuntime.forRest(
                httpClient,
                providerProfiles(endpoints, false),
                Duration.ofSeconds(10),
                maximumRequestBytes,
                maximumResponseBytes,
                executionPolicy,
                cacheConfig);
    }

    private static List<ProviderProfile> providerProfiles(List<URI> endpoints, boolean jsonRpcBatchEnabled)
    {
        return java.util.stream.IntStream.range(0, endpoints.size())
                .mapToObj(index -> new ProviderProfile(
                        index == 0 ? "primary" : "fallback-" + index,
                        endpoints.get(index),
                        new ProviderCapabilities(jsonRpcBatchEnabled)))
                .toList();
    }

    private static void verifyEndpointIdentities(ExecutableChainRegistry adapters, Map<String, RemoteExecutionRuntime> runtimes)
    {
        adapters.adapters().forEach(adapter -> {
            RemoteExecutionRuntime runtime = runtimes.get(adapter.descriptor().schemaName());
            if (runtime != null) {
                EndpointIdentityVerifier.verify(adapter, runtime);
            }
        });
    }

    private static ConnectorComponents createComponents(int maximumTransactionHashesPerQuery, Function<String, io.trino.spi.type.Type> typeResolver)
    {
        ExecutableChainRegistry adapters = ExecutableChainRegistry.of(
                new EthereumChainAdapter(),
                new BaseChainAdapter(),
                new OptimismChainAdapter(),
                new ArbitrumChainAdapter(),
                new BnbChainAdapter(),
                new PolygonChainAdapter(),
                new AvalancheChainAdapter(),
                new GnosisChainAdapter(),
                new KaiaChainAdapter(),
                new ArcChainAdapter(),
                new StoryChainAdapter(),
                new BobaChainAdapter(),
                new CeloChainAdapter(),
                new HyperEvmChainAdapter(),
                new AbstractChainAdapter(),
                new AnimeChainAdapter(),
                new ApeChainChainAdapter(),
                new DegenChainAdapter(),
                new InkChainAdapter(),
                new JovayChainAdapter(),
                new CrossFiChainAdapter(),
                new LineaChainAdapter(),
                new EthereumSepoliaChainAdapter(),
                new BaseSepoliaChainAdapter(),
                new OptimismSepoliaChainAdapter(),
                new ArbitrumSepoliaChainAdapter(),
                new BnbTestnetChainAdapter(),
                new PolygonAmoyChainAdapter(),
                new AvalancheFujiChainAdapter(),
                new GnosisChiadoChainAdapter(),
                new KaiaKairosChainAdapter(),
                new ArcTestnetChainAdapter(),
                new StoryAeneidChainAdapter(),
                new BobaSepoliaChainAdapter(),
                new CeloSepoliaChainAdapter(),
                new HyperEvmTestnetChainAdapter(),
                new AbstractSepoliaChainAdapter(),
                new AnimeTestnetChainAdapter(),
                new ApeChainCurtisChainAdapter(),
                new InkSepoliaChainAdapter(),
                new JovaySepoliaChainAdapter(),
                new CrossFiTestnetChainAdapter(),
                new LineaSepoliaChainAdapter(),
                new SolanaChainAdapter(),
                new SolanaDevnetChainAdapter(),
                new AptosChainAdapter(),
                new AptosTestnetChainAdapter(),
                new TronChainAdapter(),
                new TronNileChainAdapter(),
                new TronShastaChainAdapter(),
                new SuiChainAdapter(),
                new SuiTestnetChainAdapter(),
                new CosmosChainAdapter(),
                new CosmosTestnetChainAdapter(),
                new OsmosisChainAdapter(),
                new OsmosisTestnetChainAdapter(),
                new InjectiveChainAdapter(),
                new InjectiveTestnetChainAdapter(),
                new BitcoinChainAdapter(),
                new BitcoinTestnetChainAdapter(),
                new LitecoinChainAdapter(),
                new LitecoinTestnetChainAdapter(),
                new DogecoinChainAdapter(),
                new DogecoinTestnetChainAdapter(),
                new BitcoinCashChainAdapter(),
                new BitcoinCashTestnetChainAdapter());
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
    public Set<SystemTable> getSystemTables()
    {
        return systemTables;
    }

    @Override
    public void shutdown()
    {
        runtimes.values().forEach(RemoteExecutionRuntime::close);
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
