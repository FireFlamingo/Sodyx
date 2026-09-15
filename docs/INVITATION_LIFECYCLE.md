# Phase 4 local invitation lifecycle

Phase 4 implements contact establishment as a local prototype flow. An
invitation is a SQLite-persisted, strict versioned opaque payload with local
creation and expiry instants and redemption state. It is not a credential,
proof of identity, or network address.

The app renders the payload as an actual QR image using ZXing core 3.5.4.
Import accepts a local text or handoff representation, including a syntactically
valid external payload. Import is a labeled simulation: it uses no camera
permission, network, server, or peer connection. The payload has no authenticity
or confidentiality, so syntactically valid tampering cannot be detected.

An invitation is valid on `[created, expires)`. Redemption and relationship
creation are one database transaction with a guarded consumed-state update.
Exactly one local transaction wins; repeated, concurrent, or consumed redemption
cannot create another relationship in that database. There is no issuer
synchronization or server-side winner.

Successful redemption creates relationship-scoped pseudonym references and a
cosmetic local alias. The relationship may exist before any active session. No
transport, encryption, key exchange, or remote contact establishment occurs.

The database retains the payload, version, lifecycle timestamps, redemption
state, and relationship linkage needed for this flow. Expired or consumed
invitations are no longer redeemable. Deletion does not promise removal from QR
screenshots, clipboard contents, OS logs, SQLite journals, backups, another
installation, or another person's captured copy.

This phase excludes global search, email or phone signup/discovery, public
usernames, camera scanning, network transport, cryptography, and issuer or
server synchronization.
