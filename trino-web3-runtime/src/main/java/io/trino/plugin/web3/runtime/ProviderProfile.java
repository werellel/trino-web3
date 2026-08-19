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

import java.net.URI;

import static java.util.Objects.requireNonNull;

/** A generic provider endpoint. M2 deliberately contains no vendor behavior. */
public record ProviderProfile(String name, URI endpoint, ProviderCapabilities capabilities)
{
    public ProviderProfile(String name, URI endpoint)
    {
        this(name, endpoint, ProviderCapabilities.GENERIC_JSON_RPC);
    }

    public ProviderProfile
    {
        requireNonNull(name, "name is null");
        requireNonNull(endpoint, "endpoint is null");
        requireNonNull(capabilities, "capabilities is null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name is blank");
        }
        if (!endpoint.isAbsolute() || !(endpoint.getScheme().equals("http") || endpoint.getScheme().equals("https"))) {
            throw new IllegalArgumentException("endpoint must be an absolute HTTP(S) URL");
        }
    }

    @Override
    public String toString()
    {
        return "ProviderProfile{name=" + name + ", capabilities=" + capabilities + "}";
    }
}
