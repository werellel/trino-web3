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

import io.trino.plugin.web3.runtime.RemoteExecutionRuntime;
import io.trino.plugin.web3.runtime.RemoteResult;

import java.util.List;

import static java.util.Objects.requireNonNull;

/** Verifies that all configured provider roles for one schema identify one network. */
public final class EndpointIdentityVerifier
{
    private EndpointIdentityVerifier() {}

    public static void verify(ExecutableChainAdapter adapter, RemoteExecutionRuntime runtime)
    {
        requireNonNull(adapter, "adapter is null");
        requireNonNull(runtime, "runtime is null");
        List<String> providerNames = runtime.providerNames();
        EndpointIdentityProbe probe = adapter.endpointIdentityProbe();
        String expectedIdentity = null;
        for (String providerName : providerNames) {
            String identity = identity(adapter.descriptor().schemaName(), probe, runtime, providerName);
            if (expectedIdentity == null) {
                expectedIdentity = identity;
            }
            else if (!expectedIdentity.equals(identity)) {
                throw new IllegalArgumentException("configured endpoints do not have the same chain identity for schema " + adapter.descriptor().schemaName());
            }
        }
    }

    private static String identity(String schemaName, EndpointIdentityProbe probe, RemoteExecutionRuntime runtime, String providerName)
    {
        RemoteResult result;
        try {
            result = runtime.executeOnProvider(providerName, probe.request()).join();
        }
        catch (RuntimeException e) {
            throw new IllegalArgumentException("failed to validate endpoint identity for schema " + schemaName);
        }
        try {
            return probe.extractIdentity(result.value());
        }
        catch (RuntimeException e) {
            throw new IllegalArgumentException("endpoint returned an invalid chain identity for schema " + schemaName);
        }
    }
}
