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
classloader, proves that Trino's cache library is packaged, and executes a
cache-enabled non-batch fallback query against local mock RPC servers.

M3 tests cover weight eviction, maximum-entry bypass, caller-value isolation,
cache lifecycle cleanup, scoped metric isolation, finalized block and
transaction reuse, hash normalization, pending and missing transactions,
unsupported and inverted finality, malformed and missing block non-admission,
near-head block and transaction reorganization, bounded transaction-hash
equality/IN pushdown, per-query hash limits, and worker-local distributed
warm-up. The distributed test permits one cold load per execution node and
asserts remote request reduction rather than assuming a cluster-global cache.
Focused regressions prove that a warm finalized query performs no finality or
data RPC, the finality snapshot refreshes after its deterministic deadline,
cancelled executions reject late admission, decoded cache reads respect an
execution byte budget, PageSources report retained row memory, and uppercase
hash literals remain subject to Trino's case-sensitive residual predicate.
The suite also covers deterministic TTL expiry, finality with a logical batch
limit of one, Metadata-stage hash-limit rejection, and repeated cache-enabled
timeout, HTTP 429, and partial-batch failures with zero admission.

A controllable in-memory scheduler and transport validate cooldown and rate
admission without wall-clock sleeps. The deterministic suite also covers mixed
batch capabilities, execution-scoped metric isolation, maximum concurrency,
queue overflow, 429 without `Retry-After`, 429 exhaustion, unsupported methods,
shutdown during backoff, and cancellation of the underlying transport future.

Trino 475 may log a late remote-task callback rejection while a standalone test
server is closing. This is test-harness teardown noise after query completion;
Failsafe results and connector resource cleanup remain authoritative.

M4 descriptor tests cover strict JSON round trips, unknown fields, unsupported
and trailing format input, registry ordering and duplicate ownership, immutable
addition/removal, adapter and table evolution versions (including rejected
table removal), protocol-specific
JSON-RPC/REST invariants, duplicate definitions and references, tolerant
provider response evolution, required-field failure without payload leakage,
explicit response-row limits, and Ethereum's exact built-in descriptor
contract. Connector tests prove that multiple native descriptors produce
independent Trino schemas and tables. Executable-adapter tests cover registry
composition, immutable scans and rows, Ethereum's exact range and discrete
split planning, bounded-query rejection, and descriptor-named block and
transaction rows. Existing local-RPC connector tests exercise the
registry-dispatched PageSource path, including metrics, memory, cancellation,
cache, non-batch execution, and failover. The packaged-plugin integration test
also loads all chain contract modules from the assembled ZIP. Metadata-registry
construction rejects unsupported declared types without exposing descriptor
values.

Descriptor-driven pushdown tests use non-EVM native names (`ledger_version` and
`signature`) to prove that required access-path bindings produce named range or
discrete predicates without schema-name branching. They also prove exact range
enforcement, conservative discrete residuals, missing-required-binding
rejection, deterministic method choice, handle immutability, bounded diagnostic
output, and EVM-side hash validation.

The Aptos M4 suite covers the strict built-in descriptor, the native
`ledger_version` and account-event stream access paths, the 100-entry REST page
cap, deterministic split planning, complete contiguous response validation,
optional sender, event GUID/sequence identity, malformed fields, signed BIGINT
overflow, and payload-safe failures. REST runtime tests cover URI/query
construction, GET and POST bodies, request and response bounds, malformed JSON,
HTTP 429 metadata, single-flight, no wire batching, retry/failover, protocol
mismatch, and transport cancellation. Trino integration tests prove Aptos
schema/table discovery, bounded transaction and event SQL execution, compact
JSON-text event payload projection, boolean and nullable page writing,
unbounded-scan rejection before remote work, and an explicit missing-endpoint
failure. All fixtures are local.

`BenchmarkRemoteResultCache` is a JMH benchmark for cache hit, miss, and
serialization/admission costs at 1 KiB and 64 KiB payload sizes. Generate its
test classes and classpath, then run it with the same Java 23 used by Maven:

```bash
mvn -pl trino-web3-runtime clean test-compile
mvn -pl trino-web3-runtime dependency:build-classpath \
    -Dmdep.includeScope=test \
    -Dmdep.outputFile=target/jmh-classpath.txt
JAVA_23_BIN=/path/to/java-23/bin/java
"$JAVA_23_BIN" -cp "trino-web3-runtime/target/test-classes:trino-web3-runtime/target/classes:$(< trino-web3-runtime/target/jmh-classpath.txt)" \
    org.openjdk.jmh.Main '.*BenchmarkRemoteResultCache.*'
```
