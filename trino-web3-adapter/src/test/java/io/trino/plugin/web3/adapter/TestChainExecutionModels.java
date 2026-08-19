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
package io.trino.plugin.web3.adapter;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TestChainExecutionModels
{
    @Test
    void testScanDefensivelyCopiesPredicates()
    {
        Map<String, ChainScan.LongRange> ranges = new LinkedHashMap<>();
        ranges.put("slot", new ChainScan.LongRange(10, 20));
        List<String> signatures = new ArrayList<>(List.of("signature"));
        Map<String, List<String>> values = new LinkedHashMap<>();
        values.put("signature", signatures);

        ChainScan scan = new ChainScan("transactions", ranges, values);
        ranges.clear();
        signatures.clear();
        values.clear();

        assertThat(scan.ranges()).containsEntry("slot", new ChainScan.LongRange(10, 20));
        assertThat(scan.discreteValues()).containsEntry("signature", List.of("signature"));
        assertThatThrownBy(() -> scan.ranges().put("other", new ChainScan.LongRange(1, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void testRowDefensivelyCopiesJsonAndDistinguishesMissingColumn()
    {
        ObjectNode nested = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        nested.put("value", "original");
        ChainRow row = new ChainRow(Map.of("id", TextNode.valueOf("one"), "payload", nested));
        nested.put("value", "changed");
        ObjectNode returned = (ObjectNode) row.value("payload");
        returned.put("value", "also-changed");

        assertThat(row.value("payload").path("value").textValue()).isEqualTo("original");
        assertThat(row.retainedSizeInBytes()).isPositive();
        assertThatThrownBy(() -> row.value("missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("chain row is missing declared column missing");
    }

    @Test
    void testLimitsRejectUnboundedValues()
    {
        assertThatThrownBy(() -> new ChainSplitLimits(0, 1, 1))
                .hasMessage("maximumRangeItemsPerSplit must be positive");
        assertThatThrownBy(() -> new ChainSplitLimits(2, 1, 1))
                .hasMessage("maximumRangeItemsPerQuery is smaller than maximumRangeItemsPerSplit");
        assertThatThrownBy(() -> new ChainScan.LongRange(-1, 1))
                .hasMessage("invalid non-negative long range");
    }
}
