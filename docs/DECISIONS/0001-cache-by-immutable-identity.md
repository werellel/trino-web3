# Cache remote data by immutable identity

## Status

Accepted for M3 implementation.

## Context

EVM block numbers identify a position in the current canonical chain, not an
immutable block. A near-head reorganization can make the same number resolve to
a different hash. Caching block payloads directly by number can therefore
return stale canonical data.

The connector also needs bounded worker-local reuse without placing Ethereum
finality rules in the provider-independent RPC runtime. M2 already owns
asynchronous scheduling and single-flight, so cache loading must compose with
that work instead of introducing a blocking cache loader or executor.

## Decision

The first production cache is an opt-in, worker-local L1 memory cache built
with Trino 475's `EvictableCacheBuilder`. It is bounded by retained weight,
records statistics, has explicit disabled behavior, and may have an operational
TTL. L2 disk and distributed caches are not part of this decision.

The EVM adapter resolves finality and canonical identity. Block payloads are
stored by block hash, and finalized transaction payloads addressed by
transaction hash are stored by transaction hash. Pending or near-head
transaction results are revalidated because inclusion metadata can change. The
generic runtime does not infer finality from an RPC method or block number. A
finalized block number may retain a small canonical hash reference; safe and
head number references are revalidated on later scans.

Cache keys include a chain namespace, operation, canonical parameters,
representation, and format version. Values are bounded serialized JSON bytes,
not caller-visible mutable trees. Admission is two phase: the runtime returns
uncommitted successful work and the chain adapter commits it only after complete
decoding, finality, number, and hash validation. Missing values and all
transport, throttling, server, protocol, decoding, and cancellation failures
are not cached.

The chain namespace represents one configured catalog network, not a value
derived from every response. All primary and fallback endpoints in an M3
catalog must therefore address the same EVM chain. Endpoint-specific
`eth_chainId` verification requires a provider-targeted runtime operation and
is assigned to M5 configuration hardening; the EVM adapter must not open a
parallel HTTP path to implement it.

Cache lookup precedes the existing M2 shared-operation registry. A miss uses
that registry for asynchronous single-flight and preserves its subscriber-aware
cancellation semantics.

## Alternatives considered

### Cache block payloads by block number with a TTL

Rejected because no TTL proves correctness near the head. A reorg can occur
within the TTL and return stale canonical data.

### Cache only finalized block numbers

Rejected as the sole identity model. Although an honest finalized boundary
makes the mapping stable, hash-based payload identity is reusable for explicit
hash operations and makes the immutable/mutable boundary explicit. A separate
small finalized number-to-hash reference is allowed.

### Put Ethereum finality rules in the RPC runtime

Rejected because finality is chain-specific and would violate the documented
runtime/adapter boundary.

### Use a blocking loading cache

Rejected because it would block worker threads, duplicate M2 scheduling, and
make cancellation ownership ambiguous.

### Add L2 disk or a distributed cache in M3

Rejected for the first implementation. Persistent formats require atomic
writes, ownership, permission, versioning, corruption recovery, and cleanup
contracts. A distributed cache additionally changes consistency and failure
semantics. The roadmap marks L2 optional and does not require shared caching.

### Cache null or missing results briefly

Rejected for M3. Absence semantics differ across RPC methods and chain state;
omitting negative caching is the conservative behavior allowed by the roadmap.

## Consequences

Repeated finalized reads can avoid remote data operations after warm-up while
near-head reads continue to resolve the current canonical hash. Reorged hash A
may remain in the immutable cache, but a later number lookup can only reach hash
B and therefore remains correct.

The first query performs serialization and may do extra finality work. Cache
hits perform deserialization to isolate cached bytes from mutable consumers.
These costs require benchmark evidence.

The cache is not shared across workers or connector instances, so distributed
queries may warm each worker independently. Disabling the cache preserves M2
behavior. Disk persistence remains available for a later ADR without changing
the immutable identity contract.
