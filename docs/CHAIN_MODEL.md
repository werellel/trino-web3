# Chain adapter contract

Chain adapters preserve a chain's native data model while translating bounded
logical scans into provider-independent remote operations. They sit between
connector core and RPC runtime:

```text
Trino SPI and connector core
→ chain adapter
→ RPC runtime
→ provider transport
```

An adapter owns table definitions, native type mappings, RPC method selection,
response validation and decoding, and chain-specific finality and reorg rules.
It does not open HTTP connections or own retry, rate, batching, failover,
single-flight, or cache storage policy.

## Current EVM model

M3 exposes the native EVM schema through `ethereum.blocks` and
`ethereum.transactions`. Reads require either a bounded `block_number` range or,
for transactions, a bounded equality or `IN` predicate on transaction hash.
The adapter converts those constraints into Ethereum JSON-RPC operations and
validates response identity before producing rows.

EVM finality uses `HEAD`, `SAFE`, and `FINALIZED` classifications. Only a
finalized block number may retain a canonical number-to-hash reference. Block
payloads are cached by block hash, and transaction-hash results are cached only
after their inclusion block is finalized. Near-head data and pending
transactions are revalidated. Finality semantics remain in the EVM adapter;
the generic runtime treats cache keys and response values as opaque.

Ethereum metadata is loaded from the built-in
`web3.trino.io/v1alpha1` descriptor. The descriptor is the authoritative list
of tables, columns, JSON-RPC methods, bounded input bindings, and response field
mappings. The registered `EthereumChainAdapter` remains authoritative for
bounded split planning, decoding, finality, and reorganization behavior.
Connector split planning and PageSource creation dispatch through that
executable adapter by schema. Adapter splits are transport-neutral range or
discrete-value records; their column identity is preserved across Trino split
serialization.

## Versioned descriptor contract

Each adapter supplies one immutable descriptor with separate versions for the
descriptor format, adapter release, and each table. Any changed descriptor
requires an adapter-version increment, and a changed existing table requires a
table-version increment. Chain and schema identity cannot change across
versions.

The initial declarative surface supports JSON-RPC and read-oriented REST
methods, split/predicate/projection/literal bindings, response cardinality, and
JSON Pointer field mappings. It deliberately excludes transport ownership,
credentials, retries, provider selection, finality rules, scripting, and
query-time reload. This lets providers add unrelated response fields without
breaking a table while preserving strict validation of connector-owned
contracts. Declarative response mapping also requires an explicit row limit;
the chain's bounded split or pagination policy supplies that limit.

The descriptor and executable contracts are intentionally complementary. A
descriptor defines metadata, method inventory, restricted bindings, and simple
response mappings. A code adapter implements bounded planning, complex
decoding, finality, reorganization, and validation. Only executable adapters
are placed in the production execution registry, so descriptor-only metadata
cannot accidentally expose a table with no data path.

Coordinator predicate planning uses each method's required `SPLIT` and
`PREDICATE` bindings as an access-path contract. The generic handle records the
selected method and named predicates; it does not contain `blockRange`,
`transactionHashes`, or another chain-specific field. Current generic domain
extraction deliberately supports only bounded BIGINT ranges and bounded VARCHAR
equality/`IN` sets. Chain adapters remain responsible for identifier syntax,
normalization, multi-column semantics, and rejecting a handle that does not
match the selected method.

## Adding a chain

Before a new chain is exposed, its design must define:

- native schemas, tables, columns, and identifiers;
- bounded predicates and split-generation limits;
- projection and predicate capabilities;
- remote operation and batch capabilities;
- response validation and partial-failure behavior;
- finality, reorganization, pending-state, and immutable cache identity rules;
- deterministic mock protocol fixtures and cancellation tests.

Solana and Aptos adapters must model their native blocks, transactions,
instructions, events, and finality semantics. They must not reuse EVM tables or
Ethereum-specific decoding. Bitcoin, Tron, Sui, and Near follow the same
registry boundary but keep UTXO, object, receipt, event, and finality semantics
in their own adapters. Production execution for these chains remains later M4
work.
