# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and releases follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

* Trino 475 compatibility verification and tagged-release automation.
* Configuration, endpoint secrecy, and connector lifecycle hardening.
* Native Ethereum, Solana, and Aptos vertical slices with bounded execution.
* Native EVM transaction receipts and event logs with bounded query planning
  and additive `raw_json` preservation.
* Native Tron REST blocks and transactions with bounded block-number execution.
* Native Sui, Cosmos SDK, and Bitcoin-family adapter support with isolated
  mainnet and testnet schemas.

### Changed

* The release workflow builds and tests a tag-derived non-SNAPSHOT version
  before attaching the packaged plugin distribution.
