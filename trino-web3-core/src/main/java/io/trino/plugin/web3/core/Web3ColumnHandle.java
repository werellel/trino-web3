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
package io.trino.plugin.web3.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.trino.spi.connector.ColumnHandle;

import static java.util.Objects.requireNonNull;

public record Web3ColumnHandle(String name, int ordinal)
        implements ColumnHandle
{
    @JsonCreator
    public Web3ColumnHandle(
            @JsonProperty("name") String name,
            @JsonProperty("ordinal") int ordinal)
    {
        this.name = requireNonNull(name, "name is null");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name is empty");
        }
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal is negative");
        }
        this.ordinal = ordinal;
    }
}
