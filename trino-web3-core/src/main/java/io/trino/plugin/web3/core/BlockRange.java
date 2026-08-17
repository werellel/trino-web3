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

import static java.lang.String.format;

public record BlockRange(long startInclusive, long endInclusive)
{
    @JsonCreator
    public BlockRange(
            @JsonProperty("startInclusive") long startInclusive,
            @JsonProperty("endInclusive") long endInclusive)
    {
        if (startInclusive < 0) {
            throw new IllegalArgumentException(format("startInclusive is negative: %s", startInclusive));
        }
        if (endInclusive < startInclusive) {
            throw new IllegalArgumentException(format("endInclusive (%s) is smaller than startInclusive (%s)", endInclusive, startInclusive));
        }
        this.startInclusive = startInclusive;
        this.endInclusive = endInclusive;
    }

    public long size()
    {
        return Math.addExact(Math.subtractExact(endInclusive, startInclusive), 1);
    }
}
