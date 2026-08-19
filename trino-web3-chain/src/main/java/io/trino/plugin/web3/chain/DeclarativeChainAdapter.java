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

import java.io.InputStream;

import static java.util.Objects.requireNonNull;

/** A chain adapter assembled entirely from a validated descriptor. */
public final class DeclarativeChainAdapter
        implements ChainAdapter
{
    private final ChainDescriptor descriptor;

    public DeclarativeChainAdapter(ChainDescriptor descriptor)
    {
        this.descriptor = requireNonNull(descriptor, "descriptor is null");
    }

    public static DeclarativeChainAdapter fromJson(String json)
    {
        return new DeclarativeChainAdapter(ChainDescriptorCodec.fromJson(json));
    }

    public static DeclarativeChainAdapter fromJson(InputStream input)
    {
        return new DeclarativeChainAdapter(ChainDescriptorCodec.fromJson(input));
    }

    @Override
    public ChainDescriptor descriptor()
    {
        return descriptor;
    }

    @Override
    public String toString()
    {
        return "DeclarativeChainAdapter{name=" + descriptor.name() + ", adapterVersion=" + descriptor.adapterVersion() + "}";
    }
}
