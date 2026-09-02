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
import com.fasterxml.jackson.databind.ObjectMapper;
import io.trino.plugin.web3.adapter.ChainRow;
import io.trino.plugin.web3.adapter.RangeChainSplit;
import io.trino.plugin.web3.runtime.RemoteResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestBitcoinChainDataClient
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String BLOCK_HASH = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
    private static final String TRANSACTION_HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String PREVIOUS_HASH = "1111111111111111111111111111111111111111111111111111111111111111";

    @Test
    public void testDecodesNativeUtxoRows()
            throws Exception
    {
        JsonNode block = OBJECT_MAPPER.readTree("""
                {
                  "hash": "%s",
                  "height": 100,
                  "previousblockhash": "%s",
                  "time": 1700000000,
                  "nTx": 1,
                  "tx": [{
                    "txid": "%s",
                    "version": 2,
                    "locktime": 0,
                    "vin": [
                      {"txid": "%s", "vout": 1, "sequence": 4294967295},
                      {"coinbase": "03abcd", "sequence": 4294967295}
                    ],
                    "vout": [
                      {"n": 0, "value": 1.23456789, "scriptPubKey": {"hex": "0014abcd", "address": "bc1qexample"}},
                      {"n": 1, "value": 0.00000001, "scriptPubKey": {"hex": "6a01ff"}}
                    ]
                  }]
                }
                """.formatted(BLOCK_HASH, PREVIOUS_HASH, TRANSACTION_HASH, PREVIOUS_HASH));
        RangeChainSplit split = new RangeChainSplit("block_height", 100, 100);
        RemoteResult result = new RemoteResult(block, "primary");

        List<ChainRow> transactions = BitcoinChainDataClient.decode("transactions", split, List.of(result));
        assertThat(transactions).extracting(row -> row.value("txid").textValue()).containsExactly(TRANSACTION_HASH);
        assertThat(transactions.getFirst().value("input_count").longValue()).isEqualTo(2);
        assertThat(transactions.getFirst().value("output_count").longValue()).isEqualTo(2);

        List<ChainRow> inputs = BitcoinChainDataClient.decode("inputs", split, List.of(result));
        assertThat(inputs).hasSize(2);
        assertThat(inputs.get(0).value("previous_txid").textValue()).isEqualTo(PREVIOUS_HASH);
        assertThat(inputs.get(1).value("coinbase").textValue()).isEqualTo("03abcd");
        assertThat(inputs.get(1).value("previous_txid").isNull()).isTrue();

        List<ChainRow> outputs = BitcoinChainDataClient.decode("outputs", split, List.of(result));
        assertThat(outputs).hasSize(2);
        assertThat(outputs.get(0).value("value_satoshis").longValue()).isEqualTo(123_456_789);
        assertThat(outputs.get(1).value("value_satoshis").longValue()).isEqualTo(1);
        assertThat(outputs.get(1).value("address").isNull()).isTrue();
    }

    @Test
    public void testRejectsMismatchedHeightAndFractionalSatoshis()
            throws Exception
    {
        JsonNode block = OBJECT_MAPPER.readTree("{\"hash\":\"%s\",\"height\":101,\"tx\":[]}".formatted(BLOCK_HASH));
        assertThatThrownBy(() -> BitcoinChainDataClient.decode(
                "blocks",
                new RangeChainSplit("height", 100, 100),
                List.of(new RemoteResult(block, "primary"))))
                .hasMessageContaining("height does not match");
        assertThatThrownBy(() -> BitcoinChainDataClient.toSatoshis(OBJECT_MAPPER.readTree("1.000000001")))
                .hasMessageContaining("not an exact satoshi amount");
    }
}
