# Cache and finality

The runtime's single-flight registry shares only identical in-flight work and
removes each entry when that work completes or the last subscriber cancels.
Errors are never stored as missing data.

The connector optionally enables a worker-local L1 memory cache. The runtime owns bounded cache
mechanics and integration with single-flight. The EVM adapter owns finality,
canonical hash resolution, and the decision that a value is immutable. The
generic runtime must not interpret Ethereum tags, confirmations, block numbers,
or reorganization rules.

## Identity

Block payloads are cached by canonical block hash, never directly by a mutable
near-head block number. A finalized number may retain a small reference to its
canonical hash. `HEAD` and `SAFE` number lookups are revalidated on later scans.

Transaction results are cacheable only when addressed by transaction hash and
their inclusion block is `FINALIZED`. Pending or near-head transaction objects
are revalidated because their inclusion metadata may change during a reorg.
The existing transaction block-range scan may reuse a full block response whose
key includes the block hash and `fullTransactions=true` representation.

Keys contain a chain namespace, operation, canonical parameters,
representation, and a format version. EVM quantities use lower-case minimal
hexadecimal and fixed-size hashes use lower case. Secrets, URLs, provider names,
request IDs, query IDs, addresses, and raw hashes never become metric labels.
One catalog is therefore required to contain endpoints for one EVM chain.
Endpoint-specific `eth_chainId` probes validate this configuration through the
runtime rather than adding provider transport to the EVM adapter.

## Values and bounds

Cache values are bounded serialized JSON bytes admitted only after the chain
adapter validates and decodes the complete result. A caller receives a newly
decoded tree, so mutating it cannot corrupt the retained value. The cache has a
maximum retained weight, a maximum entry size, size-based eviction, and an
optional operational TTL. Oversized successful results bypass caching.

Admission is two phase: the runtime produces uncommitted successful work and
the adapter explicitly commits it with a validated immutable key. This prevents
an RPC-valid but chain-invalid payload from being cached while keeping decoding
out of the generic runtime. A logical batch decoder failure admits none of that
batch.

The initial cache is L1 only. L2 worker-local disk remains optional and requires
a separate persistence/corruption ADR. No shared distributed cache is planned
for the current cache contract.

## Finality

The generic classifications are:

```text
HEAD
SAFE
FINALIZED
```

For EVM, the adapter resolves the standard `safe` and `finalized` tags. Invalid,
inverted, or unsupported boundaries cannot make data more cacheable; the
adapter falls back conservatively to `HEAD` behavior. It does not silently
invent a confirmation-depth policy.

The EVM adapter retains a completed boundary snapshot for at most one second.
An older finalized or safe height can only classify newly finalized data more
conservatively, while avoiding a finality RPC for every warm split. Unsupported
or malformed boundary results use the same short lifetime so an incompatible
provider is not probed for every split and is retried promptly.

The preferred lookup is:

```text
block number
-> classify finality
-> resolve or revalidate canonical block hash
-> read/write immutable payload by hash
```

For a near-head reorg from hash A to hash B, the old A entry may remain as valid
immutable historical content, but the next number lookup revalidates and
returns B. The number is never a permanent alias to A.

### Aptos

Aptos' selected REST endpoints return committed ledger history only: the
connector does not expose pending transactions. For this connector,
`ledger_version` identifies one immutable committed transaction record within
the configured Aptos network. A transaction-range response is cacheable only
when every response item has the requested contiguous ledger version and all
projected required fields validate. Its cache identity is the catalog-local
`aptos` namespace, the REST operation, inclusive `start`/`end` ledger versions,
the projected REST representation, and a format version.

An Aptos event identity is `(account_address, creation_number,
sequence_number)`. The address is canonicalized to lower-case shortest Aptos
hex form, while creation and sequence numbers are canonical unsigned decimal
values. Event-range entries are cacheable only when every returned `guid`
matches the requested address and creation number and sequence numbers are
contiguous. The event cache key contains that canonical stream identity,
inclusive sequence range, representation, and format version.

Committed Aptos history is treated as `FINALIZED` for cache admission; Aptos
REST does not provide an EVM-like `head`/`safe`/`finalized` tag distinction for
these historical range endpoints. The current bounded scans never request a
moving head alias, so no mutable-head key or TTL correctness mechanism is
needed. The configured TTL, when present, remains an operational upper bound,
not a finality mechanism. When Aptos has primary and fallback origins, catalog
creation verifies their `GET /v1` `chain_id` values match before any runtime is
published. The worker-local runtime keeps cache entries isolated by connector
instance and never includes origins or secrets in a key.

### Solana

The initial Solana `getBlock` vertical slice requests `commitment=finalized`,
but does not admit any response to the cache. A finalized commitment alone does
not define a cache identity: a later admission policy must key immutable payload
by canonical `blockhash`, define how a `slot` lookup is revalidated, and cover
unavailable/null block results. Until that contract exists, repeated Solana
scans execute through the normal bounded runtime path and null results are not
negative cached.

## Admission and failure

Only an adapter-committed, fully validated successful RPC result is admitted.
The cache never stores cancellation, timeout, connection failure, HTTP 429,
HTTP 4xx/5xx,
JSON-RPC error, partial batch failure, malformed response, oversized response,
decoder failure, or a missing/null block. The current cache does not implement negative
caching.

The same prohibition applies to Aptos partial ledger/event ranges, wrong ledger
versions, mismatched event GUIDs, non-contiguous sequences, and malformed
required fields. A rejected Aptos response is neither cached nor treated as a
negative result.

The same prohibition applies to all Solana responses in this slice because the
adapter performs no Solana cache admission.

If a retained value cannot be decoded, the entry is invalidated and the normal
bounded remote path is used. Cache failure is never reported as remote absence
and does not consume the RPC retry budget.

## Cancellation and lifecycle

A cache miss uses the runtime's asynchronous single-flight registry. Independent
subscriber cancellation is preserved, and cancelling the last subscriber does
not admit incomplete work. Cache hits schedule no worker task. One cache-bearing
runtime is owned and closed by the connector; there is no per-query cache,
executor, HTTP client, or thread pool.

Every execution bounds the total serialized size of decoded cache hits by the
configured maximum RPC response size. A hit that would exceed the remaining
budget bypasses the cache and follows the normal bounded remote path. Cache
JSON work uses a lifecycle lock independent of the scheduler lock. Admission
checks an execution cancellation state after serialization and immediately
before insertion, so a cancelled execution cannot perform a late commit.

Worker-global entry count, retained weight, and eviction count remain separate
from PageSource metrics. The system snapshot exposes their local runtime state through
`system.cache_stats`; the table does not expose cache keys or values.

The cache identity decision is recorded in
`docs/DECISIONS/0001-cache-by-immutable-identity.md`.
