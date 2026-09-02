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

The EVM adapter is reused for Base (`0x2105`), Optimism (`0xa`), Arbitrum One
(`0xa4b1`), BNB Smart Chain (`0x38`), Polygon (`0x89`), and Avalanche C-Chain
(`0xa86a`). Each network is registered as an independent schema (`base`,
`optimism`, `arbitrum`, `bnb`, `polygon`, or `avalanche`) and uses a separate
runtime/cache namespace.

Both EVM tables include a `raw_json` `VARCHAR` containing the compact JSON for
the complete block or transaction object returned by the node. This preserves
additive provider fields without destabilizing the typed columns; callers can
apply Trino's `json_parse(raw_json)` and JSON functions for access to fields
that are not yet modeled. The JSON-RPC envelope is not included. A typed field
must be added to the descriptor (and its version bumped) only when stable
relational access is required.

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

The same raw-payload compatibility contract applies to every chain adapter:
each remote table includes a `raw_json` `VARCHAR` containing the compact JSON
source object for the emitted row. It preserves additive fields from JSON-RPC
and REST providers without requiring an immediate typed-column migration.

## Current Tron model

Tron uses its native HTTP API rather than an EVM JSON-RPC compatibility layer.
The `tron.blocks` and `tron.transactions` tables require a bounded BIGINT
`block_number` range. Each split issues `POST /wallet/getblockbynum` with the
requested number; block rows retain the block ID, timestamp, transaction
count, and complete native object, while transaction rows retain each native
`txID`, contract count, and complete transaction object. The adapter validates
that the returned block number and ID match the request before publishing
rows. Tron endpoint identity is established from the native
`GET /wallet/getnowblock` response shape, and REST requests use the shared
runtime for limits, retries, failover, cancellation, and metrics. Cache
admission remains disabled until a reorganization-safe block identity policy
is defined.

## Current Sui model

Sui uses its native JSON-RPC protocol. The `sui.checkpoints` and
`sui.transactions` tables require a bounded BIGINT
`checkpoint_sequence_number` range. Checkpoints are read with
`sui_getCheckpoint`; transaction rows retain each digest and are hydrated with
`sui_getTransactionBlock`. Every emitted row includes the complete native
object in `raw_json`, preserving additive fields. The adapter validates
checkpoint sequence numbers and transaction digests before publishing rows.
Endpoint identity is established with `sui_getChainIdentifier`. Sui cache
admission remains disabled until checkpoint finality and reorganization
identity semantics are explicitly defined.

## Current Cosmos-family model

Cosmos Hub, Osmosis, and Injective use separate adapters and descriptors while
sharing the Cosmos SDK REST block envelope. Their native `blocks` and
`transactions` tables require bounded BIGINT `height` ranges and read
`GET /cosmos/base/tendermint/v1beta1/blocks/{height}`. Block rows retain the
chain ID, header time, block hash, and transaction count. Transaction rows
retain each native base64 transaction with its block/index and `raw_json`.
Endpoint identity validates the expected chain-ID prefix (`cosmoshub-`,
`osmosis-`, or `injective-`). Cache admission remains disabled until chain
specific finality and reorganization identity rules are defined.

## Current Solana model

M4 exposes `solana.blocks`, `solana.transactions`, and
`solana.instructions` through bounded BIGINT `slot` ranges. Each operation is
a shared-runtime JSON-RPC `getBlock` call with `commitment=finalized`; a null
block result yields no rows. Blocks preserve `slot`, `blockhash`, `parent_slot`,
and optional `block_time`. Transactions preserve the primary signature,
success derived from the native `meta.err`, and fee. Instructions preserve the
compiled top-level instruction order, resolved program ID, account-index JSON,
and native base58 data. Inner instructions, parsed instruction variants, and
versioned account-key object forms are intentionally not in this initial table.

The initial Solana slice makes no cache admission. A future cache contract must
define canonical blockhash identity, commitment/finality semantics, and how a
slot lookup is revalidated before any completed result is retained.

## Current Aptos model

M4 exposes `aptos.transactions` and `aptos.events` without mapping either to
an EVM transaction or log. Transaction rows contain `ledger_version`, `hash`,
native transaction `type`, `success`, `vm_status`, and nullable `sender`.
Reads require a bounded BIGINT `ledger_version` range. The adapter caps every
REST page at 100 transactions, validates a complete contiguous response,
rejects unsigned 64-bit versions that cannot be represented by Trino BIGINT,
and produces rows only after the whole split is valid.

Event reads use `GET /v1/accounts/{account_address}/events/{creation_number}`
with exactly one account address, one creation number, and a bounded
`sequence_number` range. Event rows retain the native account address,
creation number, sequence number, type, and compact JSON payload text. A
keyed-range split carries the two stream keys plus the sequence range; it is not
an EVM log abstraction. Event responses must be complete, contiguous, and match
both the requested GUID and sequence numbers before rows are published.

The data client owns no endpoint, HTTP client, retry, rate, failover, or
provider-health policy. Complete committed transaction and event ranges have
adapter-defined immutable cache identities; the configured cache TTL is only an
operational bound, not a finality mechanism.

## Versioned descriptor contract

Each adapter supplies one immutable descriptor with separate versions for the
descriptor format, adapter release, and each table. Any changed descriptor
requires an adapter-version increment, and a changed existing table requires a
table-version increment. Existing tables cannot be removed through descriptor
evolution. Chain and schema identity cannot change across versions.

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

Before a new chain is exposed, complete
[the new chain adapter checklist](NEW_CHAIN_ADAPTER_CHECKLIST.md). Its design
must define:

- native schemas, tables, columns, and identifiers;
- bounded predicates and split-generation limits;
- projection and predicate capabilities;
- remote operation and batch capabilities;
- response validation and partial-failure behavior;
- finality, reorganization, pending-state, and immutable cache identity rules;
- deterministic mock protocol fixtures and cancellation tests.

Solana and Aptos adapters must model their native blocks, transactions,
instructions, events, and finality semantics. They must not reuse EVM tables or
Ethereum-specific decoding. Bitcoin-family, Tron, Sui, and Near adapters follow
the same registry boundary but keep UTXO, object, receipt, event, and finality
semantics in their own adapters. Production execution for Aptos
transactions/events, Solana blocks/transactions/instructions, and the
Bitcoin-family schemas is present. Bitcoin, Litecoin, Dogecoin, and Bitcoin
Cash each expose native UTXO-oriented `blocks`, `transactions`, `inputs`, and
`outputs` tables through bounded Core-compatible `getblockhash`/`getblock`
reads. Their node identity is checked using `getnetworkinfo.subversion` with a
chain-specific product prefix, while the shared decoder accepts only the
documented Core response shapes. Cache admission remains disabled for all four
until each chain has a reorganization-safe immutable identity contract.
