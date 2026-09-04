# Numeric and binary SQL functions

The plugin registers exact-width `UINT256` and `INT256` types for values that
cannot be represented by Trino `DECIMAL(38, 0)`. Values are stored canonically
as 32-byte, big-endian two's-complement bytes; `UINT256` interprets those bytes
as unsigned and `INT256` as signed. The type's displayed object value is its
decimal string, so callers can use an explicit `CAST(... AS VARCHAR)` when a
stable textual result is required.

## Constructors and conversion

```sql
web3_uint256(varchar)                 -- strict decimal, 0 .. 2^256 - 1
web3_int256(varchar)                  -- strict decimal, -2^255 .. 2^255 - 1
web3_varbinary_to_uint256(varbinary)  -- 0 .. 32 bytes, big-endian, zero extended
web3_varbinary_to_int256(varbinary)   -- 0 .. 32 bytes, big-endian, sign extended
web3_uint256_to_decimal(uint256)      -- exact decimal VARCHAR
web3_int256_to_decimal(int256)        -- exact decimal VARCHAR
web3_from_base58(varchar)             -- Base58 to VARBINARY
web3_to_base58(varbinary)             -- VARBINARY to Base58
```

`UINT256` and `INT256` can be cast to 32-byte `VARBINARY` and from a `VARBINARY`
containing at most 32 bytes; they can also be cast to `VARCHAR`. Invalid text, more than 32 input bytes, division by zero,
and arithmetic outside the declared range fail with a Trino error; values are
never silently rounded or wrapped. SQL `NULL` remains `NULL`.

Both types provide equality, ordering, hashing, `+`, `-`, `*`, `/`, `%`, and
`INT256` unary negation. `sum(uint256)` and `sum(int256)` use exact arithmetic,
including partial-state combine, and fail on overflow. `try_sum` has the same
exact semantics but returns `NULL` on overflow. `min` and `max` compare in the
native unsigned or signed domain. They intentionally do not coerce through
floating point or `DECIMAL(38, 0)`.

The integer constructors are decimal-only. Hexadecimal and Base58 decoding are
separate binary functions and are not implicitly applied to numeric text. Base58
preserves leading zero bytes as the alphabet's leading `1` characters. This
keeps malformed or ambiguous external representations visible to the query.

## Examples

```sql
SELECT CAST(web3_uint256('1000000000000000000000000000000000000') AS VARCHAR);

SELECT CAST(sum(value) AS VARCHAR)
FROM (VALUES web3_uint256('1'), web3_uint256('2')) AS t(value);

SELECT CAST(web3_varbinary_to_uint256(from_hex('ffffffff')) AS VARCHAR);
```

The functions are registered by `Web3Plugin`; the type and function
implementation is isolated in the `trino-web3-functions` module and has no
dependency on chain adapters or RPC transport.
