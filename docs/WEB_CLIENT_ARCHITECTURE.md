# Linked web client architecture

**Status:** Phase 17 design. This records the architecture required before a web
implementation; it makes no claim that the current Android prototype or any
browser client provides these properties.

Sodyx's Android phone is the root of trust. A browser is a linked delegated
client with bounded authority. It is not an account-login surface and never
receives the phone's master identity private key, private prekeys, or complete
primary-device protocol state. The existing local invitation QR is unrelated:
it is unauthenticated and cannot be reused for browser pairing.

## Goals and boundaries

A browser may read and send only after approval by an already trusted phone.
Pairing binds a browser-generated public key, one exact web origin, and a
short-lived transaction. The phone lists and revokes linked browsers. The phone
and relay enforce expiry and revocation, so an old browser cannot extend its
own authority.

The web client has no Bluetooth or mesh requirement, no public username,
phone-number identity, email identity, email/password login, or global
directory. A user who loses browser access pairs again from a trusted phone.

This design does not define substitute messenger cryptography. The selected
libsignal adapter must own identity verification, session establishment,
encryption, and ratchet state. This protocol delegates a bounded device
capability and routes opaque encrypted data.

## Trust model

~~~
  trusted phone                                      linked browser
  root identity; approval; revocation                link key; delegated credential
  primary protocol state                             encrypted local cache
             |                                                |
             +------ authenticated pairing / delegation ------+
                                      |
                           untrusted relay service
                      routes opaque envelopes and checks
                      capability status; learns metadata
~~~

The phone authenticates pairing through its local unlock/authentication boundary
and physical scanning of the browser QR. Before approval it shows the requested
origin, browser label, requested duration, and a short transaction confirmation
value. Scanning alone does not establish browser trustworthiness.

The relay is not trusted with plaintext, root private keys, or authority to
create a link. It can observe network addresses, timing, sizes, routing
identifiers, credential use, revocation events, and delivery outcomes. This
design makes no traffic-analysis anonymity claim.

## Pairing protocol

All fields use canonical binary encodings with a version and length limits.
The selected crypto adapter must supply and test-vector the exact signature,
key-agreement, encryption, and transcript construction. URLs and JSON carry
values; they are not an ad hoc signed encoding.

1. The browser creates a fresh browser link key pair, marked non-exportable
   through WebCrypto where supported, plus random pairing ID and pairing nonce.
   It registers its public key and requested duration with the relay over TLS.
2. The relay returns a short-lived, one-use pending record. The QR contains the
   version, relay endpoint, pairing ID, pairing nonce, browser public key, exact
   origin, requested authorization mode, and expiry. The transaction expiry is
   measured in minutes.
3. The phone scans the QR and rejects unrecognized versions, expiry, malformed
   keys, non-HTTPS origins, unexpected relays, and origins that do not match the
   reviewed deployment configuration. It fetches the pending record through an
   authenticated relay channel and compares every QR value with that record.
4. After the user sees and approves the binding, the phone creates a durable
   linked-device record: random link ID, key fingerprint, origin, scopes,
   issued/expiry times, active status, and revocation generation.
5. The phone issues an audience-bound delegated credential. It binds the link
   ID, browser public-key thumbprint, relay and origin, scopes, issue/expiry
   times, credential ID, and revocation generation. The phone encrypts that
   credential and only minimum link-scoped bootstrap material to the browser
   public key, binding the sealed package to the complete pairing transcript.
6. The browser retrieves and decrypts the sealed package, validates issuer,
   expiry, and transcript binding, then proves possession of its link private
   key to the relay. The relay atomically consumes the pending record. Reuse of
   its ID, nonce, package, or proof fails.

The credential is a proof-of-possession device capability, never a reusable
password. Every relay request includes a fresh request nonce and proof by the
bound browser key. The relay rejects wrong key, origin, audience, credential ID,
expiry, revocation generation, or duplicate nonce. Renewal requires a new
phone-to-browser delegation and cannot silently upgrade scope or duration.

## Authority, expiry, and revocation

Initial scope is intentionally narrow: fetch this linked device's opaque inbox,
acknowledge delivery IDs, submit opaque outbound envelopes under its link ID,
and request renewal. Identity changes, contact or device management, exports,
security settings, and linking another browser remain phone-only until
separately designed and reviewed.

The phone offers explicit modes:

- Temporary: default 15-minute session.
- Until browser closes: browser-local state plus a short absolute expiry.
- Trusted browser: distinct named record with a conservative renewable expiry.

The browser continuously displays its mode, remaining time, link fingerprint,
origin, last relay contact, and that the phone can revoke it.

Revocation is an idempotent, phone-authorized state change. The phone atomically
marks the link revoked and sends a signed revoke request to the relay. The relay
denies the capability, deletes undelivered link bootstrap material, and returns
the current revocation generation on later requests. The browser clears its
credential and encrypted cache after it observes revocation or expiry. Browser
storage clearing cannot promise physical erasure from disk remnants, backups,
screenshots, extensions, or a compromised host.

When the phone is offline, revocation reaches the relay when it reconnects.
Short expiry limits exposure. A relay must fail closed when it cannot validate
credential status. A browser treats rejection, missing renewal, or clock
uncertainty as blocked and requires fresh phone pairing.

## Data and cryptographic boundaries

The phone root private identity and primary-device libsignal state never cross
the pairing boundary. A browser receives material limited to its link ID,
expiry, and scopes. A separate review must decide whether a linked browser has
its own device session state or requests phone-side cryptographic operations;
the choice changes availability and browser-compromise exposure.

The relay receives opaque ciphertext envelopes and authenticated routing
metadata only. It must not decrypt, issue identity credentials, or decide that a
peer identity change is safe. Plaintext appears in browser memory only after
local authenticated decryption. A browser cache uses a link-scoped local
protection key where available but has weaker protection than Android
hardware-backed storage.

## Browser trust is weaker

Native Android code is installed, signed, and updated through a platform
distribution path. A browser runs JavaScript that its origin can change on every
load. A compromised service, CDN, origin, build pipeline, extension, or browser
profile can read displayed plaintext, make valid WebCrypto calls, use a
non-exportable key, exfiltrate a credential, send messages, or hide revocation
status. Non-exportability prevents direct key export; it does not stop malicious
JavaScript from using that key while it executes.

A linked browser is therefore a convenience client with weaker guarantees than
the Android root. Its UI must state that status, expiry, origin, and the phone
revocation route at all times. The phone notifies the user when a browser is
linked, renewed, or revoked. High-consequence actions remain phone-approved.

The architecture does not claim protection from a compromised phone or browser,
screen capture, endpoint malware, malicious accessibility software, server
traffic analysis, or malicious served JavaScript.

## Transport limits

Browser transport uses HTTPS and a relay-compatible long-poll, server-sent
event, or WebSocket channel. It has no reliable always-on background execution
or push delivery and may fail on tab suspension, private-mode teardown, cookie
clearing, or network change. A service worker is a cache/background helper, not
a trusted agent or a substitute for phone approval.

The relay assigns opaque envelope IDs and supports bounded retention and
idempotent fetch/acknowledgement. Duplicated, replayed, delayed, reordered, and
missing envelopes are normal transport outcomes. The crypto adapter and durable
state determine safe acceptance, replay handling, and resync. HTTP success
cannot prove those properties.

## Required interfaces

These are implementation dependencies for Phase 18. The names are descriptive,
not approved APIs.

| Contract | Operations and invariant |
| --- | --- |
| LinkedDeviceRegistry on phone | Create/list/find/revoke links; persist link ID, public-key fingerprint, origin, scopes, issue/expiry, status, revocation generation, and safe audit time. Status transitions are atomic and durable. |
| PairingTransactionStore | Create one-use pending records; bind QR and relay values; consume atomically; expire unconsumed records; rate-limit failures without logging QR secrets. |
| DelegatedCredentialIssuer | Issue, verify, renew, and revoke audience-bound proof-of-possession credentials from validated phone records only. It exposes no root private key or raw signing operation. |
| LinkBootstrapSealer | Encrypt minimum link-scoped bootstrap material to the browser key and bind it to transcript. It cannot serialize the root identity or complete primary protocol state. |
| LinkedDeviceCryptoAdapter | Expose opaque per-link crypto/session operations, versioned durable state, identity-change signals, and duplicate/replay/reorder outcomes. This is the only libsignal bridge. |
| RelayLinkAuthorizer | Verify credential, proof, audience, expiry, nonce, link state, and revocation generation for every operation; fail closed when status is uncertain. |
| OpaqueEnvelopeRelay | Accept/route bounded ciphertext and delivery IDs; provide idempotent fetch/ack; enforce retention and authorization; never parse plaintext. |
| WebSecureState | Hold browser key references, credential, encrypted cache, expiry, and revocation state. Clear observable state at logout/expiry/revocation without claiming guaranteed erasure. |
| TrustStatusPresenter | Render origin, label/fingerprint, scope, expiry, connected/blocked/revoked state, and phone-revocation direction. It cannot approve or renew itself. |

Current LocalDeviceIdentityRef, relationship UUIDs, InvitationPayload,
EnvelopeReference, and plaintext SQLite MessageRow are local prototype models.
They are not valid inputs to these contracts. Phase 18 needs new versioned types
and migration rules.

## Required tests

Use a fake clock, fake relay, and deterministic test crypto adapter, then repeat
critical paths on an Android device and at least two supported browsers.

1. A QR links only its scanned browser key, exact origin, and one pending
   transaction. Tampering, replay, and retrieval races create no second link.
2. Cancellation, expiry, malformed input, relay substitution, invalid proof, and
   stale transcript leave no active record or reusable bootstrap package.
3. A copied credential fails with another browser key, origin, audience, nonce,
   link ID, or expiry.
4. Expiry blocks fetch, send, renewal, and cache use. Renewal requires current
   phone approval and cannot silently expand scope or trust duration.
5. Phone revocation blocks relay operations after acknowledgement; browser
   restart and offline cache recovery cannot restore authority. Repeat revocation
   is safe.
6. Duplicate, replayed, delayed, reordered, malformed, and oversized envelopes
   produce no plaintext and no state rollback. Process death during bootstrap,
   decrypt/accept, ack, renewal, and revocation has an atomic recovery result.
7. Tests inspect every bootstrap and persistence field to prove no root private
   identity or complete phone protocol-state export reaches the browser.
8. Trust-status UI distinguishes active, expiring, expired, offline, blocked,
   and revoked. It offers no password recovery and never claims equal Android
   protection.
9. A malicious-JavaScript simulation can read displayed browser data and invoke
   a non-exportable WebCrypto key, demonstrating the documented limitation.

## Review gates before Phase 18

1. Review exact transcripts, canonical encodings, crypto constructions,
   state schema, expiry limits, and credential format with the selected
   libsignal integration.
2. Define relay retention, metadata/log policy, rate limits, clock source,
   deployment-origin pinning, incident revocation, and availability behavior.
3. Decide the linked-device crypto ownership model and assess multi-device
   delivery, verification, history, resync, browser compromise, and phone
   availability.
4. Complete a JavaScript-origin and supply-chain threat model covering CSP,
   dependencies, service-worker updates, release integrity, and response to a
   compromised origin. These controls reduce risk but cannot make web equal to
   the phone root.
5. Complete the required test protocol and independent security review before
   users can create a web link.

Phase 17 ends with this architecture. This document adds no browser client,
credential, relay endpoint, pairing QR, or web cryptographic state.
