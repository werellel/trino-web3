# M5.4 — Configuration, security, and lifecycle hardening

## Scope

Harden the existing catalog configuration and runtime lifecycle without adding
provider behavior, chain tables, or new execution policy knobs.

## Changes

* Reject fallback-only, empty, and duplicate endpoint lists without echoing
  endpoint values.
* Keep configuration parsing and asynchronous HTTP failures free of raw
  property values, endpoints, and credentials.
* Close every runtime already created if connector initialization fails, and
  make repeated connector shutdown safe.
* Document endpoint secrecy, shared HTTP-client ownership, and shutdown
  behavior.

## Risks and tests

Endpoint URLs may legitimately include provider credentials in a path or query,
so validation must not reject those forms solely to avoid disclosure. Tests use
only local endpoints or deliberately invalid values, assert that secrets are
absent from errors, and cover configuration rejection, sanitized transport
failure, and repeated shutdown. Existing deterministic runtime tests cover
queued and in-flight cancellation during runtime close.
