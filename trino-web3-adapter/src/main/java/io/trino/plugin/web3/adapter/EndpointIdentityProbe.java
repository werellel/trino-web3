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

import com.fasterxml.jackson.databind.JsonNode;
import io.trino.plugin.web3.runtime.RemoteRequest;

import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/** A chain-native, transport-neutral request that identifies one endpoint's network. */
public record EndpointIdentityProbe(RemoteRequest request, Function<JsonNode, String> identityExtractor)
{
    public EndpointIdentityProbe
    {
        requireNonNull(request, "request is null");
        requireNonNull(identityExtractor, "identityExtractor is null");
    }

    public String extractIdentity(JsonNode response)
    {
        String identity = identityExtractor.apply(requireNonNull(response, "response is null"));
        if (identity == null || identity.isBlank()) {
            throw new IllegalArgumentException("endpoint returned an invalid chain identity");
        }
        return identity;
    }
}
