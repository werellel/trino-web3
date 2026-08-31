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
package io.trino.plugin.web3.solana;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.trino.plugin.web3.adapter.RangeChainSplit;
import io.trino.plugin.web3.runtime.RemoteResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TestSolanaChainDataClient
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void testDecodesNativeBlockTransactionAndInstructionRows()
            throws Exception
    {
        var block = OBJECT_MAPPER.readTree("""
                {
                  "blockhash":"block-hash",
                  "parentSlot":9,
                  "blockTime":123,
                  "transactions":[
                    {
                      "meta":{"err":null,"fee":5000},
                      "transaction":{
                        "signatures":["signature"],
                        "message":{
                          "accountKeys":["payer","program"],
                          "instructions":[{"programIdIndex":1,"accounts":[0],"data":"3Bxs"}]
                        }
                      }
                    }
                  ]
                }
                """);
        RangeChainSplit range = new RangeChainSplit("slot", 10, 10);
        List<RemoteResult> results = List.of(new RemoteResult(block, "test"));

        assertThat(SolanaChainDataClient.decodeBlocks(range, results).getFirst().values())
                .containsEntry("slot", com.fasterxml.jackson.databind.node.LongNode.valueOf(10))
                .containsEntry("blockhash", com.fasterxml.jackson.databind.node.TextNode.valueOf("block-hash"));
        assertThat(SolanaChainDataClient.decodeTransactions(range, results).getFirst().values())
                .containsEntry("signature", com.fasterxml.jackson.databind.node.TextNode.valueOf("signature"))
                .containsEntry("success", com.fasterxml.jackson.databind.node.BooleanNode.TRUE);
        assertThat(SolanaChainDataClient.decodeInstructions(range, results).getFirst().values())
                .containsEntry("program_id", com.fasterxml.jackson.databind.node.TextNode.valueOf("program"))
                .containsEntry("account_indices", com.fasterxml.jackson.databind.node.TextNode.valueOf("[0]"));
    }

    @Test
    void testSkipsUnavailableFinalizedSlotAndRejectsInvalidInstruction()
            throws Exception
    {
        RangeChainSplit range = new RangeChainSplit("slot", 10, 10);
        assertThat(SolanaChainDataClient.decodeBlocks(range, List.of(new RemoteResult(OBJECT_MAPPER.nullNode(), "test"))))
                .isEmpty();

        var malformed = OBJECT_MAPPER.readTree("""
                {"transactions":[{"transaction":{"signatures":["signature"],"message":{"accountKeys":["payer"],"instructions":[{"programIdIndex":1,"accounts":[],"data":"x"}]}},"meta":{"err":null,"fee":1}}]}
                """);
        assertThatThrownBy(() -> SolanaChainDataClient.decodeInstructions(range, List.of(new RemoteResult(malformed, "test"))))
                .hasMessage("Solana instruction programIdIndex is outside message accountKeys");
    }
}
