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
package io.trino.plugin.web3.bitcoin;

import com.fasterxml.jackson.databind.JsonNode;
import io.trino.plugin.web3.adapter.ChainRow;
import io.trino.plugin.web3.adapter.RangeChainSplit;
import io.trino.plugin.web3.runtime.RemoteExecutionRuntime;
import io.trino.plugin.web3.runtime.RemoteResult;
import io.trino.plugin.web3.utxo.UtxoChainDataClient;

import java.util.List;

/** Compatibility facade for the shared Bitcoin-family UTXO data client. */
public final class BitcoinChainDataClient
        extends UtxoChainDataClient
{
    public BitcoinChainDataClient(RemoteExecutionRuntime runtime)
    {
        super(runtime, "bitcoin");
    }

    public static List<ChainRow> decode(String tableName, RangeChainSplit split, List<RemoteResult> results)
    {
        return UtxoChainDataClient.decode("Bitcoin", tableName, split, results);
    }

    public static long toSatoshis(JsonNode value)
    {
        return UtxoChainDataClient.toSatoshis(value);
    }
}
