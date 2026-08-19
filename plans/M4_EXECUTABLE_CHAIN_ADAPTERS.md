# Plan: M4 executable chain adapter dispatch

## Goal

Make the existing Ethereum split and PageSource path execute through the same
registered code-based adapter that supplies its versioned descriptor, without
changing query results, bounds, cache behavior, finality, metrics, or
cancellation.

## Scope

- Add a small Trino-independent adapter execution API.
- Represent pushed range and discrete predicates as an immutable `ChainScan`.
- Let code-based adapters plan bounded `ChainSplit` values.
- Let worker data clients return named, immutable `ChainRow` values.
- Route split planning and PageSource execution by schema through an immutable
  executable-adapter registry.
- Make Ethereum implement the new planning and data-client contracts.
- Add a transport-neutral remote-request value boundary while keeping the
  production runtime JSON-RPC-only in this increment.
- Preserve existing Trino handles and accept legacy Ethereum split values
  while emitting generic column-preserving split values for new planning.

## Non-goals

- REST transport or Aptos execution.
- Solana, Bitcoin, Tron, Sui, or Near tables.
- Operator-provided adapter packs or query-time hot reload.
- A universal blockchain row or finality model.
- Generic pushdown for descriptor-only tables.
- Moving provider, retry, rate, cache, or cancellation policy into adapters.

## Dependency direction

```text
chain descriptor API
        ↑
adapter execution API → RPC runtime
        ↑                  ↑
     EVM adapter ──────────┘
        ↑
connector core / Trino SPI
```

The adapter API contains no Trino SPI and owns no transport implementation.
`ChainRow` values are named Java scalar values decoded by a chain adapter;
Trino type writing remains connector-core responsibility.

## Correctness risks

- Unknown schemas or tables must fail before remote work.
- A descriptor table without executable planning and data access must not be
  exposed by the executable registry.
- Ethereum hash and block-range limits must remain enforced in the adapter.
- Column handles must be validated against the registered descriptor before a
  PageSource is created.
- Nullable values must remain distinguishable from a missing declared column.
- Closing the generic PageSource must cancel the original remote execution.
- Metrics and memory accounting must remain execution-scoped.
- REST request values must reject unsafe methods, absolute URLs, fragments,
  credentials, and unbounded bodies before a transport exists.

## Tests

- Registry rejects descriptor-only and duplicate executable adapters.
- Ethereum planner produces exact bounded range and transaction-hash splits.
- Ethereum data client returns descriptor-named rows for both tables.
- Generic PageSource projection, null writing, memory, metrics, and
  cancellation regressions pass.
- Unknown table, split, and column combinations fail without remote calls.
- JSON-RPC and REST remote-request values are immutable and strictly bounded.
- Existing catalog, distributed, cache, and packaged-plugin tests remain green.

## Validation

```bash
mvn -pl trino-web3-adapter,trino-web3-evm,trino-web3-plugin,trino-web3-testing -am test
mvn validate
mvn verify
git diff --check
```
