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
package io.trino.plugin.web3.sui;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.trino.plugin.web3.adapter.RangeChainSplit;
import io.trino.plugin.web3.runtime.RemoteResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

final class TestSuiChainDataClient
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void testDecodesCheckpointAndTransactionRows()
            throws Exception
    {
        var checkpoint = OBJECT_MAPPER.readTree("""
                {"sequenceNumber":10,"digest":"checkpoint-digest","epoch":3,"timestampMs":1700000000000,"transactions":["tx-digest"],"futureField":"kept"}
                """);
        var transaction = OBJECT_MAPPER.readTree("""
                {"digest":"tx-digest","transaction":{"data":{"sender":"0xabc"}},"effects":{"status":{"status":"success"}},"futureField":"kept"}
                """);
        RangeChainSplit range = new RangeChainSplit("checkpoint_sequence_number", 10, 10);

        assertThat(SuiChainDataClient.decodeCheckpoints(range, List.of(new RemoteResult(checkpoint, "test"))).getFirst().values())
                .containsEntry("checkpoint_sequence_number", com.fasterxml.jackson.databind.node.LongNode.valueOf(10))
                .containsEntry("digest", com.fasterxml.jackson.databind.node.TextNode.valueOf("checkpoint-digest"))
                .containsEntry("transaction_count", com.fasterxml.jackson.databind.node.LongNode.valueOf(1))
                .satisfies(values -> assertThat(values.get("raw_json").textValue()).contains("futureField"));
        assertThat(SuiChainDataClient.decodeTransactions(range, List.of(new RemoteResult(checkpoint, "test")), List.of(new RemoteResult(transaction, "test"))).getFirst().values())
                .containsEntry("digest", com.fasterxml.jackson.databind.node.TextNode.valueOf("tx-digest"))
                .containsEntry("sender", com.fasterxml.jackson.databind.node.TextNode.valueOf("0xabc"))
                .containsEntry("status", com.fasterxml.jackson.databind.node.TextNode.valueOf("success"))
                .satisfies(values -> assertThat(values.get("raw_json").textValue()).contains("futureField"));
    }
}
