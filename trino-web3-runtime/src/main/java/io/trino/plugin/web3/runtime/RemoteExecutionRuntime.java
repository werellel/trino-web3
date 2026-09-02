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

/** Shared worker-local scheduler for bounded, read-only remote operations. */
public final class RemoteExecutionRuntime
        implements AutoCloseable
{
    private static final long BATCH_COALESCING_MILLIS = 1;

    private final Object lock = new Object();
    private final ReentrantReadWriteLock cacheLifecycleLock = new ReentrantReadWriteLock();
    private final List<ProviderProfile> providers;
    private final Map<String, JsonRpcTransport> jsonRpcTransports;
    private final Map<String, RestTransport> restTransports;
    private final RemoteRequest.Protocol protocol;
    private final ExecutionPolicy policy;
    private final RemoteExecutionScheduler scheduler;
    private final RemoteResultCache cache;
    private final int maximumCacheReadBytes;
    private final boolean batchingEnabled;
    private final ArrayDeque<SharedOperation> pending = new ArrayDeque<>();
    private final Map<SharedOperationKey, SharedOperation> sharedOperations = new HashMap<>();
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
                Map.of(),
                RemoteRequest.Protocol.JSON_RPC,
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
                Map.of(),
                RemoteRequest.Protocol.JSON_RPC,
                policy,
                new ExecutorRemoteExecutionScheduler(),
                cacheConfig,
                maximumResponseBytes);
    }

    public static RemoteExecutionRuntime forRest(
            HttpClient httpClient,
            List<ProviderProfile> providers,
            Duration requestTimeout,
            int maximumRequestBytes,
            int maximumResponseBytes,
            ExecutionPolicy policy,
            RemoteCacheConfig cacheConfig)
    {
        return new RemoteExecutionRuntime(
                providers,
                Map.of(),
                createRestTransports(httpClient, providers, requestTimeout, maximumRequestBytes, maximumResponseBytes),
                RemoteRequest.Protocol.REST,
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
        this(providers, transports, Map.of(), RemoteRequest.Protocol.JSON_RPC, policy, scheduler, RemoteCacheConfig.disabled(), 1);
    }

    RemoteExecutionRuntime(
            List<ProviderProfile> providers,
            Map<String, JsonRpcTransport> transports,
            ExecutionPolicy policy,
            RemoteExecutionScheduler scheduler,
            RemoteCacheConfig cacheConfig)
    {
        this(providers, transports, Map.of(), RemoteRequest.Protocol.JSON_RPC, policy, scheduler, cacheConfig, cacheConfig.maximumEntryBytes());
    }

    RemoteExecutionRuntime(
            List<ProviderProfile> providers,
            Map<String, JsonRpcTransport> transports,
            ExecutionPolicy policy,
            RemoteExecutionScheduler scheduler,
            RemoteCacheConfig cacheConfig,
            int maximumCacheReadBytes)
    {
        this(providers, transports, Map.of(), RemoteRequest.Protocol.JSON_RPC, policy, scheduler, cacheConfig, maximumCacheReadBytes);
    }

    static RemoteExecutionRuntime forRest(
            List<ProviderProfile> providers,
            Map<String, RestTransport> transports,
            ExecutionPolicy policy,
            RemoteExecutionScheduler scheduler)
    {
        return new RemoteExecutionRuntime(
                providers,
                Map.of(),
                transports,
                RemoteRequest.Protocol.REST,
                policy,
                scheduler,
                RemoteCacheConfig.disabled(),
                1);
    }

    private RemoteExecutionRuntime(
            List<ProviderProfile> providers,
            Map<String, JsonRpcTransport> jsonRpcTransports,
            Map<String, RestTransport> restTransports,
            RemoteRequest.Protocol protocol,
            ExecutionPolicy policy,
            RemoteExecutionScheduler scheduler,
            RemoteCacheConfig cacheConfig,
            int maximumCacheReadBytes)
    {
        this.providers = List.copyOf(requireNonNull(providers, "providers is null"));
        if (this.providers.isEmpty()) {
            throw new IllegalArgumentException("providers is empty");
        }
        this.jsonRpcTransports = Map.copyOf(requireNonNull(jsonRpcTransports, "jsonRpcTransports is null"));
        this.restTransports = Map.copyOf(requireNonNull(restTransports, "restTransports is null"));
        this.protocol = requireNonNull(protocol, "protocol is null");
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
            boolean transportPresent = switch (protocol) {
                case JSON_RPC -> this.jsonRpcTransports.containsKey(provider.name());
                case REST -> this.restTransports.containsKey(provider.name());
            };
            if (!transportPresent) {
                throw new IllegalArgumentException("provider transport is missing");
            }
        }
        batchingEnabled = protocol == RemoteRequest.Protocol.JSON_RPC && this.providers.stream()
                .allMatch(provider -> provider.capabilities().supportsJsonRpcBatch());
    }

    public CompletableFuture<RemoteResult> execute(RemoteOperation operation)
    {
        return execute((RemoteRequest) operation);
    }

    public CompletableFuture<RemoteResult> execute(RemoteRequest request)
    {
        return execute(request, new MetricScope(), Optional.empty());
    }

    /** Executes a bounded request on one configured provider without failover. */
    public CompletableFuture<RemoteResult> executeOnProvider(String providerName, RemoteRequest request)
    {
        requireNonNull(providerName, "providerName is null");
        return execute(request, new MetricScope(), Optional.of(providerName));
    }

    /** Returns generated provider roles without exposing endpoints. */
    public List<String> providerNames()
    {
        return providers.stream()
                .map(ProviderProfile::name)
                .toList();
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

    /**
     * Returns a local, immutable observability snapshot. It never probes a
     * provider and deliberately excludes endpoints and request data.
     */
    public RemoteRuntimeSnapshot snapshot()
    {
        List<RemoteRuntimeSnapshot.ProviderSnapshot> providerSnapshots;
        synchronized (lock) {
            long now = scheduler.nanoTime();
            providerSnapshots = providers.stream()
                    .map(provider -> providerSnapshot(provider, now))
                    .toList();
        }
        return new RemoteRuntimeSnapshot(
                protocol,
                policy,
                cache.isEnabled(),
                metrics(),
                cache.metrics(),
                providerSnapshots);
    }

    private RemoteRuntimeSnapshot.ProviderSnapshot providerSnapshot(ProviderProfile provider, long now)
    {
        long remainingNanos = Math.max(0, unhealthyUntilNanos.getOrDefault(provider.name(), 0L) - now);
        long remainingMillis = remainingNanos == 0 ? 0 : Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
        return new RemoteRuntimeSnapshot.ProviderSnapshot(
                provider.name(),
                provider.capabilities().supportsJsonRpcBatch(),
                remainingNanos == 0 ? RemoteRuntimeSnapshot.ProviderSnapshot.State.AVAILABLE : RemoteRuntimeSnapshot.ProviderSnapshot.State.COOLDOWN,
                remainingMillis);
    }

    private RemoteExecution<List<RemoteResult>> executeBatchWithMetrics(List<RemoteOperation> operations, MetricScope scope)
    {
        if (operations.size() > policy.maximumBatchSize()) {
            return new RemoteExecution<>(CompletableFuture.failedFuture(new IllegalArgumentException("logical batch exceeds maximumBatchSize")), scope::snapshot);
        }
        List<CompletableFuture<RemoteResult>> futures = operations.stream()
                .map(operation -> execute(operation, scope, Optional.empty()))
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

        public RemoteExecution<RemoteResult> execute(RemoteRequest request)
        {
            CompletableFuture<RemoteResult> future = RemoteExecutionRuntime.this.execute(request, scope, Optional.empty());
            trackCancellation(future);
            return new RemoteExecution<>(future, scope::snapshot);
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

    private CompletableFuture<RemoteResult> execute(RemoteRequest operation, MetricScope scope, Optional<String> targetProvider)
    {
        requireNonNull(operation, "operation is null");
        requireNonNull(targetProvider, "targetProvider is null");
        if (operation.protocol() != protocol) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("remote request protocol does not match runtime protocol"));
        }
        Subscriber subscriber = new Subscriber(scope);
        synchronized (lock) {
            if (closed) {
                return CompletableFuture.failedFuture(new IllegalStateException("RPC runtime is closed"));
            }
            if (targetProvider.isPresent() && providers.stream().noneMatch(provider -> provider.name().equals(targetProvider.orElseThrow()))) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("unknown provider"));
            }
            SharedOperationKey key = new SharedOperationKey(operation, targetProvider);
            SharedOperation shared = sharedOperations.get(key);
            if (shared == null) {
                if (pending.size() >= policy.maximumQueueSize()) {
                    return CompletableFuture.failedFuture(new IllegalStateException("RPC execution queue is full"));
                }
                shared = new SharedOperation(key);
                sharedOperations.put(key, shared);
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
            sharedOperations.remove(shared.key, shared);
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
            List<SharedOperation> operations = collectBatchOperations(wireBatchSize);
            if (operations.isEmpty()) {
                scheduleDrainLocked();
                return;
            }
            batch = new Batch(List.copyOf(operations), operations.getFirst().targetProvider);
            operations.forEach(operation -> operation.batch = batch);
            activeBatches++;
            scheduleDrainLocked();
        }
        scheduler.schedule(() -> startBatch(batch), 0, TimeUnit.NANOSECONDS);
    }

    private void startBatch(Batch batch)
    {
        ProviderProfile provider;
        List<RemoteRequest> requests;
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
            ProviderSelection selection = selectProviderLocked(batch.providerOffset, batch.targetProvider);
            if (selection.provider().isEmpty()) {
                scheduler.schedule(() -> startBatch(batch), selection.waitNanos(), TimeUnit.NANOSECONDS);
                return;
            }
            provider = selection.provider().orElseThrow();
            requests = batch.operations.stream()
                    .map(operation -> operation.operation)
                    .toList();
            attemptMetrics = new AttemptMetrics();
            batch.operations.forEach(operation -> operation.subscribers.forEach(subscriber -> attemptMetrics.addScope(subscriber.scope, operation)));
            batch.attemptMetrics = attemptMetrics;
            batch.requestStartNanos = scheduler.nanoTime();
            metrics.requestStarted(requests.size());
        }

        CompletableFuture<List<JsonNode>> response;
        try {
            response = switch (protocol) {
                case JSON_RPC -> executeJsonRpc(provider, requests);
                case REST -> executeRest(provider, requests);
            };
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

    private CompletableFuture<List<JsonNode>> executeJsonRpc(ProviderProfile provider, List<RemoteRequest> operations)
    {
        List<JsonRpcClient.JsonRpcRequest> requests = new ArrayList<>();
        for (int index = 0; index < operations.size(); index++) {
            if (!(operations.get(index) instanceof RemoteOperation operation)) {
                throw new IllegalStateException("JSON-RPC runtime received a non-JSON-RPC request");
            }
            requests.add(new JsonRpcClient.JsonRpcRequest(index, operation.method(), operation.parameters()));
        }
        JsonRpcTransport transport = jsonRpcTransports.get(provider.name());
        if (provider.capabilities().supportsJsonRpcBatch()) {
            return transport.executeBatch(requests);
        }
        if (requests.size() != 1) {
            throw new IllegalStateException("non-batch provider received multiple operations");
        }
        return transport.execute(requests.getFirst()).thenApply(List::of);
    }

    private CompletableFuture<List<JsonNode>> executeRest(ProviderProfile provider, List<RemoteRequest> requests)
    {
        if (requests.size() != 1 || !(requests.getFirst() instanceof RestRemoteRequest request)) {
            throw new IllegalStateException("REST runtime received an invalid wire batch");
        }
        CompletableFuture<JsonNode> response = restTransports.get(provider.name()).execute(request);
        CompletableFuture<List<JsonNode>> result = response.thenApply(List::of);
        result.whenComplete((value, failure) -> {
            if (result.isCancelled()) {
                response.cancel(true);
            }
        });
        return result;
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
            if (failure instanceof RemoteHttpException httpFailure && httpFailure.statusCode() == 429) {
                metrics.throttled();
                attemptMetrics.throttled();
            }
            if (isRetryable(failure) && batch.attempt < policy.retryPolicy().maximumAttempts()) {
                // A provider-targeted operation is used for catalog-time identity
                // validation. It must retry the same endpoint without waiting for a
                // health cooldown that would only be useful to normal failover.
                if (batch.targetProvider.isEmpty()) {
                    unhealthyUntilNanos.put(provider.name(), scheduler.nanoTime() + policy.providerCooldown().toNanos());
                }
                batch.attempt++;
                if (batch.targetProvider.isEmpty()) {
                    batch.providerOffset++;
                }
                batch.permitAcquired = false;
                metrics.retry();
                attemptMetrics.retry();
                if (batch.targetProvider.isEmpty() && providers.size() > 1) {
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

    private List<SharedOperation> collectBatchOperations(int wireBatchSize)
    {
        List<SharedOperation> operations = new ArrayList<>();
        while (!pending.isEmpty() && operations.isEmpty()) {
            SharedOperation operation = pending.removeFirst();
            operation.queued = false;
            if (!operation.subscribers.isEmpty()) {
                operations.add(operation);
            }
        }
        if (operations.isEmpty()) {
            return operations;
        }
        Optional<String> targetProvider = operations.getFirst().targetProvider;
        java.util.Iterator<SharedOperation> iterator = pending.iterator();
        while (iterator.hasNext() && operations.size() < wireBatchSize) {
            SharedOperation operation = iterator.next();
            if (operation.targetProvider.equals(targetProvider)) {
                iterator.remove();
                operation.queued = false;
                if (!operation.subscribers.isEmpty()) {
                    operations.add(operation);
                }
            }
        }
        return operations;
    }

    private ProviderSelection selectProviderLocked(int offset, Optional<String> targetProvider)
    {
        long now = scheduler.nanoTime();
        if (targetProvider.isPresent()) {
            ProviderProfile provider = providers.stream()
                    .filter(candidate -> candidate.name().equals(targetProvider.orElseThrow()))
                    .findFirst()
                    .orElseThrow();
            long unhealthyUntil = unhealthyUntilNanos.getOrDefault(provider.name(), 0L);
            return unhealthyUntil <= now ? new ProviderSelection(Optional.of(provider), 0) : new ProviderSelection(Optional.empty(), unhealthyUntil - now);
        }
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
        sharedOperations.remove(operation.key, operation);
        operation.batch = null;
        operation.queued = false;
    }

    private Duration retryDelay(Throwable failure, int attempt)
    {
        if (failure instanceof RemoteHttpException httpFailure && httpFailure.statusCode() == 429) {
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

    private static Map<String, RestTransport> createRestTransports(
            HttpClient httpClient,
            List<ProviderProfile> providers,
            Duration requestTimeout,
            int maximumRequestBytes,
            int maximumResponseBytes)
    {
        requireNonNull(httpClient, "httpClient is null");
        requireNonNull(requestTimeout, "requestTimeout is null");
        Map<String, RestTransport> transports = new HashMap<>();
        for (ProviderProfile provider : requireNonNull(providers, "providers is null")) {
            if (transports.put(provider.name(), new RestClient(httpClient, provider.endpoint(), requestTimeout, maximumRequestBytes, maximumResponseBytes)) != null) {
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
        if (failure instanceof RemoteHttpException httpFailure) {
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
        private final SharedOperationKey key;
        private final RemoteRequest operation;
        private final Optional<String> targetProvider;
        private final List<Subscriber> subscribers = new ArrayList<>();
        private boolean queued = true;
        private Batch batch;

        private SharedOperation(SharedOperationKey key)
        {
            this.key = key;
            operation = key.operation();
            targetProvider = key.targetProvider();
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
        private final Optional<String> targetProvider;
        private boolean permitAcquired;
        private boolean finished;

        private Batch(List<SharedOperation> operations, Optional<String> targetProvider)
        {
            this.operations = operations;
            this.targetProvider = targetProvider;
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

    private record SharedOperationKey(RemoteRequest operation, Optional<String> targetProvider)
    {
        private SharedOperationKey
        {
            requireNonNull(operation, "operation is null");
            requireNonNull(targetProvider, "targetProvider is null");
        }
    }

    private record ProviderSelection(Optional<ProviderProfile> provider, long waitNanos) {}
}
