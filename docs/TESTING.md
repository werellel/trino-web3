# Testing

Runtime and connector tests use local deterministic `HttpServer` fixtures only.
They must not call public RPC providers or require credentials.

M2 fault tests cover malformed JSON, timeout, request/response byte bounds,
primary-provider 5xx failover, HTTP 429 with `Retry-After`, and single-flight
sharing of identical in-flight work. They additionally cover terminal HTTP 4xx,
retry exhaustion, connection failure, explicit partial-batch failure, provider
cooldown, subscriber-independent cancellation, and queued-work removal.
Connector tests cover bounded EVM queries, PageSource metrics, catalog security
validation, distributed fallback, and cancellation. The packaged-plugin
integration test extracts the distribution ZIP, loads it with a Trino plugin
classloader, and executes a non-batch fallback query against local mock RPC
servers.

A controllable in-memory scheduler and transport validate cooldown and rate
admission without wall-clock sleeps. The deterministic suite also covers mixed
batch capabilities, execution-scoped metric isolation, maximum concurrency,
queue overflow, 429 without `Retry-After`, 429 exhaustion, unsupported methods,
shutdown during backoff, and cancellation of the underlying transport future.

Trino 475 may log a late remote-task callback rejection while a standalone test
server is closing. This is test-harness teardown noise after query completion;
Failsafe results and connector resource cleanup remain authoritative.
