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

## M3 boundary

The runtime does not cache results. Single-flight only shares identical
in-flight work and removes the entry after success, failure, or cancellation.
Finality and reorganization semantics remain outside the runtime until M3.
