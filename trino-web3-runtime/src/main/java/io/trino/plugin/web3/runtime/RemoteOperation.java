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

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * A provider-independent, read-only remote operation. The runtime treats the
 * method and parameters as opaque data and does not interpret chain semantics.
 */
public record RemoteOperation(String method, List<Object> parameters)
{
    public RemoteOperation
    {
        requireNonNull(method, "method is null");
        requireNonNull(parameters, "parameters is null");
        if (method.isBlank()) {
            throw new IllegalArgumentException("method is blank");
        }
        parameters = List.copyOf(parameters);
    }
}
