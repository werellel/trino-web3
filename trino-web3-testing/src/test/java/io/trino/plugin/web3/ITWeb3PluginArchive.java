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

import io.trino.spi.Plugin;
import io.trino.server.PluginManager;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

public class ITWeb3PluginArchive
{
    @Test
    public void testPluginArchiveRegistersPlugin()
            throws Exception
    {
        Path pluginArchive = Path.of("..", "trino-web3-plugin", "target", "trino-web3-plugin-0.1-SNAPSHOT.jar").toRealPath();

        try (var classLoader = PluginManager.createClassLoader("web3", List.of(pluginArchive.toUri().toURL()))) {
            List<String> pluginClassNames = ServiceLoader.load(Plugin.class, classLoader)
                    .stream()
                    .map(provider -> provider.type().getName())
                    .toList();

            assertThat(pluginClassNames).containsExactly("io.trino.plugin.web3.Web3Plugin");
        }
    }
}
