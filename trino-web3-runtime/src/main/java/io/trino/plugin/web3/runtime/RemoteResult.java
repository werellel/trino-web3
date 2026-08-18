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

import com.fasterxml.jackson.databind.JsonNode;

import static java.util.Objects.requireNonNull;

/** A successful immutable runtime result and the generic provider that served it. */
public record RemoteResult(JsonNode value, String providerName)
{
    public RemoteResult
    {
        requireNonNull(value, "value is null");
        requireNonNull(providerName, "providerName is null");
    }
}
