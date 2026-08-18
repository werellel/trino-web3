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

import java.time.Duration;

import static java.util.Objects.requireNonNull;

/** Bounds shared worker-local remote execution resources. */
public record ExecutionPolicy(
        int maximumConcurrency,
        int maximumQueueSize,
        int maximumBatchSize,
        int maximumAttempts,
        int requestsPerSecond,
        Duration initialBackoff,
        Duration maximumBackoff,
        Duration providerCooldown)
{
    public ExecutionPolicy
    {
        if (maximumConcurrency < 1 || maximumQueueSize < 1 || maximumBatchSize < 1 || maximumAttempts < 1 || requestsPerSecond < 1) {
            throw new IllegalArgumentException("execution limits must be positive");
        }
        requireNonNull(initialBackoff, "initialBackoff is null");
        requireNonNull(maximumBackoff, "maximumBackoff is null");
        requireNonNull(providerCooldown, "providerCooldown is null");
        if (initialBackoff.isNegative() || initialBackoff.isZero() || maximumBackoff.compareTo(initialBackoff) < 0 || providerCooldown.isNegative() || providerCooldown.isZero()) {
            throw new IllegalArgumentException("execution durations are invalid");
        }
    }

    public static ExecutionPolicy defaults()
    {
        return new ExecutionPolicy(16, 1_024, 100, 3, 100, Duration.ofMillis(100), Duration.ofSeconds(30), Duration.ofSeconds(30));
    }

    public RetryPolicy retryPolicy()
    {
        return new RetryPolicy(maximumAttempts, initialBackoff, maximumBackoff);
    }

    public RateLimitPolicy rateLimitPolicy()
    {
        return new RateLimitPolicy(requestsPerSecond);
    }
}
