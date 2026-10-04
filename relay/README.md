# Sodyx relay

A dependency-free Node.js service for opaque encrypted envelopes. It has no accounts, usernames, contacts, social graph, or plaintext decryption keys.

## Run

Tested with Node.js 22.17.1. The relay uses Node's built-in SQLite API (experimental on Node 22).

```sh
npm test
npm start
```

Defaults: `HOST=127.0.0.1`, `PORT=8080`, `DATA_PATH=./data/relay.db`. Keep the data directory outside source control. SQLite commits are synchronous and mailbox data survives restart. Only SHA-256 hashes of capabilities are persisted; bearer capabilities are returned once at creation.

Expose the service through an HTTPS reverse proxy. For example, with a domain pointing to the host, a Caddy configuration can be:

```text
relay.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

Configure your real domain and TLS on the host; the Android app accepts HTTPS only. Do not enable access logs that retain client IPs, capabilities, or request bodies. Windows deployments must protect the database directory with host ACLs; Unix database files are created with mode 0600. Backups of the relay database extend ciphertext retention and should be disabled unless deliberately required.

## HTTP contract v1

All responses use `Cache-Control: no-store`. Capabilities go in `Authorization: Bearer <capability>`, never URLs.

- `POST /v1/mailboxes` with `{ "ttlSeconds": 86400 }` returns separate random 256-bit `deliveryCapability` and `retrievalCapability`, plus `expiresAt` in Unix milliseconds. TTL is 60 seconds to seven days.
- `POST /v1/envelopes` with a delivery capability and `{ "envelope": "base64url opaque bytes" }` returns `201` and a random message `id`.
- `GET /v1/envelopes` with a retrieval capability returns `{ "envelopes": [{ "id": "...", "envelope": "..." }] }` without deleting.
- `DELETE /v1/envelopes/{id}` with the retrieval capability acknowledges one durably accepted message and returns `204`.
- `DELETE /v1/mailboxes` with the retrieval capability revokes the mailbox, deletes its ciphertext, and invalidates both capabilities.

Unknown, expired, revoked, or wrong-scope capabilities return `404`. Missing authorization returns `401`; malformed input returns `400` or `413`. Clients handle repeated ciphertext through their E2EE state, not a server identity.

Limits are 256 KiB per envelope, 512 KiB per request, 128 envelopes and 8 MiB per mailbox, 1024 mailboxes and 64 MiB of ciphertext globally. Full storage returns `507` (or `503` for mailbox creation). Header/request timeouts are 10/20 seconds. Expiry is checked on access, periodically while running, and when loading persisted state after restart.

## Trust and limits

The relay observes IP connections, timing, sizes, capabilities while requests are in flight, and mailbox occupancy. A compromised host can drop, delay, replay, or inject ciphertext. Clients must authenticate and enforce replay rules. This is a single relay and provides no anonymity guarantee.

The mailbox-creation endpoint is bounded but public. Use a private relay or deployment-level abuse controls for untrusted traffic; those controls must not become an unnecessary user-tracking service. The test suite covers capability scope, malformed input, storage bounds, expiry, revocation, and restart durability without bearer-capability persistence.
