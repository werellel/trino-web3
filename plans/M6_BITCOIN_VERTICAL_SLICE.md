# Bitcoin vertical slice

## Scope

Add a first native Bitcoin adapter without changing the generic runtime. The
initial slice exposes bounded Bitcoin Core JSON-RPC reads for:

* `bitcoin.blocks` (block header summary);
* `bitcoin.transactions` (transaction-level UTXO metadata);
* `bitcoin.inputs` (vin rows); and
* `bitcoin.outputs` (vout rows with integer satoshi values).

The adapter uses `getblockhash(height)` followed by bounded `getblock(hash,
verbosity)` calls. It never scans an unbounded height range and does not model
Bitcoin inputs/outputs as Ethereum transactions or logs.

## Contracts

* Add a dedicated `trino-web3-bitcoin` Maven module and built-in versioned
  descriptor.
* Use one catalog-owned `web3.bitcoin.rpc-url` plus validated fallback URLs.
* Validate endpoint identity with `getblockchaininfo.chain` and require all
  configured endpoints to agree.
* Keep retry, batching, rate limits, failover, cancellation, and transport in
  `RemoteExecutionRuntime`.
* Use bounded non-negative `height` predicates for all initial tables.
* Keep cache admission disabled until Bitcoin finality and reorg identity are
  explicitly defined; no negative caching.

## Affected modules

* root/module POMs and plugin assembly;
* `trino-web3-bitcoin` adapter, descriptor, and deterministic decoder tests;
* `trino-web3-plugin` factory, connector registry, metadata, and endpoint
  wiring;
* `trino-web3-testing` mock Bitcoin Core integration and ZIP loading checks;
* chain model, configuration, testing, and README documentation.

## Validation

* Unit tests cover height split bounds, satoshi conversion, malformed/missing
  fields, coinbase inputs, and response identity.
* Connector tests cover `SHOW TABLES FROM web3.bitcoin`, bounded block and
  transaction/input/output queries, fallback, cancellation, and exact rows.
* Packaged-plugin tests load the Bitcoin adapter through the assembled ZIP.
* Run `mvn --batch-mode --errors -Dtrino.version=475 clean verify` with local
  mock servers only.
