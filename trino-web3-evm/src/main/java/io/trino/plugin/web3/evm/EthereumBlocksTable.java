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

import io.trino.plugin.web3.core.Web3ColumnHandle;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.connector.SchemaTableName;

import java.util.List;
import java.util.Map;

import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.VarcharType.VARCHAR;

public final class EthereumBlocksTable
{
    public static final String SCHEMA_NAME = "ethereum";
    public static final String TABLE_NAME = "blocks";
    public static final Web3ColumnHandle BLOCK_NUMBER_COLUMN = new Web3ColumnHandle("block_number", 0);
    public static final Web3ColumnHandle BLOCK_HASH_COLUMN = new Web3ColumnHandle("block_hash", 1);
    public static final List<ColumnMetadata> COLUMNS = List.of(
            new ColumnMetadata(BLOCK_NUMBER_COLUMN.name(), BIGINT),
            new ColumnMetadata(BLOCK_HASH_COLUMN.name(), VARCHAR));
    public static final ConnectorTableMetadata TABLE_METADATA = new ConnectorTableMetadata(
            new SchemaTableName(SCHEMA_NAME, TABLE_NAME), COLUMNS);

    private EthereumBlocksTable() {}

    public static Map<String, Web3ColumnHandle> columnHandles()
    {
        return Map.of(
                BLOCK_NUMBER_COLUMN.name(), BLOCK_NUMBER_COLUMN,
                BLOCK_HASH_COLUMN.name(), BLOCK_HASH_COLUMN);
    }

    public static ColumnMetadata columnMetadata(Web3ColumnHandle column)
    {
        return COLUMNS.get(column.ordinal());
    }
}
