<!--
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# Query examples

This document contains executable examples for the chain schemas currently
provided by `trino-web3`. The examples assume the catalog is named `web3`.
Configure the corresponding chain endpoint before running a data query. The
Docker setup is described in the [README](../README.md#local-trino-cluster-with-docker).

## Discovery

```sql
SHOW SCHEMAS FROM web3;

SHOW TABLES FROM web3.ethereum;

DESCRIBE web3.ethereum.blocks;
```

Remote scans are bounded by design. Use a height, block number, slot, ledger
version, sequence, or checkpoint range as required by the native adapter.
Unbounded scans are rejected before an RPC request is scheduled.

## EVM

Ethereum and the supported EVM networks use the same native tables:
`blocks`, `transactions`, `receipts`, and `logs`. Each network has an independent schema, including
testnets such as `ethereum_sepolia` and `base_sepolia`.

### Blocks

```sql
SELECT block_number, block_hash
FROM web3.ethereum.blocks
WHERE block_number BETWEEN 23000000 AND 23000100;
```

```sql
SELECT block_number, block_hash
FROM web3.base.blocks
WHERE block_number = 1000000;
```

### Transactions by block range

```sql
SELECT hash, block_number, from_address, to_address
FROM web3.ethereum.transactions
WHERE block_number BETWEEN 23000000 AND 23000010;
```

### Transactions by hash

```sql
SELECT hash, block_number, from_address, to_address
FROM web3.ethereum.transactions
WHERE hash IN ('0xabc...', '0xdef...');
```

### Receipts by transaction hash

```sql
SELECT transaction_hash, block_number, status, gas_used, raw_json
FROM web3.ethereum.receipts
WHERE transaction_hash = '0x0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef';
```

### Logs by bounded block range

```sql
SELECT block_number, transaction_hash, log_index, address, topic0, data
FROM web3.ethereum.logs
WHERE block_number BETWEEN 23000000 AND 23000100;
```

### Additional provider fields

`raw_json` contains the complete source object for the row. Use Trino JSON
functions when a provider field is not represented by a stable typed column.

```sql
SELECT
    hash,
    json_extract_scalar(json_parse(raw_json), '$.gas') AS gas,
    json_extract_scalar(json_parse(raw_json), '$.nonce') AS nonce
FROM web3.ethereum.transactions
WHERE block_number = 23000000;
```

## Aptos

Tables: `transactions` and `events`.

```sql
SELECT ledger_version, hash, type, success, vm_status, sender
FROM web3.aptos.transactions
WHERE ledger_version BETWEEN 1000 AND 1099;
```

Events require an account, creation number, and bounded sequence range:

```sql
SELECT account_address, creation_number, sequence_number, event_type, data
FROM web3.aptos.events
WHERE account_address = '0x1'
  AND creation_number = '7'
  AND sequence_number BETWEEN 0 AND 99;
```

## Solana

Tables: `blocks`, `transactions`, and `instructions`. Every table requires a
bounded `slot` predicate.

```sql
SELECT slot, blockhash, parent_slot, block_time
FROM web3.solana.blocks
WHERE slot BETWEEN 200000000 AND 200000100;
```

```sql
SELECT slot, signature, success, fee
FROM web3.solana.transactions
WHERE slot BETWEEN 200000000 AND 200000100;
```

```sql
SELECT slot, transaction_signature, instruction_index, program_id,
       account_indices, data
FROM web3.solana.instructions
WHERE slot BETWEEN 200000000 AND 200000100;
```

The initial instruction table represents compiled top-level instructions; it
does not include inner instructions.

## Tron

```sql
SELECT block_number, block_hash, timestamp, transaction_count
FROM web3.tron.blocks
WHERE block_number BETWEEN 60000000 AND 60000010;
```

```sql
SELECT txid, block_number, contract_count
FROM web3.tron.transactions
WHERE block_number BETWEEN 60000000 AND 60000010;
```

## Sui

Sui exposes `checkpoints` and `transactions`, both bounded by checkpoint
sequence number.

```sql
SELECT checkpoint_sequence_number, digest, epoch, timestamp_ms,
       transaction_count
FROM web3.sui.checkpoints
WHERE checkpoint_sequence_number BETWEEN 1000000 AND 1000100;
```

```sql
SELECT checkpoint_sequence_number, digest, sender, status
FROM web3.sui.transactions
WHERE checkpoint_sequence_number BETWEEN 1000000 AND 1000100;
```

## Cosmos SDK chains

The `cosmos`, `osmosis`, and `injective` schemas expose native `blocks` and
`transactions` tables.

```sql
SELECT height, hash, chain_id, time, transaction_count
FROM web3.cosmos.blocks
WHERE height BETWEEN 100000 AND 100010;
```

```sql
SELECT height, index, tx_base64
FROM web3.cosmos.transactions
WHERE height BETWEEN 100000 AND 100010;
```

The same query shape applies to the other Cosmos SDK schemas:

```sql
SELECT height, hash, chain_id
FROM web3.osmosis.blocks
WHERE height BETWEEN 5000000 AND 5000010;
```

## Bitcoin-family UTXO chains

The `bitcoin`, `litecoin`, `dogecoin`, and `bitcoincash` schemas expose
`blocks`, `transactions`, `inputs`, and `outputs`. Block and row scans require
a bounded `height` or `block_height` predicate.

```sql
SELECT height, hash, previous_block_hash, time, transaction_count
FROM web3.bitcoin.blocks
WHERE height BETWEEN 800000 AND 800010;
```

```sql
SELECT txid, block_hash, block_height, version, input_count, output_count
FROM web3.bitcoin.transactions
WHERE block_height BETWEEN 800000 AND 800010;
```

```sql
SELECT txid, input_index, previous_txid, previous_vout, sequence
FROM web3.bitcoin.inputs
WHERE block_height BETWEEN 800000 AND 800010;
```

```sql
SELECT txid, output_index, value_satoshis, script_pubkey_hex, address
FROM web3.bitcoin.outputs
WHERE block_height BETWEEN 800000 AND 800010;
```

The same native table shape applies to Litecoin, Dogecoin, and Bitcoin Cash:

```sql
SELECT height, hash
FROM web3.litecoin.blocks
WHERE height BETWEEN 2500000 AND 2500010;
```

## Testnets

Mainnet and testnet schemas are independent and require their own endpoint
properties. Examples:

```sql
SELECT block_number, block_hash
FROM web3.base_sepolia.blocks
WHERE block_number BETWEEN 1000000 AND 1000010;
```

```sql
SELECT slot, blockhash
FROM web3.solana_devnet.blocks
WHERE slot BETWEEN 1000 AND 1010;
```

```sql
SELECT height, hash
FROM web3.bitcoin_testnet.blocks
WHERE height BETWEEN 100000 AND 100010;
```

## Raw JSON and system snapshots

Every remote chain row includes `raw_json`, a compact JSON document containing
the complete source object (without the JSON-RPC envelope). This provides
forward compatibility for additive provider fields.

```sql
SELECT
    block_number,
    json_extract_scalar(json_parse(raw_json), '$.miner') AS miner
FROM web3.ethereum.blocks
WHERE block_number BETWEEN 23000000 AND 23000001;
```

Runtime snapshots do not make RPC calls:

```sql
SELECT schema_name, runtime_configured, protocol
FROM web3.system.chains;

SELECT schema_name, request_count, failure_count, retry_count
FROM web3.system.rpc_metrics;
```

See [system tables](SYSTEM_TABLES.md) for the complete snapshot contract and
[chain model](CHAIN_MODEL.md) for per-chain planning requirements.
