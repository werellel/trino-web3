# RPC Runtime

The runtime executes bounded, read-only remote JSON-RPC and REST operations without
depending on Trino SPI classes or chain table metadata. Chain adapters own
method selection and response decoding; the runtime owns batching, admission,
generic-provider selection, retry, cancellation, and metrics.

Descriptors may inventory either JSON-RPC or REST operations. The runtime
uses transport-neutral request identity plus immutable, bounded JSON-RPC and
endpoint-relative REST request values. JSON-RPC operations may be coalesced
into protocol batches; REST operations always use one wire request. Both use
the same queue, concurrency, rate, retry, provider health, failover,
single-flight, cancellation, and metric state machine. REST request values cannot select an
endpoint, supply headers, or carry an absolute/network-path URL. Descriptors
never own HTTP clients, endpoints, credentials, retry, rate, failover, cache,
or cancellation policy.

## Bounds

The default worker-local `ExecutionPolicy` has 16 concurrent wire requests, a
1,024-item queue, 100 operations per logical batch, three total attempts, and
100 admitted requests per second. Backoff and valid `Retry-After` delays are
bounded to 30 seconds. These are static conservative limits, not adaptive
policies.

All limits are catalog properties under `web3.rpc.*` and have hard validation
ceilings. Admission is scheduled asynchronously: rate waiting never sleeps on
an execution worker. Individual operations enter a bounded queue, identical
operations share an in-flight result, and compatible queued operations are
coalesced into JSON-RPC batches. REST requests are admitted individually and
never consume an execution worker while waiting for a rate permit.

Provider capabilities control the wire envelope. When every configured
provider supports JSON-RPC batches, compatible operations may share a batch
array. If any primary or fallback endpoint is configured as non-batching, the
runtime uses one operation per wire request so failover never sends an
unsupported envelope. Every initial request and retry obtains a separate
asynchronous rate permit.

Logical callers may submit single-operation executions even when two values
are needed together. The scheduler still coalesces compatible operations when
the configured maximum batch size permits it. This allows EVM finality reads to
work correctly when `web3.rpc.maximum-batch-size=1` without bypassing runtime
batch planning.

## Failure handling

Timeouts, connection failures, HTTP 429, and HTTP 5xx are retryable within the
attempt budget. A valid `Retry-After` value takes precedence for HTTP 429.
Other HTTP 4xx responses, JSON-RPC errors, malformed responses, and partial
batch failures are terminal. Batch decoding is atomic: if any response in a
wire batch is invalid or contains a JSON-RPC error, every operation in that
wire batch fails explicitly and the batch is not retried. A retryable primary
failure tries configured generic fallback endpoints in declaration order.
Standard JSON-RPC error codes, including invalid parameters (`-32602`) and
unsupported methods (`-32601`), are preserved for classification without
copying provider error messages that could echo request data.

## Metrics

The runtime maintains exact worker-local physical request counters and creates
an isolated metric scope for every chain-adapter execution. A scope records
only wire attempts containing one of its live operations. A late single-flight
subscriber joins the active attempt's scope explicitly. Trino PageSources
publish these scoped counters, so unrelated concurrent splits do not appear in
each other's metrics.

`RemoteExecutionRuntime.snapshot()` exposes only node-local runtime counters
for operator system tables. It is local-only and never probes a provider. Its
provider state is intentionally narrow: `AVAILABLE` means not in the generic
runtime cooldown, while `COOLDOWN` means temporarily avoided after a retryable
failure. Neither state proves reachability or expected chain identity.

Each physical attempt also updates exactly one generated provider-role counter.
These counters appear in `system.providers`; their schema-wide aggregate appears
in `system.rpc_metrics`. Page-source metric entries retain their isolated
execution scope and use stable names documented in [METRICS.md](METRICS.md).
No metric label is derived from an endpoint, vendor, method, request, identity,
or query.
Catalog-time endpoint identity probes are included in runtime snapshots because
they use this same bounded execution path; they are not query page-source work.

## Cancellation and lifecycle

Each configured chain schema owns one runtime and the catalog connector closes all of them in
`Connector.shutdown()`. Cancelling a page source cancels its future and the
in-flight HTTP request when no longer needed. The runtime does not create an
executor or HTTP client per query or split.

One connector-owned JDK `HttpClient` is shared by all configured runtimes so
connection reuse remains catalog-scoped. Connector construction closes every
runtime already created if a later runtime or endpoint identity check fails.
Shutdown is idempotent: it cancels work, clears the runtime cache, and stops
the scheduler without relying on garbage collection.

Each single-flight subscriber is independently cancellable. A queued operation
is removed as soon as its last subscriber cancels. A wire batch is cancelled
only after every operation in that batch has no remaining subscribers.

## Cache integration

The connector optionally uses a bounded, worker-local result cache owned by
each configured runtime.
The runtime owns storage, serialized-value isolation, weight/entry limits,
optional TTL, eviction statistics, and execution-scoped cache counters. It
does not decide whether a response is immutable. The EVM adapter validates a
response, resolves its finality and canonical identity, and explicitly admits
it only after the complete logical result is valid. Aptos admits only complete,
validated committed ledger and event ranges with adapter-defined identities.
The Solana vertical slice intentionally performs no cache admission until its
slot/hash identity and reorganization contract are defined by the adapter.

Cache misses continue through the runtime's asynchronous single-flight
scheduler.
Closing the runtime cancels queued/in-flight work, clears retained cache
entries, and closes the scheduler. Cache keys and metrics contain no endpoint,
credential, query ID, address, or provider-specific dimension.

Cache JSON serialization and deserialization do not run while holding the RPC
scheduler lock. A separate cache lifecycle lock coordinates connector shutdown,
so a large bounded cache value cannot delay queue admission, cancellation, or
single-flight bookkeeping. Each execution has a cache-read budget capped by
the configured maximum RPC response size.

Adapter admission is execution-cancellation aware. Serialization may finish
after cancellation begins, but the final cache insertion is serialized with
the execution cancellation state and is skipped once cancellation wins.

Provider network identity is not inferred by the generic runtime. Connector
construction asks each chain adapter for a native identity probe and executes
it once against every configured provider role through the provider-targeted
runtime path. Ethereum uses `eth_chainId`, Solana uses `getGenesisHash`, and
Aptos uses `GET /v1` and its `chain_id`. The adapter validates and
canonicalizes the identity; the connector requires all results to match. A
mismatch, malformed result, or unavailable endpoint rejects catalog creation
with a sanitized error. Targeted probes use the existing request limits and
bounded retry policy, but never fail over or publish a provider cooldown;
failed construction closes the temporary runtime. This also validates a
single configured endpoint before the runtime is published.
