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

import com.fasterxml.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static java.lang.Math.min;
import static java.util.Objects.requireNonNull;

/** Shared worker-local scheduler for bounded, read-only JSON-RPC operations. */
public final class RemoteExecutionRuntime
        implements AutoCloseable
{
    private static final long BATCH_COALESCING_MILLIS = 1;

    private final Object lock = new Object();
    private final ReentrantReadWriteLock cacheLifecycleLock = new ReentrantReadWriteLock();
    private final List<ProviderProfile> providers;
    private final Map<String, JsonRpcTransport> transports;
    private final ExecutionPolicy policy;
    private final RemoteExecutionScheduler scheduler;
    private final RemoteResultCache cache;
    private final int maximumCacheReadBytes;
    private final boolean batchingEnabled;
    private final ArrayDeque<SharedOperation> pending = new ArrayDeque<>();
    private final Map<RemoteOperation, SharedOperation> sharedOperations = new HashMap<>();
    private final Map<String, Long> unhealthyUntilNanos = new HashMap<>();
    private final MetricScope metrics = new MetricScope();
    private long nextPermitNanos;
    private int activeBatches;
    private boolean drainScheduled;
    private volatile boolean closed;

    public RemoteExecutionRuntime(
            HttpClient httpClient,
            List<ProviderProfile> providers,
            Duration requestTimeout,
            int maximumRequestBytes,
            int maximumResponseBytes,
            ExecutionPolicy policy)
    {
        this(
                providers,
                createTransports(httpClient, providers, requestTimeout, maximumRequestBytes, maximumResponseBytes),
                policy,
                new ExecutorRemoteExecutionScheduler(),
                RemoteCacheConfig.disabled(),
                maximumResponseBytes);
    }

    public RemoteExecutionRuntime(
            HttpClient httpClient,
            List<ProviderProfile> providers,
            Duration requestTimeout,
            int maximumRequestBytes,
            int maximumResponseBytes,
            ExecutionPolicy policy,
            RemoteCacheConfig cacheConfig)
    {
        this(
                providers,
                createTransports(httpClient, providers, requestTimeout, maximumRequestBytes, maximumResponseBytes),
                policy,
                new ExecutorRemoteExecutionScheduler(),
                cacheConfig,
                maximumResponseBytes);
    }

    RemoteExecutionRuntime(
            List<ProviderProfile> providers,
            Map<String, JsonRpcTransport> transports,
            ExecutionPolicy policy,
            RemoteExecutionScheduler scheduler)
    {
        this(providers, transports, policy, scheduler, RemoteCacheConfig.disabled());
    }

    RemoteExecutionRuntime(
            List<ProviderProfile> providers,
            Map<String, JsonRpcTransport> transports,
            ExecutionPolicy policy,
            RemoteExecutionScheduler scheduler,
            RemoteCacheConfig cacheConfig)
    {
        this(providers, transports, policy, scheduler, cacheConfig, cacheConfig.maximumEntryBytes());
    }

    RemoteExecutionRuntime(
            List<ProviderProfile> providers,
            Map<String, JsonRpcTransport> transports,
            ExecutionPolicy policy,
            RemoteExecutionScheduler scheduler,
            RemoteCacheConfig cacheConfig,
            int maximumCacheReadBytes)
    {
        this.providers = List.copyOf(requireNonNull(providers, "providers is null"));
        if (this.providers.isEmpty()) {
            throw new IllegalArgumentException("providers is empty");
        }
        this.transports = Map.copyOf(requireNonNull(transports, "transports is null"));
        this.policy = requireNonNull(policy, "policy is null");
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
        cache = new RemoteResultCache(requireNonNull(cacheConfig, "cacheConfig is null"));
        if (maximumCacheReadBytes < 1) {
            throw new IllegalArgumentException("maximumCacheReadBytes must be positive");
        }
        this.maximumCacheReadBytes = maximumCacheReadBytes;
        Set<String> providerNames = new java.util.HashSet<>();
        for (ProviderProfile provider : this.providers) {
            if (!providerNames.add(provider.name())) {
                throw new IllegalArgumentException("provider names must be unique");
            }
            if (!this.transports.containsKey(provider.name())) {
                throw new IllegalArgumentException("provider transport is missing");
            }
        }
        batchingEnabled = this.providers.stream()
                .allMatch(provider -> provider.capabilities().supportsJsonRpcBatch());
    }

    public CompletableFuture<RemoteResult> execute(RemoteOperation operation)
    {
        return execute(operation, new MetricScope());
    }

    public CompletableFuture<List<RemoteResult>> executeBatch(List<RemoteOperation> operations)
    {
        return executeBatchWithMetrics(operations).future();
    }

    public RemoteExecution<List<RemoteResult>> executeBatchWithMetrics(List<RemoteOperation> operations)
    {
        requireNonNull(operations, "operations is null");
        MetricScope scope = new MetricScope();
        return executeBatchWithMetrics(operations, scope);
    }

    public ExecutionContext newExecutionContext()
    {
        return new ExecutionContext(new MetricScope());
    }

    public boolean isCacheEnabled()
    {
        return cache.isEnabled();
    }

    public RemoteCacheMetrics cacheMetrics()
    {
        return cache.metrics();
    }

    private RemoteExecution<List<RemoteResult>> executeBatchWithMetrics(List<RemoteOperation> operations, MetricScope scope)
    {
        if (operations.size() > policy.maximumBatchSize()) {
            return new RemoteExecution<>(CompletableFuture.failedFuture(new IllegalArgumentException("logical batch exceeds maximumBatchSize")), scope::snapshot);
        }
        List<CompletableFuture<RemoteResult>> futures = operations.stream()
                .map(operation -> execute(operation, scope))
                .toList();
        CompletableFuture<List<RemoteResult>> result = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList());
        result.whenComplete((value, failure) -> {
            if (result.isCancelled()) {
                futures.forEach(future -> future.cancel(true));
            }
        });
        return new RemoteExecution<>(result, scope::snapshot);
    }

    public final class ExecutionContext
    {
        private final Object contextLock = new Object();
        private final MetricScope scope;
        private final AtomicLong retainedCacheReadBytes = new AtomicLong();
        private boolean cancelled;

        private ExecutionContext(MetricScope scope)
        {
            this.scope = scope;
        }

        public RemoteExecution<List<RemoteResult>> executeBatch(List<RemoteOperation> operations)
        {
            requireNonNull(operations, "operations is null");
            RemoteExecution<List<RemoteResult>> execution = executeBatchWithMetrics(operations, scope);
            trackCancellation(execution.future());
            return execution;
        }

        public Optional<JsonNode> getCached(RemoteCacheKey key)
        {
            requireNonNull(key, "key is null");
            if (isCancelled()) {
                return Optional.empty();
            }
            long remainingBytes = Math.max(0, maximumCacheReadBytes - retainedCacheReadBytes.get());
            Optional<RemoteResultCache.CachedValue> value;
            cacheLifecycleLock.readLock().lock();
            try {
                if (closed) {
                    return Optional.empty();
                }
                value = cache.get(key, remainingBytes);
            }
            finally {
                cacheLifecycleLock.readLock().unlock();
            }
            if (value.isPresent()) {
                RemoteResultCache.CachedValue cached = value.orElseThrow();
                long retainedBytes = retainedCacheReadBytes.addAndGet(cached.serializedBytes());
                if (retainedBytes > maximumCacheReadBytes || isCancelled()) {
                    retainedCacheReadBytes.addAndGet(-cached.serializedBytes());
                    if (cache.isEnabled()) {
                        scope.cacheMiss();
                    }
                    return Optional.empty();
                }
                scope.cacheHit(cached.serializedBytes());
                return Optional.of(cached.value());
            }
            if (cache.isEnabled()) {
                scope.cacheMiss();
            }
            return Optional.empty();
        }

        public void admit(RemoteCacheKey key, JsonNode value)
        {
            requireNonNull(key, "key is null");
            requireNonNull(value, "value is null");
            if (isCancelled()) {
                return;
            }
            Optional<RemoteResultCache.PreparedValue> prepared = cache.prepare(value);
            if (prepared.isEmpty()) {
                return;
            }
            int bytes;
            synchronized (contextLock) {
                if (cancelled) {
                    return;
                }
                cacheLifecycleLock.readLock().lock();
                try {
                    if (closed) {
                        return;
                    }
                    bytes = cache.put(key, prepared.orElseThrow());
                }
                finally {
                    cacheLifecycleLock.readLock().unlock();
                }
            }
            scope.cacheWrite(bytes);
        }

        public void invalidate(RemoteCacheKey key)
        {
            requireNonNull(key, "key is null");
            cacheLifecycleLock.readLock().lock();
            try {
                if (!closed) {
                    cache.invalidate(key);
                }
            }
            finally {
                cacheLifecycleLock.readLock().unlock();
            }
        }

        public void revalidation()
        {
            if (cache.isEnabled()) {
                scope.cacheRevalidation();
            }
        }

        public <T> RemoteExecution<T> execution(CompletableFuture<T> future)
        {
            requireNonNull(future, "future is null");
            trackCancellation(future);
            future.whenComplete((value, failure) -> retainedCacheReadBytes.set(0));
            return new RemoteExecution<>(future, scope::snapshot, retainedCacheReadBytes::get);
        }

        public RemoteExecutionMetrics metrics()
        {
            return scope.snapshot();
        }

        private boolean isCancelled()
        {
            synchronized (contextLock) {
                return cancelled;
            }
        }

        private void trackCancellation(CompletableFuture<?> future)
        {
            future.whenComplete((value, failure) -> {
                if (future.isCancelled()) {
                    synchronized (contextLock) {
                        cancelled = true;
                    }
                    retainedCacheReadBytes.set(0);
                }
            });
        }
    }

    public RemoteExecutionMetrics metrics()
    {
        return metrics.snapshot();
    }

    private CompletableFuture<RemoteResult> execute(RemoteOperation operation, MetricScope scope)
    {
        requireNonNull(operation, "operation is null");
        Subscriber subscriber = new Subscriber(scope);
        synchronized (lock) {
            if (closed) {
                return CompletableFuture.failedFuture(new IllegalStateException("RPC runtime is closed"));
            }
            SharedOperation shared = sharedOperations.get(operation);
            if (shared == null) {
                if (pending.size() >= policy.maximumQueueSize()) {
                    return CompletableFuture.failedFuture(new IllegalStateException("RPC execution queue is full"));
                }
                shared = new SharedOperation(operation);
                sharedOperations.put(operation, shared);
                pending.addLast(shared);
            }
            shared.subscribers.add(subscriber);
            subscriber.shared = shared;
            if (shared.batch != null && shared.batch.attemptMetrics != null) {
                shared.batch.attemptMetrics.addScope(scope, shared);
            }
            scheduleDrainLocked();
        }
        subscriber.future.whenComplete((value, failure) -> {
            if (subscriber.future.isCancelled()) {
                cancelSubscriber(subscriber);
            }
        });
        return subscriber.future;
    }

    private void cancelSubscriber(Subscriber subscriber)
    {
        synchronized (lock) {
            SharedOperation shared = subscriber.shared;
            if (shared == null || !shared.subscribers.remove(subscriber)) {
                return;
            }
            if (!shared.subscribers.isEmpty()) {
                return;
            }
            Batch batch = shared.batch;
            if (shared.queued) {
                pending.remove(shared);
            }
            sharedOperations.remove(shared.operation, shared);
            shared.queued = false;
            if (batch != null && batch.operations.stream().allMatch(operation -> operation.subscribers.isEmpty())) {
                if (batch.response.isPresent()) {
                    batch.response.orElseThrow().cancel(true);
                }
                else {
                    finishBatchLocked(batch);
                }
            }
        }
    }

    private void scheduleDrainLocked()
    {
        if (!drainScheduled && !closed) {
            drainScheduled = true;
            scheduler.schedule(this::drain, BATCH_COALESCING_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    private void drain()
    {
        Batch batch;
        synchronized (lock) {
            drainScheduled = false;
            if (closed || activeBatches >= policy.maximumConcurrency() || pending.isEmpty()) {
                return;
            }
            int wireBatchSize = batchingEnabled ? policy.maximumBatchSize() : 1;
            List<SharedOperation> operations = new ArrayList<>();
            while (operations.size() < wireBatchSize && !pending.isEmpty()) {
                SharedOperation operation = pending.removeFirst();
                operation.queued = false;
                if (!operation.subscribers.isEmpty()) {
                    operations.add(operation);
                }
            }
            if (operations.isEmpty()) {
                scheduleDrainLocked();
                return;
            }
            batch = new Batch(List.copyOf(operations));
            operations.forEach(operation -> operation.batch = batch);
            activeBatches++;
            scheduleDrainLocked();
        }
        scheduler.schedule(() -> startBatch(batch), 0, TimeUnit.NANOSECONDS);
    }

    private void startBatch(Batch batch)
    {
        ProviderProfile provider;
        List<JsonRpcClient.JsonRpcRequest> requests;
        AttemptMetrics attemptMetrics;
        synchronized (lock) {
            if (closed || batch.finished) {
                return;
            }
            List<SharedOperation> activeOperations = batch.operations.stream()
                    .filter(operation -> !operation.subscribers.isEmpty())
                    .toList();
            batch.operations.stream()
                    .filter(operation -> operation.subscribers.isEmpty())
                    .forEach(this::removeSharedLocked);
            batch.operations = activeOperations;
            if (activeOperations.isEmpty()) {
                finishBatchLocked(batch);
                return;
            }
            if (!batch.permitAcquired) {
                long now = scheduler.nanoTime();
                long permit = Math.max(now, nextPermitNanos);
                nextPermitNanos = permit + TimeUnit.SECONDS.toNanos(1) / policy.rateLimitPolicy().requestsPerSecond();
                batch.permitAcquired = true;
                if (permit > now) {
                    scheduler.schedule(() -> startBatch(batch), permit - now, TimeUnit.NANOSECONDS);
                    return;
                }
            }
            ProviderSelection selection = selectProviderLocked(batch.providerOffset);
            if (selection.provider().isEmpty()) {
                scheduler.schedule(() -> startBatch(batch), selection.waitNanos(), TimeUnit.NANOSECONDS);
                return;
            }
            provider = selection.provider().orElseThrow();
            requests = new ArrayList<>();
            for (int index = 0; index < batch.operations.size(); index++) {
                RemoteOperation operation = batch.operations.get(index).operation;
                requests.add(new JsonRpcClient.JsonRpcRequest(index, operation.method(), operation.parameters()));
            }
            attemptMetrics = new AttemptMetrics();
            batch.operations.forEach(operation -> operation.subscribers.forEach(subscriber -> attemptMetrics.addScope(subscriber.scope, operation)));
            batch.attemptMetrics = attemptMetrics;
            batch.requestStartNanos = scheduler.nanoTime();
            metrics.requestStarted(requests.size());
        }

        CompletableFuture<List<JsonNode>> response;
        try {
            JsonRpcTransport transport = transports.get(provider.name());
            if (provider.capabilities().supportsJsonRpcBatch()) {
                response = transport.executeBatch(requests);
            }
            else {
                if (requests.size() != 1) {
                    throw new IllegalStateException("non-batch provider received multiple operations");
                }
                response = transport.execute(requests.getFirst()).thenApply(List::of);
            }
        }
        catch (RuntimeException e) {
            response = CompletableFuture.failedFuture(e);
        }
        synchronized (lock) {
            batch.response = Optional.of(response);
            if (closed || batch.operations.stream().allMatch(operation -> operation.subscribers.isEmpty())) {
                response.cancel(true);
            }
        }
        response.whenComplete((values, failure) -> {
            if (failure == null) {
                completeBatch(batch, provider, values);
            }
            else {
                failBatch(batch, provider, unwrap(failure));
            }
        });
    }

    private void completeBatch(Batch batch, ProviderProfile provider, List<JsonNode> values)
    {
        synchronized (lock) {
            if (batch.finished) {
                return;
            }
            finishAttemptLocked(batch);
            for (int index = 0; index < batch.operations.size(); index++) {
                SharedOperation operation = batch.operations.get(index);
                RemoteResult result = new RemoteResult(values.get(index), provider.name());
                operation.subscribers.forEach(subscriber -> subscriber.future.complete(result));
                removeSharedLocked(operation);
            }
            finishBatchLocked(batch);
        }
    }

    private void failBatch(Batch batch, ProviderProfile provider, Throwable failure)
    {
        synchronized (lock) {
            if (batch.finished) {
                return;
            }
            AttemptMetrics attemptMetrics = finishAttemptLocked(batch);
            if (batch.operations.stream().allMatch(operation -> operation.subscribers.isEmpty())) {
                batch.operations.forEach(this::removeSharedLocked);
                finishBatchLocked(batch);
                return;
            }
            metrics.failure();
            attemptMetrics.failure();
            if (failure instanceof JsonRpcClient.JsonRpcHttpException httpFailure && httpFailure.statusCode() == 429) {
                metrics.throttled();
                attemptMetrics.throttled();
            }
            if (isRetryable(failure) && batch.attempt < policy.retryPolicy().maximumAttempts()) {
                unhealthyUntilNanos.put(provider.name(), scheduler.nanoTime() + policy.providerCooldown().toNanos());
                batch.attempt++;
                batch.providerOffset++;
                batch.permitAcquired = false;
                metrics.retry();
                attemptMetrics.retry();
                if (providers.size() > 1) {
                    metrics.failover();
                    attemptMetrics.failover();
                }
                Duration delay = retryDelay(failure, batch.attempt - 1);
                scheduler.schedule(() -> startBatch(batch), delay.toNanos(), TimeUnit.NANOSECONDS);
                return;
            }
            batch.operations.forEach(operation -> {
                operation.subscribers.forEach(subscriber -> subscriber.future.completeExceptionally(failure));
                removeSharedLocked(operation);
            });
            finishBatchLocked(batch);
        }
    }

    private AttemptMetrics finishAttemptLocked(Batch batch)
    {
        AttemptMetrics attemptMetrics = requireNonNull(batch.attemptMetrics, "attemptMetrics is null");
        long latencyNanos = Math.max(0, scheduler.nanoTime() - batch.requestStartNanos);
        metrics.requestFinished(latencyNanos);
        attemptMetrics.requestFinished(latencyNanos);
        batch.attemptMetrics = null;
        batch.response = Optional.empty();
        return attemptMetrics;
    }

    private ProviderSelection selectProviderLocked(int offset)
    {
        long now = scheduler.nanoTime();
        long earliest = Long.MAX_VALUE;
        for (int index = 0; index < providers.size(); index++) {
            ProviderProfile provider = providers.get((offset + index) % providers.size());
            long unhealthyUntil = unhealthyUntilNanos.getOrDefault(provider.name(), 0L);
            if (unhealthyUntil <= now) {
                return new ProviderSelection(Optional.of(provider), 0);
            }
            earliest = Math.min(earliest, unhealthyUntil);
        }
        return new ProviderSelection(Optional.empty(), Math.max(1, earliest - now));
    }

    private void finishBatchLocked(Batch batch)
    {
        if (!batch.finished) {
            batch.finished = true;
            activeBatches--;
            scheduleDrainLocked();
        }
    }

    private void removeSharedLocked(SharedOperation operation)
    {
        sharedOperations.remove(operation.operation, operation);
        operation.batch = null;
        operation.queued = false;
    }

    private Duration retryDelay(Throwable failure, int attempt)
    {
        if (failure instanceof JsonRpcClient.JsonRpcHttpException httpFailure && httpFailure.statusCode() == 429) {
            return httpFailure.retryAfter().flatMap(this::parseRetryAfter)
                    .map(value -> minDuration(value, policy.maximumBackoff()))
                    .orElseGet(() -> exponentialBackoff(attempt));
        }
        return exponentialBackoff(attempt);
    }

    private Duration exponentialBackoff(int attempt)
    {
        RetryPolicy retryPolicy = policy.retryPolicy();
        return minDuration(retryPolicy.initialBackoff().multipliedBy(1L << min(attempt - 1, 30)), retryPolicy.maximumBackoff());
    }

    private Optional<Duration> parseRetryAfter(String value)
    {
        try {
            long seconds = Long.parseLong(value);
            return seconds < 0 ? Optional.empty() : Optional.of(Duration.ofSeconds(seconds));
        }
        catch (NumberFormatException ignored) {
            try {
                Duration delay = Duration.between(scheduler.currentTime(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME));
                return Optional.of(delay.isNegative() ? Duration.ZERO : delay);
            }
            catch (DateTimeParseException invalidDate) {
                return Optional.empty();
            }
        }
    }

    private static Map<String, JsonRpcTransport> createTransports(
            HttpClient httpClient,
            List<ProviderProfile> providers,
            Duration requestTimeout,
            int maximumRequestBytes,
            int maximumResponseBytes)
    {
        requireNonNull(httpClient, "httpClient is null");
        requireNonNull(requestTimeout, "requestTimeout is null");
        Map<String, JsonRpcTransport> transports = new HashMap<>();
        for (ProviderProfile provider : requireNonNull(providers, "providers is null")) {
            if (transports.put(provider.name(), new JsonRpcClient(httpClient, provider.endpoint(), requestTimeout, maximumRequestBytes, maximumResponseBytes)) != null) {
                throw new IllegalArgumentException("provider names must be unique");
            }
        }
        return transports;
    }

    private static Duration minDuration(Duration left, Duration right)
    {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private static boolean isRetryable(Throwable failure)
    {
        if (failure instanceof JsonRpcClient.JsonRpcHttpException httpFailure) {
            return httpFailure.statusCode() == 429 || httpFailure.statusCode() >= 500;
        }
        return !(failure instanceof CancellationException) &&
                !(failure instanceof JsonRpcClient.JsonRpcResponseException) &&
                !(failure instanceof IllegalArgumentException) &&
                !(failure instanceof IllegalStateException);
    }

    private static Throwable unwrap(Throwable failure)
    {
        return failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
    }

    @Override
    public void close()
    {
        List<Subscriber> subscribers;
        List<CompletableFuture<List<JsonNode>>> responses;
        synchronized (lock) {
            closed = true;
            subscribers = sharedOperations.values().stream()
                    .flatMap(operation -> operation.subscribers.stream())
                    .toList();
            responses = sharedOperations.values().stream()
                    .map(operation -> operation.batch)
                    .filter(batch -> batch != null)
                    .distinct()
                    .flatMap(batch -> batch.response.stream())
                    .toList();
            sharedOperations.values().forEach(operation -> operation.subscribers.clear());
            sharedOperations.clear();
            pending.clear();
        }
        cacheLifecycleLock.writeLock().lock();
        try {
            cache.invalidateAll();
        }
        finally {
            cacheLifecycleLock.writeLock().unlock();
        }
        responses.forEach(response -> response.cancel(true));
        subscribers.forEach(subscriber -> subscriber.future.completeExceptionally(new CancellationException("RPC runtime is closed")));
        scheduler.close();
    }

    private static final class Subscriber
    {
        private final CompletableFuture<RemoteResult> future = new CompletableFuture<>();
        private final MetricScope scope;
        private SharedOperation shared;

        private Subscriber(MetricScope scope)
        {
            this.scope = scope;
        }
    }

    private static final class SharedOperation
    {
        private final RemoteOperation operation;
        private final List<Subscriber> subscribers = new ArrayList<>();
        private boolean queued = true;
        private Batch batch;

        private SharedOperation(RemoteOperation operation)
        {
            this.operation = operation;
        }
    }

    private static final class Batch
    {
        private List<SharedOperation> operations;
        private Optional<CompletableFuture<List<JsonNode>>> response = Optional.empty();
        private AttemptMetrics attemptMetrics;
        private long requestStartNanos;
        private int attempt = 1;
        private int providerOffset;
        private boolean permitAcquired;
        private boolean finished;

        private Batch(List<SharedOperation> operations)
        {
            this.operations = operations;
        }
    }

    private static final class AttemptMetrics
    {
        private final Map<MetricScope, Set<SharedOperation>> operationsByScope = new IdentityHashMap<>();
        private boolean finished;

        public void addScope(MetricScope scope, SharedOperation operation)
        {
            if (finished) {
                return;
            }
            Set<SharedOperation> operations = operationsByScope.computeIfAbsent(scope, ignored -> {
                scope.requestStarted(0);
                return Collections.newSetFromMap(new IdentityHashMap<>());
            });
            if (operations.add(operation)) {
                scope.batchItems(1);
            }
        }

        public void requestFinished(long latencyNanos)
        {
            finished = true;
            operationsByScope.keySet().forEach(scope -> scope.requestFinished(latencyNanos));
        }

        public void failure()
        {
            operationsByScope.keySet().forEach(MetricScope::failure);
        }

        public void retry()
        {
            operationsByScope.keySet().forEach(MetricScope::retry);
        }

        public void throttled()
        {
            operationsByScope.keySet().forEach(MetricScope::throttled);
        }

        public void failover()
        {
            operationsByScope.keySet().forEach(MetricScope::failover);
        }
    }

    private static final class MetricScope
    {
        private final AtomicLong requestCount = new AtomicLong();
        private final AtomicLong failureCount = new AtomicLong();
        private final AtomicLong retryCount = new AtomicLong();
        private final AtomicLong throttledCount = new AtomicLong();
        private final AtomicLong inFlightRequests = new AtomicLong();
        private final AtomicLong failoverCount = new AtomicLong();
        private final AtomicLong requestLatencyNanos = new AtomicLong();
        private final AtomicLong batchCount = new AtomicLong();
        private final AtomicLong batchItemCount = new AtomicLong();
        private final AtomicLong cacheHitCount = new AtomicLong();
        private final AtomicLong cacheMissCount = new AtomicLong();
        private final AtomicLong cacheRevalidationCount = new AtomicLong();
        private final AtomicLong cacheBytesRead = new AtomicLong();
        private final AtomicLong cacheBytesWritten = new AtomicLong();

        public void requestStarted(long batchItems)
        {
            requestCount.incrementAndGet();
            inFlightRequests.incrementAndGet();
            batchCount.incrementAndGet();
            batchItemCount.addAndGet(batchItems);
        }

        public void batchItems(long batchItems)
        {
            batchItemCount.addAndGet(batchItems);
        }

        public void requestFinished(long latencyNanos)
        {
            inFlightRequests.decrementAndGet();
            requestLatencyNanos.addAndGet(latencyNanos);
        }

        public void failure()
        {
            failureCount.incrementAndGet();
        }

        public void retry()
        {
            retryCount.incrementAndGet();
        }

        public void throttled()
        {
            throttledCount.incrementAndGet();
        }

        public void failover()
        {
            failoverCount.incrementAndGet();
        }

        public void cacheHit(long bytes)
        {
            cacheHitCount.incrementAndGet();
            cacheBytesRead.addAndGet(bytes);
        }

        public void cacheMiss()
        {
            cacheMissCount.incrementAndGet();
        }

        public void cacheRevalidation()
        {
            cacheRevalidationCount.incrementAndGet();
        }

        public void cacheWrite(long bytes)
        {
            cacheBytesWritten.addAndGet(bytes);
        }

        public RemoteExecutionMetrics snapshot()
        {
            return new RemoteExecutionMetrics(
                    requestCount.get(),
                    failureCount.get(),
                    retryCount.get(),
                    throttledCount.get(),
                    inFlightRequests.get(),
                    failoverCount.get(),
                    requestLatencyNanos.get(),
                    batchCount.get(),
                    batchItemCount.get(),
                    cacheHitCount.get(),
                    cacheMissCount.get(),
                    cacheRevalidationCount.get(),
                    cacheBytesRead.get(),
                    cacheBytesWritten.get());
        }
    }

    private record ProviderSelection(Optional<ProviderProfile> provider, long waitNanos) {}
}
