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
import io.trino.plugin.web3.chain.ChainRegistry;
import io.trino.plugin.web3.core.Web3ColumnHandle;
import io.trino.plugin.web3.core.Web3TableHandle;
import io.trino.plugin.web3.evm.EthereumChainAdapter;
import io.trino.plugin.web3.evm.ArbitrumChainAdapter;
import io.trino.plugin.web3.evm.AvalancheChainAdapter;
import io.trino.plugin.web3.evm.BaseChainAdapter;
import io.trino.plugin.web3.evm.BnbChainAdapter;
import io.trino.plugin.web3.evm.PolygonChainAdapter;
import io.trino.plugin.web3.evm.OptimismChainAdapter;
import io.trino.plugin.web3.dogecoin.DogecoinChainAdapter;
import io.trino.plugin.web3.litecoin.LitecoinChainAdapter;
import io.trino.plugin.web3.solana.SolanaChainAdapter;
import io.trino.plugin.web3.tron.TronChainAdapter;
import io.trino.plugin.web3.sui.SuiChainAdapter;
import io.trino.plugin.web3.cosmos.CosmosChainAdapter;
import io.trino.plugin.web3.cosmos.OsmosisChainAdapter;
import io.trino.plugin.web3.cosmos.InjectiveChainAdapter;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ConnectorMetadata;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.connector.ConnectorTableVersion;
import io.trino.spi.connector.Constraint;
import io.trino.spi.connector.ConstraintApplicationResult;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.type.Type;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.VarcharType.VARCHAR;

public final class Web3Metadata
        implements ConnectorMetadata
{
    private final ChainMetadataRegistry tables;
    private final DescriptorPredicatePushdown predicatePushdown;

    public Web3Metadata(int maximumTransactionHashesPerQuery)
    {
        this(
                maximumTransactionHashesPerQuery,
                ChainRegistry.of(new EthereumChainAdapter(), new BaseChainAdapter(), new OptimismChainAdapter(), new ArbitrumChainAdapter(), new BnbChainAdapter(), new PolygonChainAdapter(), new AvalancheChainAdapter(), new SolanaChainAdapter(), new AptosChainAdapter(), new TronChainAdapter(), new SuiChainAdapter(), new CosmosChainAdapter(), new OsmosisChainAdapter(), new InjectiveChainAdapter(), new BitcoinChainAdapter(), new LitecoinChainAdapter(), new DogecoinChainAdapter(), new BitcoinCashChainAdapter()),
                Web3Metadata::resolveBuiltInType);
    }

    Web3Metadata(
            int maximumTransactionHashesPerQuery,
            ChainRegistry chainRegistry,
            Function<String, Type> typeResolver)
    {
        if (maximumTransactionHashesPerQuery < 1) {
            throw new IllegalArgumentException("maximumTransactionHashesPerQuery must be positive");
        }
        tables = new ChainMetadataRegistry(chainRegistry, typeResolver);
        predicatePushdown = new DescriptorPredicatePushdown(maximumTransactionHashesPerQuery);
    }

    @Override
    public List<String> listSchemaNames(ConnectorSession session)
    {
        return tables.schemas();
    }

    @Override
    public ConnectorTableHandle getTableHandle(
            ConnectorSession session,
            SchemaTableName tableName,
            Optional<ConnectorTableVersion> startVersion,
            Optional<ConnectorTableVersion> endVersion)
    {
        return tables.table(tableName)
                .<ConnectorTableHandle>map(ignored -> new Web3TableHandle(tableName.getSchemaName(), tableName.getTableName()))
                .orElse(null);
    }

    @Override
    public ConnectorTableMetadata getTableMetadata(ConnectorSession session, ConnectorTableHandle table)
    {
        return resolvedTable(table).metadata();
    }

    @Override
    public List<SchemaTableName> listTables(ConnectorSession session, Optional<String> schemaName)
    {
        return tables.listTables(schemaName);
    }

    @Override
    public Map<String, ColumnHandle> getColumnHandles(ConnectorSession session, ConnectorTableHandle table)
    {
        return resolvedTable(table).columnHandles();
    }

    @Override
    public ColumnMetadata getColumnMetadata(ConnectorSession session, ConnectorTableHandle table, ColumnHandle column)
    {
        if (!(column instanceof Web3ColumnHandle web3Column)) {
            throw new IllegalArgumentException("column is not a Web3 column handle");
        }
        return resolvedTable(table).columnMetadata(web3Column);
    }

    @Override
    public Optional<ConstraintApplicationResult<ConnectorTableHandle>> applyFilter(
            ConnectorSession session,
            ConnectorTableHandle table,
            Constraint constraint)
    {
        ChainMetadataRegistry.ResolvedTable resolvedTable = resolvedTable(table);
        Web3TableHandle web3Table = (Web3TableHandle) table;
        return predicatePushdown.apply(resolvedTable, web3Table, constraint.getSummary())
                .map(result -> new ConstraintApplicationResult<>(
                        result.handle(),
                        result.remainingFilter(),
                        constraint.getExpression(),
                        false));
    }

    private ChainMetadataRegistry.ResolvedTable resolvedTable(ConnectorTableHandle table)
    {
        if (!(table instanceof Web3TableHandle web3Table)) {
            throw new IllegalArgumentException("table is not a Web3 table handle");
        }
        ChainMetadataRegistry.ResolvedTable resolvedTable = tables.table(web3Table)
                .orElseThrow(() -> new IllegalArgumentException("unknown Web3 table " + web3Table.schemaName() + "." + web3Table.tableName()));
        return resolvedTable;
    }

    static Type resolveBuiltInType(String type)
    {
        return switch (type) {
            case "bigint" -> BIGINT;
            case "boolean" -> BOOLEAN;
            case "varchar" -> VARCHAR;
            default -> throw new IllegalArgumentException("unsupported built-in descriptor type " + type);
        };
    }
}
