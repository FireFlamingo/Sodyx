# Sodyx Relay Prototype

This service provides a small, in-memory mailbox for **opaque encrypted envelopes**. It is intended to prove asynchronous delivery. It has no accounts, user names, contacts, recipient identities, or plaintext message handling.

The relay is a single hop. It does not provide anonymity and it does not claim resistance to traffic analysis. A network observer can still see a client connect to this server, request timing, request size, and the server address. The service is designed so that later ingress and mailbox nodes can route opaque capabilities without changing the Android envelope contract.

## Run and test

Requires Node.js 22 or later (included in current Windows development environments).

```powershell
cd relay
npm test
npm start
```

The default listener is `127.0.0.1:8080`. Set `HOST` and `PORT` only when deploying behind a TLS terminator. Production deployment must use HTTPS; the relay intentionally contains no TLS or certificate policy so that this stays a small transport component.

## Threat model and boundaries

The relay stores bytes that have already been authenticated and encrypted by the client. It cannot decrypt, modify safely, identify the human sender or receiver, or interpret the envelope.

The relay can observe a request's network connection, timing, size, the fact that a mailbox capability was used, and mailbox occupancy while serving it. It stores delivery and retrieval capabilities and ciphertext in memory until retrieval deletion or expiry. It does not persist request logs, client IP addresses, capabilities, envelope content, or identity metadata. A host or reverse proxy can add metadata outside this process; deployment must configure those layers with equally restrictive logging.

Bearer capabilities are access control, not identities. Anyone holding a delivery capability may submit ciphertext until expiry. Anyone holding a retrieval capability may list and delete ciphertext until expiry. Compromise of the relay exposes stored ciphertext and capabilities, but not plaintext without the client-held E2EE keys. A compromised relay can drop, delay, replay, and inject ciphertext; clients must enforce E2EE authentication, replay detection, and session rules.

The process intentionally makes no anonymity claim. It is a Phase 8 delivery prototype, not the Phase 9 multi-hop transport design.

## HTTP protocol v1

All responses use `Cache-Control: no-store`. Request and response bodies are JSON. Capabilities are only sent in `Authorization: Bearer <capability>` after creation. Clients must never put capabilities in query strings, logs, analytics, notifications, or crash reports.

### Create a mailbox

`POST /v1/mailboxes`

```json
{ "ttlSeconds": 86400 }
```

`ttlSeconds` is an integer from 60 to 604800. The relay holds at most 1024
mailboxes in total; creation returns `503` when that limit is reached. The
response is returned once:

```json
{
  "deliveryCapability": "43 base64url characters",
  "retrievalCapability": "43 base64url characters",
  "expiresAt": 1760000000000
}
```

Each capability contains 256 bits from the operating system CSPRNG. The client must transfer the delivery capability to the sender only through an authenticated, encrypted Sodyx protocol message. Keep the retrieval capability only on the intended receiver device.

### Submit an envelope

`POST /v1/envelopes` with `Authorization: Bearer <deliveryCapability>`:

```json
{ "envelope": "base64url-encoded opaque bytes" }
```

The decoded envelope must be between 1 byte and 262144 bytes. A mailbox holds
at most 128 envelopes and 8 MiB; all mailboxes together hold at most 64 MiB of
envelope bytes. Success is `201` with an opaque 128-bit message ID. A full
mailbox or relay envelope budget returns `507`.

### Retrieve and delete

`GET /v1/envelopes` with `Authorization: Bearer <retrievalCapability>` returns:

```json
{ "envelopes": [{ "id": "22 base64url characters", "envelope": "..." }] }
```

Retrieval does not delete. After the Android client verifies and durably records an E2EE envelope, it must acknowledge that exact ID:

```text
DELETE /v1/envelopes/{id}
Authorization: Bearer <retrievalCapability>
```

This returns `204`. Expiry removes the complete mailbox and invalidates both capabilities. Unknown, expired, or wrong-scope capabilities return `404`, so callers cannot distinguish them. Missing or malformed authorization returns `401` or `400`.

## Android integration contract

1. Create a mailbox while establishing a session. Treat both returned capabilities as secrets.
2. Include the delivery capability inside the authenticated E2EE session setup or ratcheted control message. Never derive it from an identity or alias.
3. Send already encrypted/framed envelope bytes using base64url. The relay must receive no plaintext, key, alias, relationship, contact, or session identifier.
4. Poll `GET /v1/envelopes` through HTTPS. Verify each envelope using libsignal and the session's replay rules before saving it.
5. Persist the message ID only with the verified encrypted record, then delete it with the retrieval capability. Retry deletes idempotently: `404` means it is already absent or expired.
6. Handle `404` as capability expiry, `507` as bounded mailbox backpressure, and network errors as retryable transport failures. Do not expose these raw details to other contacts.
7. On session destruction, locally erase both capabilities and call the deletion endpoint for any listed envelope IDs that remain. Capability expiry is the final server-side erasure backstop.

For later multi-hop routing, an ingress can carry the same `Authorization` capability and opaque body to a mailbox node. No hop should add recipient identity or plaintext fields to this protocol.

## Operational limits

This prototype keeps all mailbox state in RAM, so restart deletes every mailbox
and envelope. It is intentionally unsuitable for production durability. The
HTTP server limits header receipt to 10 seconds and requests to 20 seconds.
A durable successor needs encrypted-at-rest storage, crash-safe expiry and
deletion, deployment-side log review, rate limiting that does not retain client
identity, abuse controls, and a separately reviewed multi-hop threat model.
