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
package io.trino.plugin.web3.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestExecutionModels
{
    @Test
    public void testRemoteOperationIsImmutableValueKey()
    {
        List<Object> parameters = new ArrayList<>(List.of("0x1", false));
        RemoteOperation operation = new RemoteOperation("eth_getBlockByNumber", parameters);
        parameters.clear();

        assertThat(operation.parameters()).containsExactly("0x1", false);
        assertThat(operation).isEqualTo(new RemoteOperation("eth_getBlockByNumber", List.of("0x1", false)));
        assertThatThrownBy(() -> operation.parameters().add("unexpected"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(operation.protocol()).isEqualTo(RemoteRequest.Protocol.JSON_RPC);
    }

    @Test
    public void testRestRequestIsEndpointRelativeBoundedAndImmutable()
            throws Exception
    {
        ObjectNode body = (ObjectNode) new ObjectMapper().readTree("{\"limit\":10}");
        Map<String, List<String>> query = new java.util.LinkedHashMap<>();
        query.put("ledger_version", new ArrayList<>(List.of("123")));
        RestRemoteRequest request = new RestRemoteRequest("POST", "/v1/transactions", query, Optional.of(body));
        query.clear();
        body.put("secret", "changed");

        assertThat(request.protocol()).isEqualTo(RemoteRequest.Protocol.REST);
        assertThat(request.operationName()).isEqualTo("POST /v1/transactions");
        assertThat(request.queryParameters()).containsEntry("ledger_version", List.of("123"));
        assertThat(request.body().orElseThrow().has("secret")).isFalse();
        assertThat(request.toString()).doesNotContain("ledger_version", "123", "limit");
        assertThatThrownBy(() -> request.queryParameters().put("other", List.of("value")))
                .isInstanceOf(UnsupportedOperationException.class);

        assertThatThrownBy(() -> new RestRemoteRequest("GET", "https://secret.example/v1", Map.of(), Optional.empty()))
                .hasMessage("REST path must be a bounded endpoint-relative absolute path");
        assertThatThrownBy(() -> new RestRemoteRequest("GET", "//secret.example/v1", Map.of(), Optional.empty()))
                .hasMessage("REST path must be a bounded endpoint-relative absolute path");
        assertThatThrownBy(() -> new RestRemoteRequest("GET", "/v1\\transactions", Map.of(), Optional.empty()))
                .hasMessage("REST path must be a bounded endpoint-relative absolute path");
        assertThatThrownBy(() -> new RestRemoteRequest("GET", "/v1", Map.of(), Optional.of(body)))
                .hasMessage("REST GET request must not contain a body");
    }

    @Test
    public void testExecutionPolicyRejectsInvalidBounds()
    {
        assertThatThrownBy(() -> new ExecutionPolicy(0, 1, 1, 1, 1, Duration.ofMillis(1), Duration.ofMillis(1), Duration.ofMillis(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionPolicy(1, 1, 1, 1, 1, Duration.ofMillis(2), Duration.ofMillis(1), Duration.ofMillis(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testDerivedPoliciesPreserveConfiguredContracts()
    {
        ExecutionPolicy policy = new ExecutionPolicy(2, 3, 4, 5, 6, Duration.ofMillis(7), Duration.ofMillis(8), Duration.ofMillis(9));

        assertThat(policy.retryPolicy()).isEqualTo(new RetryPolicy(5, Duration.ofMillis(7), Duration.ofMillis(8)));
        assertThat(policy.rateLimitPolicy()).isEqualTo(new RateLimitPolicy(6));
    }

    @Test
    public void testProviderProfileDoesNotExposeEndpoint()
    {
        ProviderProfile provider = new ProviderProfile("primary", URI.create("https://secret.example/rpc?token=do-not-leak"));

        assertThat(provider.toString())
                .contains("primary")
                .doesNotContain("secret.example", "do-not-leak");
    }
}
