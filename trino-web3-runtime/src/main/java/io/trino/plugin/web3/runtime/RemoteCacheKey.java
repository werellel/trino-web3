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

import static java.util.Objects.requireNonNull;

/** Adapter-provided immutable identity; fields must already be canonical. */
public record RemoteCacheKey(String namespace, String operation, String parameters, String representation, int formatVersion)
{
    public RemoteCacheKey
    {
        requireNonNull(namespace, "namespace is null");
        requireNonNull(operation, "operation is null");
        requireNonNull(parameters, "parameters is null");
        requireNonNull(representation, "representation is null");
        if (namespace.isBlank() || operation.isBlank() || representation.isBlank()) {
            throw new IllegalArgumentException("cache key fields are blank");
        }
        if (formatVersion < 1) {
            throw new IllegalArgumentException("formatVersion must be positive");
        }
    }

    int estimatedSize()
    {
        long characters = (long) namespace.length() + operation.length() + parameters.length() + representation.length();
        return (int) Math.min(Integer.MAX_VALUE, 64 + characters * Character.BYTES);
    }
}
