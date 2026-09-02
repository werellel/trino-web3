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

import java.net.http.HttpTimeoutException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

/** A retryable transport failure whose message deliberately excludes endpoint details. */
final class RemoteTransportException
        extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    private RemoteTransportException()
    {
        super("Remote HTTP request failed");
    }

    public static RuntimeException sanitize(Throwable failure)
    {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof CancellationException cancellationException) {
            return cancellationException;
        }
        if (cause instanceof HttpTimeoutException) {
            // Preserve the standard timeout type for retry and timeout metrics,
            // while replacing the JDK message and any request details.
            return new CompletionException(new SafeHttpTimeoutException());
        }
        return new RemoteTransportException();
    }

    private static final class SafeHttpTimeoutException
            extends HttpTimeoutException
    {
        private static final long serialVersionUID = 1L;

        private SafeHttpTimeoutException()
        {
            super("Remote HTTP request timed out");
        }
    }
}
