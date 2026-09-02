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
package io.trino.plugin.web3.cosmos;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.trino.plugin.web3.adapter.RangeChainSplit;
import io.trino.plugin.web3.adapter.ChainRow;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class TestCosmosChainDataClient
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void testDecodesNativeBlockAndBase64Transactions()
            throws Exception
    {
        var response = OBJECT_MAPPER.readTree("""
                {"block_id":{"hash":"HASH10"},"block":{"header":{"chain_id":"cosmoshub-4","height":"10","time":"2024-01-01T00:00:00Z"},"data":{"txs":["dHgx","dHgy"]},"future":"kept"}}
                """);
        RangeChainSplit range = new RangeChainSplit("height", 10, 10);
        ChainRow block = CosmosChainDataClient.decodeBlock(response, 10);
        assertThat(block.values()).containsEntry("height", com.fasterxml.jackson.databind.node.LongNode.valueOf(10));
        assertThat(block.values()).containsEntry("hash", com.fasterxml.jackson.databind.node.TextNode.valueOf("HASH10"));
        assertThat(CosmosChainDataClient.decodeTransactions(response, 10)).hasSize(2);
    }
}
