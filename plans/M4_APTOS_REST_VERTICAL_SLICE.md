# Plan: M4 Aptos REST vertical slice

Status: implemented for `aptos.transactions`; `aptos.events` is implemented by
the follow-on `M4_APTOS_EVENTS_VERTICAL_SLICE.md` plan. Full M4 remains in
progress.

## Goal

Prove that the shared remote runtime is not JSON-RPC-only by executing one
bounded, native Aptos table through the same admission, retry, failover,
cancellation, single-flight, and metrics path used by Ethereum.

## Scope

- Execute immutable endpoint-relative `RestRemoteRequest` values through the
  shared runtime.
- Add a bounded asynchronous REST transport using the connector-owned HTTP
  client, request timeout, request-size limit, and response-size limit.
- Keep REST requests out of JSON-RPC wire batches while preserving queue,
  concurrency, rate, retry, provider health, failover, cancellation, and
  execution-scoped metrics behavior.
- Add a `trino-web3-aptos` module with a versioned native descriptor and
  executable adapter.
- Expose `aptos.transactions` with a required bounded `ledger_version` range.
- Execute `GET /v1/transactions?start=...&limit=...` and validate the complete
  response before producing rows.
- Configure Aptos primary and fallback REST endpoints independently of
  Ethereum endpoints.
- Keep metadata available when no endpoint is configured; fail data reads with
  a schema-specific configuration error.

## Non-goals

- Aptos events in this transaction-focused increment; blocks, resources,
  modules, and account transactions remain out of scope.
- Aptos writes, transaction submission, authentication headers, or
  provider-specific behavior.
- Aptos cache admission or a generic finality policy.
- REST wire batching, pagination beyond one bounded split, adaptive
  concurrency, or operator-supplied descriptors.
- Solana, Bitcoin, Tron, Sui, or Near execution.

## Affected modules

- Root POM: add `trino-web3-aptos` in dependency order.
- `trino-web3-runtime`: REST transport and protocol-neutral scheduling input.
- `trino-web3-aptos`: descriptor, split planning, response validation,
  decoding, and deterministic tests.
- `trino-web3-plugin`: compose both adapters, schema-specific runtimes, and
  Aptos endpoint configuration.
- `trino-web3-testing`: metadata, bounded Aptos query, failure, cancellation,
  and packaged-plugin tests.
- Architecture, runtime, chain model, testing, README, and roadmap status.

## Correctness risks

| Risk | Control |
| --- | --- |
| REST path selects another host or leaks credentials | Accept only validated endpoint-relative paths; endpoints remain catalog-owned and are never included in errors or metrics. |
| REST work bypasses runtime policy | Submit every request through the existing bounded queue and provider-selection state machine. |
| JSON-RPC and REST requests are coalesced | A runtime instance has one protocol; REST wire batch size is always one. |
| Cancellation leaves an HTTP request active | Cancelling the final subscriber cancels the transport future. |
| Large or malformed response escapes bounds | Read at most `maximumResponseBytes + 1` and parse JSON before adapter decoding. |
| Aptos uint64 silently overflows Trino BIGINT | Parse decimal strings exactly and reject values outside signed non-negative BIGINT. |
| Provider returns the wrong range or partial data | Require an array with exactly the requested contiguous versions before exposing rows. |
| Missing endpoint breaks catalog discovery | Build adapters and metadata independently from optional per-schema runtimes. |

## Required tests

- REST GET query encoding, bounded response parsing, 429/`Retry-After`, 5xx,
  malformed JSON, and cancellation.
- Runtime REST single-flight, queue/concurrency, retry, failover, metrics, and
  no-batch behavior.
- Aptos descriptor, exact split planning, response decoding, wrong version,
  partial response, malformed field, and BIGINT overflow.
- `SHOW SCHEMAS`, `SHOW TABLES FROM web3.aptos`, bounded SQL execution,
  projection, missing endpoint, cancellation, and packaged-plugin loading.
- Existing Ethereum, cache, finality, and ZIP execution regressions remain
  green without an external provider.

## Validation commands

```bash
mvn -pl trino-web3-runtime,trino-web3-aptos,trino-web3-plugin,trino-web3-testing -am test -DskipITs
mvn validate
mvn clean verify
git diff --check
unzip -Z1 trino-web3-plugin/target/trino-web3-plugin-0.1-SNAPSHOT-plugin.zip
```
