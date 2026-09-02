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
import io.trino.plugin.web3.bitcoin.BitcoinChainAdapter;
import io.trino.plugin.web3.bitcoincash.BitcoinCashChainAdapter;
import io.trino.plugin.web3.adapter.ChainPlanningException;
import io.trino.plugin.web3.adapter.ChainScan;
import io.trino.plugin.web3.adapter.ChainSplit;
import io.trino.plugin.web3.adapter.ChainSplitLimits;
import io.trino.plugin.web3.adapter.DiscreteValueChainSplit;
import io.trino.plugin.web3.adapter.ExecutableChainRegistry;
import io.trino.plugin.web3.adapter.KeyedRangeChainSplit;
import io.trino.plugin.web3.adapter.RangeChainSplit;
import io.trino.plugin.web3.core.Web3DiscreteValueSplit;
import io.trino.plugin.web3.core.Web3KeyedRangeSplit;
import io.trino.plugin.web3.core.Web3RangeSplit;
import io.trino.plugin.web3.core.Web3TableHandle;
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
import io.trino.plugin.web3.dogecoin.DogecoinChainAdapter;
import io.trino.plugin.web3.litecoin.LitecoinChainAdapter;
import io.trino.plugin.web3.solana.SolanaChainAdapter;
import io.trino.plugin.web3.tron.TronChainAdapter;
import io.trino.plugin.web3.sui.SuiChainAdapter;
import io.trino.plugin.web3.cosmos.CosmosChainAdapter;
import io.trino.plugin.web3.cosmos.OsmosisChainAdapter;
import io.trino.plugin.web3.cosmos.InjectiveChainAdapter;
import io.trino.spi.StandardErrorCode;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorSplitManager;
import io.trino.spi.connector.ConnectorSplitSource;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.ConnectorTransactionHandle;
import io.trino.spi.connector.Constraint;
import io.trino.spi.connector.DynamicFilter;
import io.trino.spi.connector.FixedSplitSource;

import java.util.Map;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

public final class Web3SplitManager
        implements ConnectorSplitManager
{
    private final ExecutableChainRegistry adapters;
    private final ChainSplitLimits limits;

    public Web3SplitManager(long maximumBlocksPerSplit, long maximumBlocksPerQuery)
    {
        this(maximumBlocksPerSplit, maximumBlocksPerQuery, 1_000);
    }

    public Web3SplitManager(long maximumBlocksPerSplit, long maximumBlocksPerQuery, int maximumTransactionHashesPerQuery)
    {
        this(
                maximumBlocksPerSplit,
                maximumBlocksPerQuery,
                maximumTransactionHashesPerQuery,
                ExecutableChainRegistry.of(new EthereumChainAdapter(), new BaseChainAdapter(), new OptimismChainAdapter(), new ArbitrumChainAdapter(), new BnbChainAdapter(), new PolygonChainAdapter(), new AvalancheChainAdapter(), new GnosisChainAdapter(), new KaiaChainAdapter(), new ArcChainAdapter(), new StoryChainAdapter(), new BobaChainAdapter(), new CeloChainAdapter(), new HyperEvmChainAdapter(), new AbstractChainAdapter(), new AnimeChainAdapter(), new ApeChainChainAdapter(), new DegenChainAdapter(), new InkChainAdapter(), new JovayChainAdapter(), new CrossFiChainAdapter(), new LineaChainAdapter(), new EthereumSepoliaChainAdapter(), new BaseSepoliaChainAdapter(), new OptimismSepoliaChainAdapter(), new ArbitrumSepoliaChainAdapter(), new BnbTestnetChainAdapter(), new PolygonAmoyChainAdapter(), new AvalancheFujiChainAdapter(), new GnosisChiadoChainAdapter(), new KaiaKairosChainAdapter(), new ArcTestnetChainAdapter(), new StoryAeneidChainAdapter(), new BobaSepoliaChainAdapter(), new CeloSepoliaChainAdapter(), new HyperEvmTestnetChainAdapter(), new AbstractSepoliaChainAdapter(), new AnimeTestnetChainAdapter(), new ApeChainCurtisChainAdapter(), new InkSepoliaChainAdapter(), new JovaySepoliaChainAdapter(), new CrossFiTestnetChainAdapter(), new LineaSepoliaChainAdapter(), new SolanaChainAdapter(), new AptosChainAdapter(), new TronChainAdapter(), new SuiChainAdapter(), new CosmosChainAdapter(), new OsmosisChainAdapter(), new InjectiveChainAdapter(), new BitcoinChainAdapter(), new LitecoinChainAdapter(), new DogecoinChainAdapter(), new BitcoinCashChainAdapter()));
    }

    Web3SplitManager(
            long maximumBlocksPerSplit,
            long maximumBlocksPerQuery,
            int maximumTransactionHashesPerQuery,
            ExecutableChainRegistry adapters)
    {
        this.adapters = requireNonNull(adapters, "adapters is null");
        limits = new ChainSplitLimits(maximumBlocksPerSplit, maximumBlocksPerQuery, maximumTransactionHashesPerQuery);
    }

    @Override
    public ConnectorSplitSource getSplits(
            ConnectorTransactionHandle transaction,
            ConnectorSession session,
            ConnectorTableHandle table,
            DynamicFilter dynamicFilter,
            Constraint constraint)
    {
        requireNonNull(transaction, "transaction is null");
        requireNonNull(session, "session is null");
        requireNonNull(dynamicFilter, "dynamicFilter is null");
        requireNonNull(constraint, "constraint is null");

        if (!(table instanceof Web3TableHandle web3Table)) {
            throw new IllegalArgumentException("table is not a Web3 table handle");
        }
        Map<String, ChainScan.LongRange> ranges = web3Table.ranges().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> new ChainScan.LongRange(entry.getValue().startInclusive(), entry.getValue().endInclusive())));
        ChainScan scan = new ChainScan(
                web3Table.tableName(),
                web3Table.methodName(),
                ranges,
                web3Table.discreteValues());
        try {
            java.util.List<ChainSplit> splits = adapters.adapterForSchema(web3Table.schemaName()).planSplits(scan, limits);
            return new FixedSplitSource(splits.stream()
                    .map(Web3SplitManager::toConnectorSplit)
                    .toList());
        }
        catch (ChainPlanningException e) {
            throw new TrinoException(StandardErrorCode.NOT_SUPPORTED, e.getMessage());
        }
    }

    private static io.trino.spi.connector.ConnectorSplit toConnectorSplit(ChainSplit split)
    {
        if (split instanceof RangeChainSplit range) {
            return new Web3RangeSplit(range.column(), range.startInclusive(), range.endInclusive());
        }
        if (split instanceof KeyedRangeChainSplit range) {
            return new Web3KeyedRangeSplit(range.keys(), range.rangeColumn(), range.startInclusive(), range.endInclusive());
        }
        if (split instanceof DiscreteValueChainSplit value) {
            return new Web3DiscreteValueSplit(value.column(), value.value());
        }
        throw new IllegalStateException("chain adapter returned an unsupported split type");
    }
}
