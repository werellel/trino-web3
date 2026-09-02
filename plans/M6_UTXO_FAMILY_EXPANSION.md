# Bitcoin-family adapter expansion

## Scope

Extend the Bitcoin vertical slice to Litecoin, Dogecoin, and Bitcoin Cash while
preserving native UTXO relations and the provider-independent runtime. The four
schemas expose the same bounded `blocks`, `transactions`, `inputs`, and
`outputs` table contract because their Core-compatible RPC payloads have the
same execution shape; each chain still has its own descriptor and adapter.

## Contracts

* Keep `trino-web3-utxo` limited to transport-neutral planning and decoding.
* Validate node product identity from `getnetworkinfo.subversion` using a
  chain-specific matcher before a configured endpoint is used.
* Use separate catalog endpoint properties and runtime instances for each
  chain; do not infer one chain's endpoint from another.
* Require bounded `height` or `block_height` predicates and retain the existing
  request, response, retry, rate, and concurrency limits.
* Keep cache admission disabled until immutable block identity and reorg rules
  are defined independently for each chain.

## Validation

Unit tests cover descriptor loading, bounded split planning, node identity, the
legacy `addresses` response shape, and exact satoshi conversion. Connector
tests cover catalog/schema/table discovery, endpoint identity rejection, and
the assembled plugin ZIP's class loading and `SHOW TABLES` results for all
four schemas. Tests use local mocks only.
