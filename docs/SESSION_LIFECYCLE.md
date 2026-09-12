# Phase 2 session lifecycle

The model separates a relationship, its conversations, and individual sessions:

- A relationship owns exactly two pairwise identities.
- A conversation groups the immutable session values associated with one relationship.
- A session records its relationship reference, two participant identity/alias
  snapshots, and lifecycle state.

Allowed state transitions are:

```text
CREATED -> ACTIVE -> ENDING -> CLOSED -> DESTROYED
CREATED -> ENDING
```

Transitions return new values and reject every other edge. `ENDING`, `CLOSED`, and
`DESTROYED` are modeled states only. They do not promise message delivery, database
cleanup, key destruction, remote deletion, or erasure of old immutable snapshots.
Multiple sessions may exist for one relationship; no global single-active-session
invariant is specified. `transitionSession` updates only the matching session in a
conversation. Since values are immutable, callers must retain the returned
conversation as the authoritative state.

`Message.create` checks the `Session` value it receives, so passing a stale `ACTIVE`
snapshot still succeeds. A future repository or write boundary must serialize,
revalidate, and coordinate state transitions; this Phase 2 model is not a security
boundary. Message and envelope references are caller-supplied wrappers. Standalone
models do not enforce uniqueness for message or envelope IDs.

Invitations currently represent local intent with an owner, offered pairwise
identity, timestamps, and an expiry check. Validity is the half-open interval
`[createdAt, expiresAt)`. Invitations reserve no uniqueness and have no redeemed or
consumed state. They contain no redeemable token, QR encoding, camera flow, or
redemption operation. Messages are plain application content tied to an active
session; an envelope reference is only a local association between message,
session, and caller-supplied envelope ID. It contains no plaintext fields or route
choices. There is no cryptography, transport, serialization, or secure envelope
implementation yet.
