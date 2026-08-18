# Plan: M2 production RPC runtime

Status: implemented and validated locally with Trino 475.

## Goal

Execute bounded, read-only JSON-RPC operations through a reusable worker-local
runtime that batches work, enforces resource limits, retries only temporary
failures, fails over to a configured generic endpoint, and stops promptly when
the Trino page source is closed.

## Scope

- A runtime-owned operation model and immutable execution policies.
- Logical batching into bounded JSON-RPC wire batches.
- Shared, worker-local concurrency and queue bounds.
- Worker-local rate limiting, including `429` and `Retry-After` handling.
- Bounded exponential backoff, failure classification, generic provider health,
  primary-first selection, and failover.
- Single-flight deduplication for identical in-flight operations.
- Runtime metrics with worker-global physical counters and isolated execution
  scopes for Trino PageSources.
- EVM adapter migration from direct `JsonRpcClient` calls to the runtime.
- Deterministic unit, fault-injection, connector, and distributed tests.

## Non-goals

- Cache entries, negative caching, finality, or reorganization handling (M3).
- Vendor-specific provider profiles, headers, quotas, or capabilities.
- Solana, Aptos, new EVM tables, or changes to table metadata and split logic.
- Distributed quota coordination, adaptive batching/concurrency, or cost-based
  provider selection.
- Side-effecting RPC methods.

## Implemented design

`JsonRpcClient` serializes one caller-provided JSON-RPC batch and validates its
response IDs, message sizes, timeout, and cancellation. `RemoteExecutionRuntime`
owns admission and composes compatible operations into those batches.
`EthereumBlockClient` and `EthereumTransactionClient` submit immutable remote
operations through that runtime. The connector owns one runtime with configured
primary and fallback endpoints, execution policies, lifecycle shutdown, and
Trino PageSource metrics.

## Design

`trino-web3-runtime` will own `RemoteOperation`, `RemoteResult`, immutable
`ExecutionPolicy`, `RetryPolicy`, `RateLimitPolicy`, `ProviderCapabilities`,
and a generic `ProviderProfile`. A `RemoteExecutionRuntime` accepts individual
operations, deduplicates equal in-flight operations, queues them in a bounded
shared queue, groups compatible operations into bounded logical batches, then
uses `JsonRpcClient` as its transport implementation.

The transport supports both batch arrays and single-request JSON-RPC envelopes.
If any configured endpoint lacks batch capability, the worker uses physical
batches of one across the provider set so mixed-capability failover remains
correct.

One runtime is created per catalog connector instance and reused by all page
sources in that worker. It owns no per-query executor. A primary generic
endpoint is selected first; retryable transport, timeout, throttling, and 5xx
failures may use the next healthy endpoint. Invalid parameters, unsupported
methods, malformed responses, and JSON-RPC errors are terminal. A failed
provider is temporarily unavailable under a deterministic health policy, then
may be retried after its cooldown.

The existing `web3.ethereum.rpc-url` remains the primary endpoint. An optional
ordered fallback-endpoint property is added for M2. Runtime properties expose
only operationally necessary bounds: concurrency, queue length, batch item and
byte limits, retry count/backoff, rate budget, and provider cooldown. URLs and
their query/user-info components must not appear in errors, metrics, or logs.

## Affected modules

- `trino-web3-runtime`: operation/policy/provider models, scheduler, limiter,
  failure classification, single-flight registry, metrics, and HTTP transport
  adaptation.
- `trino-web3-evm`: construct chain-native JSON-RPC operations and decode the
  returned result without owning execution policy.
- `trino-web3-plugin`: parse and validate generic provider/runtime settings,
  construct and close the shared runtime, and preserve page-source cancellation.
- `trino-web3-testing`: reusable deterministic mock RPC fault behavior and
  end-to-end failover, throttling, and cancellation tests.
- `README.md`: supported configuration, generic-provider behavior, limits, and
  metrics contract.

## Execution flow

```text
EVM adapter operation
→ single-flight lookup
→ bounded shared queue
→ compatible logical batch
→ rate-limit permit
→ healthy generic provider selection
→ JSON-RPC wire batch
→ response classification
→ retry/backoff or ordered failover
→ immutable result to EVM decoder
```

## Correctness considerations

- Runtime code remains independent of Trino SPI, schemas, and EVM decoding.
- Batches retain request-ID matching; no response ordering assumption is added.
- Every operation has an explicit timeout, request/response byte limit, batch
  limit, queue limit, concurrency limit, rate budget, and retry limit.
- A retry never occurs for terminal user/protocol failures, and total attempts
  cannot exceed the configured bound across provider failover.
- Single-flight shares only an identical immutable operation key and never
  caches completed values or failures.
- Provider selection never treats a block height as immutable; M2 stores no
  blockchain data and leaves finality semantics to M3/EVM.

## Failure modes

| Failure | M2 behavior |
| --- | --- |
| Timeout or connection failure | Retry within attempt budget, then fail over if available. |
| HTTP 429 | Record throttling, honor valid `Retry-After` within policy bounds, then retry/fail over. |
| HTTP 5xx | Retry within policy and fail over if available. |
| HTTP 4xx other than 429 | Terminal unless explicitly classified otherwise by the generic profile. |
| JSON-RPC error, malformed response, partial batch failure | Terminal for the atomic wire batch; never silently substitute a result. |
| Queue/concurrency/rate budget exhaustion | Fail explicitly or wait only while the caller remains live; no unbounded admission. |

## Concurrency and cancellation

The catalog-owned runtime owns its bounded executor and shuts it down in
`Web3Connector.shutdown()`. Cancellation removes a queued operation where
possible; cancelling the final subscriber cancels the shared operation and its
in-flight HTTP future. One cancelled subscriber must not cancel work still
needed by another subscriber. No executor, HTTP client, or thread pool is
created per query or split.

## Resource bounds

Initial conservative defaults will be validated at connector creation:

- maximum in-flight wire requests: 16
- maximum queued operations: 1,024
- maximum logical batch items: 100
- existing maximum request/response byte limits
- maximum retry attempts: 3 total attempts
- worker-local request budget: 100 operations per second
- maximum backoff and valid `Retry-After`: 30 seconds
- provider unhealthy cooldown: 30 seconds

These values are configuration defaults, not adaptive behavior.

## Compatibility

Trino remains pinned to version 475. Existing single-endpoint catalogs remain
valid. New configuration is optional except that a real RPC query still needs a
primary endpoint. The plugin ZIP must continue to carry every runtime
dependency and load through Trino's plugin classloader.

## Testing

- Unit tests for operation equality, policy validation, batching, rate permits,
  retry classification, retry/backoff calculation, health transitions,
  failover ordering, and single-flight subscriber cancellation.
- JSON-RPC mock-server tests for out-of-order responses, 429 with and without
  `Retry-After`, 5xx, connection failure, malformed/partial batches, and size
  limits.
- Connector and distributed tests proving a bounded EVM query succeeds through
  a healthy provider, falls back after primary outage, and releases queued and
  in-flight work after cancellation.
- ZIP classloader execution test remains part of full verification.

## Observability

Expose execution-scoped runtime metrics for request count, failure count,
retry count, throttled count, latency, batch size, in-flight requests, and
provider failover count. The worker-global snapshot retains exact physical
counts while concurrent PageSources receive isolated scopes. Metrics never
contain URLs, credentials, request IDs, hashes, or addresses.

## Implementation steps

1. Add immutable runtime operation, policy, provider, outcome, and metric
   models with focused validation tests.
2. Extract protocol transport behind the minimal runtime-facing contract while
   retaining `JsonRpcClient` request-ID and size validation.
3. Implement the bounded shared scheduler, compatible batch planner, and
   single-flight lifecycle.
4. Add generic provider selection, health cooldown, failure classification,
   rate limiting, bounded retry/backoff, and cancellation propagation.
5. Move EVM clients to individual runtime operations and preserve decoding
   semantics.
6. Add connector configuration, lifecycle shutdown, and safe configuration
   error handling.
7. Add deterministic fault-injection, distributed, and ZIP execution tests;
   document configuration and metrics.

## Validation

```bash
mvn validate
mvn -pl trino-web3-runtime test
mvn -pl trino-web3-testing -am test
mvn verify
```

The verification suite demonstrates normal execution, bounded 429 retry,
primary-provider failure with fallback success, terminal invalid requests,
partial-batch failure, and queued/in-flight cancellation without public RPC
access. It also covers terminal HTTP failure, retry exhaustion, connection
failure fallback, explicit partial-batch failure, subscriber-independent
cancellation, queue-capacity release, provider cooldown, PageSource metrics,
catalog validation, distributed fallback, and packaged-plugin fallback.

## Documentation

Update `README.md`, `docs/RPC_RUNTIME.md`, and `docs/TESTING.md`. The currently
empty RPC runtime and cache/finality documents must be populated before M2 is
declared complete; M2 documents the cache boundary but does not implement cache
behavior.
