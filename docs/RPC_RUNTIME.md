# RPC Runtime

The M2 runtime executes bounded, read-only remote JSON-RPC operations without
depending on Trino SPI classes or chain table metadata. Chain adapters own
method selection and response decoding; the runtime owns batching, admission,
generic-provider selection, retry, cancellation, and metrics.

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
coalesced into JSON-RPC batches.

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

## Cancellation and lifecycle

Each catalog connector owns one runtime and closes it in
`Connector.shutdown()`. Cancelling a page source cancels its future and the
in-flight HTTP request when no longer needed. The runtime does not create an
executor or HTTP client per query or split.

Each single-flight subscriber is independently cancellable. A queued operation
is removed as soon as its last subscriber cancels. A wire batch is cancelled
only after every operation in that batch has no remaining subscribers.

## Cache integration

M3 adds a bounded, worker-local result cache owned by the catalog runtime.
The runtime owns storage, serialized-value isolation, weight/entry limits,
optional TTL, eviction statistics, and execution-scoped cache counters. It
does not decide whether a response is immutable. The EVM adapter validates a
response, resolves its finality and canonical identity, and explicitly admits
it only after the complete logical result is valid.

Cache misses continue through M2's asynchronous single-flight scheduler.
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

Provider network identity is not inferred by the generic runtime. M3 requires
all endpoints in a catalog to address the same chain. M5 will add a
chain-adapter operation executed through a provider-targeted runtime path so
endpoint-by-endpoint identity verification does not bypass transport ownership.
