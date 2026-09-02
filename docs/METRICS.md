# Metrics

`trino-web3` exposes metrics through Trino 475's `ConnectorPageSource` metrics
SPI and through read-only `system` table snapshots. It does not register a
plugin-global JMX MBean or an exporter. Trino owns collection and export of
page-source metrics, while the connector owns only bounded local counters.

## Page-source metrics

Every Web3 page source returns these stable `Count` entries in its Trino
`Metrics` map. They are scoped to that page-source execution, so concurrent
splits do not contaminate each other.

| Metric | Meaning |
| --- | --- |
| `web3.rpc.requests` | Physical wire attempts started |
| `web3.rpc.failures` | Failed wire attempts |
| `web3.rpc.retries` | Retry decisions after retryable failures |
| `web3.rpc.throttled` | HTTP 429 wire attempts |
| `web3.rpc.in-flight` | Current in-flight wire attempts for this execution |
| `web3.rpc.failovers` | Failover decisions after retryable failures |
| `web3.rpc.latency-nanos` | Sum of completed wire-attempt latency in nanoseconds |
| `web3.rpc.batches` | Physical wire attempts started |
| `web3.rpc.batch-items` | Logical operations placed in wire attempts |
| `web3.cache.hits` | Cache hits |
| `web3.cache.misses` | Cache misses |
| `web3.cache.revalidations` | Adapter-requested cache revalidations |
| `web3.cache.bytes-read` | Serialized cache bytes read |
| `web3.cache.bytes-written` | Serialized cache bytes admitted |

`in-flight` is a current gauge represented by the Trino `Count` SPI type for
uniform query-metric transport. It should be interpreted only within one
snapshot, not summed across independently sampled page sources.

## Runtime snapshots

`system.rpc_metrics` provides schema-wide physical-attempt counters for the
local connector runtime. `system.providers` carries the same RPC counters for
each generated role (`primary`, `fallback-N`) together with its local cooldown
state. A physical attempt updates exactly one provider row and the corresponding
schema-wide aggregate.

For schemas with fallback endpoints, catalog-time native identity probes use the
same bounded runtime and are therefore included in these runtime counters.
They never appear in a query's page-source metrics.

All values are node-local since a connector runtime is owned by one Trino node.
They reset when that catalog runtime is recreated. System-table reads never
issue RPC requests.

## Security and cardinality

Metric names are fixed. The only provider dimension is the connector-generated,
bounded role; it is not an endpoint URL or vendor name. Metrics never contain
credentials, endpoint origins, method names, parameters, request IDs, SQL query
text, hashes, addresses, identity values, or provider error messages.
