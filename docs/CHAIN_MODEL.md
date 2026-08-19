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
Ethereum-specific decoding. M3 does not implement either adapter; that work is
reserved for M4.
