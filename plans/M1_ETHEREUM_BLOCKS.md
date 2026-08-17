# Plan: Ethereum bounded blocks and transactions scan

## Goal

Execute bounded `web3.ethereum.blocks` and `web3.ethereum.transactions`
block-number range queries against a deterministic local Ethereum JSON-RPC
mock and return rows as Trino pages.

## Scope

- `ethereum` schema and `blocks` table
- `block_number` and `block_hash` columns
- `transactions` table with `hash`, `block_number`, `from_address`, and
  `to_address` columns
- equality and bounded range predicate pushdown for `block_number`
- deterministic, configurable block-range splits
- bounded JSON-RPC batch requests for `eth_getBlockByNumber`
- response decoding, timeout, and cancellation propagation
- unit and integration tests with no external RPC endpoint

## Non-goals

- receipts, logs, Solana, or Aptos
- cache, finality, reorg handling, retry, rate limiting, or provider failover
- adaptive batching, adaptive split sizing, or cost-based planning
- public provider connectivity tests

## Current state

M0 provides a Trino 475 plugin, an empty `web3` catalog, and catalog-loading
and packaged-plugin tests. No chain schema, table handle, split, page source,
or RPC module exists.

## Proposed design

Add the smallest modules that preserve the documented dependency direction:

```text
trino-web3-plugin
    -> trino-web3-core
    -> trino-web3-evm
    -> trino-web3-runtime
```

`trino-web3-core` owns connector planning objects and Trino SPI
implementations. `trino-web3-evm` owns the EVM table definition, method
mapping, and block decoder. `trino-web3-runtime` owns a bounded HTTP
JSON-RPC execution path only; production retry, rate limiting, cache, and
failover remain M2/M3 work. `trino-web3-testing` owns the mock RPC server and
fixtures.

## Execution flow

```text
SQL block_number predicate
-> Web3Metadata.applyFilter
-> immutable table handle with bounded range
-> Web3SplitManager deterministic splits
-> Web3PageSourceProvider on a worker
-> Ethereum blocks or transactions adapter
-> bounded JSON-RPC batch
-> EthereumBlockDecoder
-> Trino Page
```

No metadata operation performs RPC I/O. A query without a block-number
equality or bounded range fails before split creation.

## Correctness and resource bounds

- A split contains an inclusive, non-empty block range with a configured
  maximum size.
- Split generation is deterministic and bounded.
- Each wire request has an explicit timeout and batch-size limit.
- JSON-RPC batch responses are matched by ID, never array order.
- A response block number must match its requested block number.
- Missing blocks and malformed responses are explicit query failures or empty
  semantic results, as defined by the table contract; transport failures are
  never treated as missing blocks.
- Cancellation closes or cancels in-flight remote work and prevents further
  page production.

## Tests

- schema, table, and exact column metadata discovery
- exact table metadata and column types
- equality/range pushdown and unbounded-scan rejection
- split boundary and maximum-range tests
- normal batch decoding and reversed response ordering
- malformed response and timeout behavior
- cancellation behavior
- local distributed Trino end-to-end bounded blocks and transactions queries
  against the mock RPC server

## Implementation steps

1. Add core, runtime, EVM, and shared-testing Maven modules and preserve the
   M0 plugin boundary.
2. Add immutable table, column, range, and split handles plus metadata and
   split-manager tests.
3. Add the deterministic mock JSON-RPC server and EVM block fixtures.
4. Implement a bounded runtime request/batch API with timeout and
   cancellation ownership.
5. Implement EVM block and transaction request mapping and decoding.
6. Add the page source and local Trino end-to-end test.
7. Run `mvn verify` and update the README with the M1 query contract.

## Validation

```bash
mvn verify
```

The validation must include a query equivalent to:

```sql
SELECT block_number, block_hash
FROM web3.ethereum.blocks
WHERE block_number BETWEEN 23000000 AND 23000100;
```

The test must use only the local mock endpoint.
