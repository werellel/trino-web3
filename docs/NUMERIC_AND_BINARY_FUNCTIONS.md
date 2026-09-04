# Exact-width numeric and binary functions

The plugin provides exact-width `UINT256` and `INT256` types for blockchain
values that do not fit in Trino `DECIMAL(38, 0)`. The functions are registered
by `Web3Plugin` and are available in every catalog using the plugin.

These functions do not change the native chain schemas. Stable columns keep
their documented Trino types, while provider-specific values remain available
through `raw_json`. Convert a field explicitly when a query needs exact
256-bit arithmetic.

## Quick reference

| Function | Input | Result | Purpose |
| --- | --- | --- | --- |
| `web3_uint256(varchar)` | decimal text | `UINT256` | Construct `0` through `2^256 - 1` |
| `web3_int256(varchar)` | decimal text | `INT256` | Construct `-2^255` through `2^255 - 1` |
| `web3_varbinary_to_uint256(varbinary)` | 0–32 bytes | `UINT256` | Big-endian, zero-extended conversion |
| `web3_varbinary_to_int256(varbinary)` | 0–32 bytes | `INT256` | Big-endian, sign-extended conversion |
| `web3_uint256_to_decimal(uint256)` | `UINT256` | `varchar` | Exact decimal text |
| `web3_int256_to_decimal(int256)` | `INT256` | `varchar` | Exact decimal text |
| `web3_from_base58(varchar)` | Base58 text | `varbinary` | Decode Bitcoin/Solana Base58 |
| `web3_to_base58(varbinary)` | bytes | `varchar` | Encode Bitcoin/Solana Base58 |

`UINT256` and `INT256` can also be cast to 32-byte `VARBINARY` or to
`VARCHAR`. A `VARBINARY` cast back to either type accepts at most 32 bytes.
The result is always canonical, exactly 32 bytes, and big-endian.

## Hex values from chain responses

Trino's built-in `from_hex` removes no `0x` prefix. Remove the prefix first,
then use the appropriate Web3 conversion function:

```sql
-- 0x5208 is 21000 as an unsigned 256-bit value
SELECT CAST(
    web3_varbinary_to_uint256(from_hex(substr('0x5208', 3)))
    AS VARCHAR
);
```

For a value in `raw_json`, extract the string and apply the same expression:

```sql
SELECT
    hash,
    CAST(
        web3_varbinary_to_uint256(
            from_hex(substr(json_extract_scalar(json_parse(raw_json), '$.gas'), 3)))
        AS VARCHAR
    ) AS gas_decimal
FROM web3.ethereum.transactions
WHERE block_number = 23000000;
```

The conversion is big-endian. Signed conversion uses two's-complement sign
extension: `from_hex('ff')` becomes `255` as `UINT256` and `-1` as `INT256`.
Use a leading zero byte when `ff` must represent positive `255` as a signed
value (`from_hex('00ff')`). Inputs longer than 32 bytes fail instead of being
truncated.

The decimal constructors intentionally accept decimal text only. They do not
guess whether a string is hex or Base58:

```sql
SELECT CAST(web3_uint256('1000000000000000000000000000000000000') AS VARCHAR);
SELECT CAST(web3_int256('-42') AS VARCHAR);
```

## Arithmetic and comparisons

Both types support equality, ordering, hashing, `+`, `-`, `*`, `/`, and `%`.
`INT256` also supports unary negation. Operations are exact and checked:

```sql
SELECT
    CAST(web3_uint256('10') + web3_uint256('2') AS VARCHAR) AS added,
    CAST(web3_int256('-7') % web3_int256('3') AS VARCHAR) AS remainder;
```

An operation outside the declared range raises a Trino
`NUMERIC_VALUE_OUT_OF_RANGE` error. Division or modulus by zero raises
`DIVISION_BY_ZERO`; values are never silently rounded or wrapped. `NULL`
inputs produce `NULL`, following normal Trino function semantics.

`UINT256` ordering is unsigned. `INT256` ordering is signed, so `-1` sorts
before `1` even though its canonical bytes are all `0xff`.

## Aggregations

`sum`, `try_sum`, `min`, and `max` are available for both exact-width types:

```sql
SELECT
    CAST(min(value) AS VARCHAR) AS minimum,
    CAST(max(value) AS VARCHAR) AS maximum,
    CAST(sum(value) AS VARCHAR) AS total,
    CAST(try_sum(value) AS VARCHAR) AS nullable_total
FROM (VALUES
    web3_uint256('9'),
    web3_uint256('2'),
    web3_uint256('7')) AS t(value);
```

`sum` fails if any partial or final state overflows. `try_sum` returns `NULL`
for overflow and also returns `NULL` for an empty input. Partial aggregation
states are combined using the same exact arithmetic, so distributed execution
does not change the result. `min` and `max` use the native signed or unsigned
ordering rather than converting through floating point or decimal.

## Base58

The Base58 functions use the Bitcoin alphabet used by Bitcoin and Solana.
Leading zero bytes are preserved as leading `1` characters:

```sql
SELECT to_hex(web3_from_base58('111'));       -- 000000
SELECT web3_to_base58(from_hex('00000102ff')); -- 11LiA
SELECT web3_to_base58(web3_from_base58('3MN')); -- 3MN
```

An empty string maps to an empty `VARBINARY`. Characters outside the Base58
alphabet (for example `0`, `O`, `I`, or `l`) fail with
`INVALID_FUNCTION_ARGUMENT`. These functions do not implement Base58Check,
address validation, or network/version prefixes; those are protocol-specific
operations and must be handled by the relevant chain adapter or query.

## Type and compatibility notes

- Values are stored as 32-byte canonical bytes, not as Java `long` or
  `DECIMAL(38, 0)` values.
- `CAST(value AS VARCHAR)` is the portable way to display the exact decimal
  value in clients and snapshots.
- `CAST(value AS VARBINARY)` exposes the canonical 32-byte representation.
- Hexadecimal input must use `from_hex` plus an explicit Web3 conversion; a
  `0x` prefix is not accepted by `from_hex`.
- Malformed text, oversized binary input, overflow, and division by zero are
  errors. They are not converted into missing rows.

The implementation is isolated in `trino-web3-functions`; it has no dependency
on chain adapters or RPC transport. See [EXAMPLES.md](EXAMPLES.md) for
chain-specific bounded query examples and [SECURITY.md](SECURITY.md) for
configuration and secret-handling rules.
