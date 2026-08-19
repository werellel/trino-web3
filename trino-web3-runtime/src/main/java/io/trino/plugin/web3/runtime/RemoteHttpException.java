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

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Transport-neutral HTTP status failure used by runtime retry classification. */
public class RemoteHttpException
        extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final String retryAfter;

    public RemoteHttpException(int statusCode, Optional<String> retryAfter)
    {
        this("Remote endpoint returned HTTP " + statusCode, statusCode, retryAfter);
    }

    protected RemoteHttpException(String message, int statusCode, Optional<String> retryAfter)
    {
        super(requireNonNull(message, "message is null"));
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException("invalid HTTP status code");
        }
        this.statusCode = statusCode;
        this.retryAfter = requireNonNull(retryAfter, "retryAfter is null").orElse(null);
    }

    public int statusCode()
    {
        return statusCode;
    }

    public Optional<String> retryAfter()
    {
        return Optional.ofNullable(retryAfter);
    }
}
