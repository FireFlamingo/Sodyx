# Protocol state storage

`EncryptedProtocolStateStore` is the Android persistence boundary for opaque
libsignal serializations. It holds the local identity key pair, peer identity
keys, ratchet sessions, one-time prekeys, signed prekeys, and Kyber prekeys.
It also stores relay capability secrets and encrypted message records in the
same SQLite transaction as ratchet advancement. Schema version 2 adds those
tables to existing version 1 protocol stores.
It does not define, parse, or construct protocol material; libsignal remains
the cryptographic authority.

## Confidentiality and integrity

Every protocol blob is encrypted independently with AES-256-GCM. The AES key
is generated and retained by Android Keystore under an alias derived from the
protocol database name. The nonce is random for every write. A row-specific
additional authenticated-data value binds a ciphertext to its table, record
identifier, and column, so copying an encrypted blob into another protocol row
fails authentication.

SQLite stores address names, device IDs, prekey IDs, registration ID, table
shape, and record presence as metadata. SQLCipher or a platform full-database
encryption facility would be required to conceal that metadata. Protocol
private-key and session serializations are never logged and are stored only as
encrypted blobs.

## Atomicity and restart behavior

`transaction` uses one SQLite rollback-journal transaction for an entire
protocol action. Nested calls on the same thread reuse the existing transaction
instead of opening a nested SQLite transaction. This lets the engine atomically
persist ratchet advancement with envelope acceptance, consume a prekey with
session creation, or replace a peer identity with its resulting session state.
An exception rolls back the full outer transaction. Reopening the store uses
the same Android Keystore key and preserves committed state.

Kyber prekey use is claimed inside this transaction. A SHA-256 digest of each
base key is stored per Kyber prekey ID; a repeat of that same base key returns
`REUSED`, while a different base key can establish another session. The
transaction rolls back the claim if the surrounding decryption fails.

## Lifecycle

`close()` releases SQLite while preserving state for restart. `destroy()`
erases all rows, deletes the database, and deletes the matching Android
Keystore key. It is the explicit account or protocol-state destruction path.
Database deletion alone is insufficient because it leaves a Keystore key;
Keystore-key deletion alone makes ciphertext unrecoverable but leaves records.

Android backups are disabled by the manifest. Each pairwise relationship needs
its own database name and Keystore alias so its local identity cannot be reused
across contacts. Destruction should follow higher-level session cleanup.
