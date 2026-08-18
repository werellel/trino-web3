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
package io.trino.plugin.web3;

import io.trino.spi.metrics.Count;

public record Web3Count(long total)
        implements Count<Web3Count>
{
    @Override
    public long getTotal()
    {
        return total;
    }

    @Override
    public Web3Count mergeWith(Web3Count other)
    {
        return new Web3Count(Math.addExact(total, other.total));
    }
}
