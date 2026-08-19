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
package io.trino.plugin.web3.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.trino.spi.connector.ConnectorTableHandle;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public record Web3TableHandle(String schemaName, String tableName, Optional<BlockRange> blockRange, List<String> transactionHashes)
        implements ConnectorTableHandle
{
    public Web3TableHandle(String schemaName, String tableName, Optional<BlockRange> blockRange)
    {
        this(schemaName, tableName, blockRange, List.of());
    }

    @JsonCreator
    public Web3TableHandle(
            @JsonProperty("schemaName") String schemaName,
            @JsonProperty("tableName") String tableName,
            @JsonProperty("blockRange") Optional<BlockRange> blockRange,
            @JsonProperty("transactionHashes") List<String> transactionHashes)
    {
        this.schemaName = requireNonNull(schemaName, "schemaName is null");
        this.tableName = requireNonNull(tableName, "tableName is null");
        this.blockRange = requireNonNull(blockRange, "blockRange is null");
        this.transactionHashes = List.copyOf(requireNonNull(transactionHashes, "transactionHashes is null"));
        if (blockRange.isPresent() && !this.transactionHashes.isEmpty()) {
            throw new IllegalArgumentException("blockRange and transactionHashes are mutually exclusive");
        }
    }

    public Web3TableHandle withBlockRange(BlockRange blockRange)
    {
        return new Web3TableHandle(schemaName, tableName, Optional.of(requireNonNull(blockRange, "blockRange is null")), List.of());
    }

    public Web3TableHandle withTransactionHashes(List<String> transactionHashes)
    {
        if (tableName.equals("blocks")) {
            throw new IllegalStateException("blocks table does not support transaction hashes");
        }
        return new Web3TableHandle(schemaName, tableName, Optional.empty(), transactionHashes);
    }

    @Override
    public String toString()
    {
        if (!transactionHashes.isEmpty()) {
            return schemaName + "." + tableName + "[transactionHashes=" + transactionHashes.size() + "]";
        }
        return blockRange
                .map(range -> schemaName + "." + tableName + "[blockRange=" + range + "]")
                .orElse(schemaName + "." + tableName);
    }
}
