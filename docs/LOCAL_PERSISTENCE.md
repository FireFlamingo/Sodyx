# Phase 3 local persistence

Phase 3 gives the in-memory Phase 2 domain a local repository boundary. The
prototype uses an app-private SQLite database through Android's
`SQLiteOpenHelper` and `SQLiteDatabase` APIs. This keeps the small prototype
free of Room and KSP while the schema and lifecycle are being exercised. Android
currently recommends Room for new SQL-backed applications, so a production
decision should revisit Room after the model and queries stabilize. See the
[SQLite storage guide](https://developer.android.com/training/data-storage/sqlite)
and [`SQLiteDatabase` reference](https://developer.android.com/reference/android/database/sqlite/SQLiteDatabase).

## Boundary and ownership

The database is private to the Sodyx application. Repository code owns schema
creation and explicit unsupported-upgrade handling, transactions, foreign-key configuration, parameterized
queries, row mapping, and conversion to the immutable Phase 2 values. UI code
does not hold a database handle or construct SQL. The domain module remains
Android-free; persistence is an app-side adapter around it.

The current schema contains six tables: `local_identity`, `pairwise_identities`,
`relationships`, `sessions`, `messages`, and `invitations`. Relationship listing uses explicit
SQLite `rowid` order; the repository does not expose a session list, and message
listing uses the monotonic `sequence` column.
The Phase 3 baseline had no persisted invitation rows. Phase 4 adds the local
invitation rows described below. The repository does not create a public identity,
keys, network redeemable invitations, transport envelopes, camera flow, or
network route. The [identity model](IDENTITY_MODEL.md)
and [session lifecycle](SESSION_LIFECYCLE.md) remain authoritative for domain
meaning and allowed transitions.

Writes affecting multiple rows use one transaction. Foreign keys are enabled
and checked at the repository boundary. Every query value is a bound parameter;
SQL identifiers are fixed by the implementation. Reads return detached domain
values. State transitions re-read the current row and validate the expected
state in the same write transaction, so a stale Phase 2 snapshot is not write
authorization.

The schema stores no created-at timestamp columns; local ordering uses rowid and
message sequence only.

The schema has a partial unique index allowing at most one `ACTIVE` session per
relationship. This is a Phase 3 repository product choice; the pure Phase 2
model permits multiple session values for one relationship.

Phase 4 adds SQLite-persisted local invitation rows. Each row stores the
versioned payload fields (`id`, `token`, and relationship-scoped `pseudonym`),
creation and expiry instants, direction, redemption state, optional redemption
time, and optional relationship linkage. The valid interval is
`[created, expires)`.
Redemption and relationship creation occur in one transaction with a guarded
single-winner update, so one local database cannot create two relationships from
one invitation. This guarantee has no issuer synchronization and does not
extend across separate databases or installations. Invitation payloads have no
authenticity or confidentiality; syntactically valid tampering cannot be
detected.

## Deletion and session ending

Ending a session deletes that session's message rows and then marks the session
`CLOSED`, preserving the relationship and its session tombstone. The operation
is performed in one transaction, is idempotent, and cannot delete another
relationship's records. A future session may be created for the relationship.

This is an application-level deletion guarantee. WAL is explicitly disabled, so
SQLite uses its platform rollback-journal behavior. That journal, database
pages, and filesystem storage may retain remnants. The implementation does not
enable `secure_delete` and does not promise physical
overwrite of flash storage, recovery-proof erasure from SQLite pages, journals,
WAL, filesystem snapshots, operating-system backups, screenshots, exports, or a
peer's copy. SQLite documents `secure_delete` as a way to overwrite deleted
content in database pages, but it is not a cryptographic eraser or a substitute
for backup, filesystem, or hardware guarantees; see the [SQLite pragma
reference](https://www.sqlite.org/pragma.html#pragma_secure_delete). The
implementation must not claim stronger guarantees than verified behavior.

Immutable Phase 2 snapshots retained in memory cannot be erased by deleting
source rows. There is no end-to-end encryption, key destruction, transport,
remote deletion, peer-side deletion, backup integration, or real invitation
redemption in this phase.

## Verification expectations

The current instrumentation suite exercises repository round trips across close
and reopen, insertion ordering, one-active-session enforcement, idempotent
message deletion and closure, relationship isolation, concurrent append/end
serialization, rollback of invalid writes, stale-write rejection, clear
missing/closed failures, and foreign-key protection. Schema version 1 has no
migration path: unsupported upgrades fail explicitly. Tests verify observable
application behavior rather than physical flash contents. The prototype may
use plaintext local values because encryption and key management are deferred;
local persistence is not a confidentiality claim.

Android backup is disabled in the manifest and both cloud-backup and
device-transfer extraction rules exclude the app's storage domains. These
settings reduce the configured backup paths; they do not cover screenshots,
exports, peer copies, or undocumented device-level capture mechanisms.
