# Connector SPI contract

The connector targets Trino 475. Coordinator-side metadata and split planning
remain deterministic and do not perform remote calls. Worker-side PageSources
own the cancellable `RemoteExecution` used for bounded EVM and Solana JSON-RPC
and Aptos REST transaction and account-event reads.

## Predicate pushdown

Metadata evaluates one descriptor method at a time. Required `SPLIT` and
`PREDICATE` bindings define a bounded access path. A method is selected only
when all its required input bindings are satisfied by an existing handle value
or a supported Trino domain. Complete paths using more constrained columns are
preferred; discrete lookup wins a deterministic tie over a range lookup.

BIGINT equality and a single bounded range are supported for `SPLIT` bindings
and are fully enforced by adapter splits, so they may be removed from the
remaining constraint. VARCHAR equality and discrete `IN` domains are supported
for `PREDICATE` bindings. Their original domains remain as residual constraints
because identifier normalization and equality are chain-specific. Ethereum
transaction hashes are validated and normalized only after the generic
coordinator planning boundary, while Trino preserves case-sensitive SQL
comparison semantics.

The configured distinct hash limit is enforced while Metadata extracts the
domain. Extraction stops as soon as the limit is exceeded, before a large table
handle or split list can be constructed. Split planning repeats the check as a
defense for deserialized or externally constructed handles.

Unbounded block, transaction, and event scans are rejected during split
planning. Planning handles contain the selected descriptor method plus immutable
maps of named ranges and discrete values. A split is either a single native
range/discrete value or a bounded range scoped by a small immutable key map,
such as an Aptos account event stream. Neither contains clients, caches,
credentials, endpoints, or cancellation resources, and their diagnostic strings
expose counts rather than values.

## PageSource lifecycle

`isBlocked()` returns the remote execution future. `close()` cancels that
future, which propagates through the runtime subscriber and removes queued work
or cancels an unshared transport request. The execution context rejects cache
admission after cancellation.

PageSources publish execution-scoped RPC and cache metrics. While a completed
decoded row list is owned by the PageSource, `getMemoryUsage()` reports an
estimate for its records and strings. Cache-hit JSON retained during an
incomplete execution is reported by the remote execution and is bounded by the
maximum RPC response size.
