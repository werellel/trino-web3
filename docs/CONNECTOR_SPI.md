# Connector SPI contract

The connector targets Trino 475. Coordinator-side metadata and split planning
remain deterministic and do not perform RPC. Worker-side PageSources own the
cancellable `RemoteExecution` used for bounded EVM reads.

## Predicate pushdown

`block_number` equality and a single bounded range are fully enforced by the
connector and may be removed from the remaining constraint. Transaction
`hash` equality and discrete `IN` domains are normalized to lower-case only for
Ethereum RPC and cache identity. Because the exposed Trino type is `VARCHAR`,
the original hash domain remains as a residual constraint so Trino preserves
case-sensitive SQL comparison semantics.

The configured distinct hash limit is enforced while Metadata extracts the
domain. Extraction stops as soon as the limit is exceeded, before a large table
handle or split list can be constructed. Split planning repeats the check as a
defense for deserialized or externally constructed handles.

Unbounded block and transaction scans are rejected during split planning.
Planning handles and splits contain only immutable bounded logical state; they
never contain clients, caches, credentials, or cancellation resources.

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
