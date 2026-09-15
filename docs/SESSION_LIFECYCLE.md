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

Phase 3 adds a repository boundary that re-reads the current database session
state and validates the expected state inside the write transaction before
mutating it. The repository schema also chooses at most one active session per
relationship. This database authority does not revoke or erase immutable Phase 2
snapshots already retained by callers; callers must refresh from the repository
after a lifecycle change. See [local persistence](LOCAL_PERSISTENCE.md).

`Message.create` checks the `Session` value it receives, so passing a stale `ACTIVE`
snapshot still succeeds. The Phase 3 repository revalidates and serializes writes,
but the Phase 2 model itself is not a security boundary. Message and envelope
references are caller-supplied wrappers. Standalone models do not enforce
uniqueness for message or envelope IDs.

The following is Phase 2 historical behavior; it predates the Phase 4 local
invitation repository. In that model, invitations represent local intent with an owner, offered pairwise
identity, timestamps, and an expiry check. Validity is the half-open interval
`[createdAt, expiresAt)`. Invitations reserve no uniqueness and have no redeemed or
consumed state. They contain no redeemable token, QR encoding, camera flow, or
redemption operation. Messages are plain application content tied to an active
session; an envelope reference is only a local association between message,
session, and caller-supplied envelope ID. It contains no plaintext fields or route
choices. There is no cryptography, transport, serialization, or secure envelope
implementation yet.

Phase 4 invitation redemption may create a relationship before any session
exists. It does not activate a transport session, prove peer identity, or alter
the Phase 2 session transition rules. A relationship can therefore exist with
no active session.
