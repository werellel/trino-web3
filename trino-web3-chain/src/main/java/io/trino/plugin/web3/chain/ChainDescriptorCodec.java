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

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/** Strict codec for declarative chain descriptor resources. */
public final class ChainDescriptorCodec
{
    private static final int MAXIMUM_DESCRIPTOR_BYTES = 1_048_576;
    private static final ObjectMapper OBJECT_MAPPER = createObjectMapper();

    private ChainDescriptorCodec() {}

    public static ChainDescriptor fromJson(String json)
    {
        requireNonNull(json, "json is null");
        byte[] bytes = json.getBytes(UTF_8);
        if (bytes.length > MAXIMUM_DESCRIPTOR_BYTES) {
            throw new IllegalArgumentException("chain descriptor exceeds maximum size of 1 MiB");
        }
        return read(bytes);
    }

    public static ChainDescriptor fromJson(InputStream input)
    {
        requireNonNull(input, "input is null");
        try (input) {
            byte[] bytes = input.readNBytes(MAXIMUM_DESCRIPTOR_BYTES + 1);
            if (bytes.length > MAXIMUM_DESCRIPTOR_BYTES) {
                throw new IllegalArgumentException("chain descriptor exceeds maximum size of 1 MiB");
            }
            return read(bytes);
        }
        catch (IOException e) {
            throw new IllegalArgumentException("chain descriptor cannot be read", e);
        }
    }

    public static String toJson(ChainDescriptor descriptor)
    {
        try {
            return OBJECT_MAPPER.writeValueAsString(requireNonNull(descriptor, "descriptor is null"));
        }
        catch (IOException e) {
            throw new IllegalArgumentException("chain descriptor cannot be serialized", e);
        }
    }

    private static ChainDescriptor read(byte[] bytes)
    {
        try {
            return OBJECT_MAPPER.readValue(bytes, ChainDescriptor.class);
        }
        catch (IOException e) {
            throw new IllegalArgumentException("invalid chain descriptor JSON", e);
        }
    }

    private static ObjectMapper createObjectMapper()
    {
        ObjectMapper objectMapper = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION.mappedFeature());
        objectMapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(64)
                .maxStringLength(65_536)
                .maxNumberLength(1_000)
                .build());
        return objectMapper;
    }
}
