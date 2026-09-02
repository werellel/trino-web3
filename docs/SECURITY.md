# Security guidance

## Endpoint credentials

Ethereum and Solana endpoint URLs can contain provider credentials in their
authority, path, or query. Treat every endpoint URL as a secret: do not commit
it, paste it into issues, or expose its catalog properties to untrusted users.
Store catalog files with restrictive filesystem permissions and inject secrets
through the deployment mechanism appropriate for the Trino installation.

Aptos endpoint configuration is deliberately restricted to a credential-free
HTTP(S) origin. The connector supplies only fixed adapter-owned request paths
and query parameters for Aptos REST operations.

The connector never publishes endpoint values, request data, credentials, or
provider identity through system tables or metric labels. Configuration errors
identify only the property name. Asynchronous HTTP connection failures are
reported with a stable endpoint-free message, including when a provider URL
contains credentials.

## Validation and lifecycle

Fallback endpoints require a primary endpoint, and blank, duplicate, or more
than eight total endpoints are rejected. Endpoint identity validation uses the
normal bounded runtime path but reports neither endpoint nor native identity
values on failure.

Each catalog shares one JDK HTTP client across its configured runtimes. Closing
the catalog cancels queued and in-flight remote work, clears retained cache
entries, and stops runtime schedulers. Repeated shutdown is safe.
