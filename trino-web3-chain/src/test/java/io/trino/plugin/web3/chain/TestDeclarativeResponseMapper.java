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
package io.trino.plugin.web3.chain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.trino.plugin.web3.chain.RemoteMethodDescriptor.Cardinality.ARRAY;
import static io.trino.plugin.web3.chain.RemoteMethodDescriptor.Protocol.REST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TestDeclarativeResponseMapper
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void testIgnoresUnknownRemoteFieldsAndMapsOptionalFieldsToNull()
            throws Exception
    {
        RemoteMethodDescriptor method = method(false);
        JsonNode response = OBJECT_MAPPER.readTree("""
                {"data":[{"id":"one","provider_extension":{"ignored":true}},{"id":"two","note":"present"}]}
                """);

        List<Map<String, JsonNode>> rows = DeclarativeResponseMapper.map(method, response, 2);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsOnlyKeys("id", "note");
        assertThat(rows.get(0).get("id").textValue()).isEqualTo("one");
        assertThat(rows.get(0).get("note").isNull()).isTrue();
        assertThat(rows.get(1).get("note").textValue()).isEqualTo("present");
    }

    @Test
    void testRejectsMissingRequiredFieldWithoutLeakingPayload()
            throws Exception
    {
        RemoteMethodDescriptor method = method(true);
        JsonNode response = OBJECT_MAPPER.readTree("""
                {"data":[{"id":"secret-value"}]}
                """);

        assertThatThrownBy(() -> DeclarativeResponseMapper.map(method, response, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("remote response is missing required column note for operation list-items")
                .hasMessageNotContaining("secret-value");
    }

    @Test
    void testRejectsResponseAboveExplicitRowLimit()
            throws Exception
    {
        RemoteMethodDescriptor method = method(false);
        JsonNode response = OBJECT_MAPPER.readTree("""
                {"data":[{"id":"one"},{"id":"two"}]}
                """);

        assertThatThrownBy(() -> DeclarativeResponseMapper.map(method, response, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("remote response row count exceeds limit for operation list-items");
    }

    private static RemoteMethodDescriptor method(boolean noteRequired)
    {
        return new RemoteMethodDescriptor(
                "list-items",
                REST,
                "GET",
                "/items",
                List.of(),
                new RemoteMethodDescriptor.ResponseMapping(
                        ARRAY,
                        "/data",
                        List.of(
                                new RemoteMethodDescriptor.ResponseField("id", "/id", true),
                                new RemoteMethodDescriptor.ResponseField("note", "/note", noteRequired))));
    }
}
