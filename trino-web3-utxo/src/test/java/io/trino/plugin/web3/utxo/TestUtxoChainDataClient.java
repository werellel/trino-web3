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
package io.trino.plugin.web3.utxo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.trino.plugin.web3.adapter.ChainRow;
import io.trino.plugin.web3.adapter.RangeChainSplit;
import io.trino.plugin.web3.runtime.RemoteResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class TestUtxoChainDataClient
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    public void testAcceptsBitcoinCoreAddressArrayShape()
            throws Exception
    {
        String hash = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
        String txid = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        var block = OBJECT_MAPPER.readTree("""
                {"hash":"%s","height":7,"tx":[{"txid":"%s","version":2,"locktime":0,
                "vin":[{"coinbase":"03abcd","sequence":1}],
                "vout":[{"n":0,"value":0.10000000,"futureField":"present","scriptPubKey":{"hex":"0014abcd","addresses":["Lexample"]}}]}]}
                """.formatted(hash, txid));

        List<ChainRow> rows = UtxoChainDataClient.decode(
                "litecoin",
                "outputs",
                new RangeChainSplit("block_height", 7, 7),
                List.of(new RemoteResult(block, "primary")));

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().value("value_satoshis").longValue()).isEqualTo(10_000_000);
        assertThat(rows.getFirst().value("address").textValue()).isEqualTo("Lexample");
        assertThat(rows.getFirst().value("raw_json").textValue()).contains("\"futureField\":\"present\"");
    }
}
