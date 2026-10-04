# Core protocol

Sodyx uses libsignal 0.102.2 through the isolated `:security` module. It implements no custom key agreement, ratchet, or cipher. Each connection has a fresh identity and independent protocol database; there is no public global account identifier.

## Contact setup

Both participants create a contact card. A versioned, bounded binary card contains public libsignal prekeys, a random pairwise address, a relay URL, a delivery capability, and expiry. The corresponding retrieval capability stays encrypted on its owner's phone.

The displayed verification code is SHA-256 over the complete canonical card. Comparing it through a separate trusted channel binds the identity key, prekeys, relay URL, capability, and expiry. Importing a card alone does not authenticate its owner. The UI requires an explicit comparison confirmation before `confirmVerifiedIdentity` pins the peer key and libsignal establishes the session. A paired card cannot silently replace another identity.

## Messages and persistence

The wire wrapper contains a version, libsignal ciphertext type, and bounded ciphertext bytes. It exposes no alias, identity, contact ID, or plaintext. The relay receives base64url-encoded opaque wire bytes. Message length and timing are visible; size and traffic obfuscation are outside the core scope.

For an outgoing message, one SQLite transaction advances libsignal state and saves the wire envelope and local message record. The local plaintext record is AES-GCM encrypted under Android Keystore. Network failure leaves the envelope queued; retry sends the same ciphertext rather than re-encrypting with rolled-back state.

For an incoming message, one transaction authenticates/decrypts, advances the ratchet, and saves the encrypted local record. The relay is acknowledged after commit. A repeated relay ID is idempotent; libsignal rejects replayed ciphertext even under a new relay ID. Authentication and identity failures escape the transaction before handling, so failed operations roll back. Malformed and unauthentic ciphertext is discarded without displaying a message. Identity changes require new verified setup.

Only a foreground conversation polls automatically. The app uses HTTPS, disallows redirects, bounds response bytes while reading, and retains no request or message logs. The debug build has a localhost-only cleartext exception for isolated ADB integration tests; release policy remains HTTPS-only.

## Closure

The registry first marks a connection closed, preventing future sends. The connection's database and Keystore wrapping key are then destroyed. Closed registry tombstones trigger cleanup on the next load, covering a crash during destruction. The app attempts to revoke its retrieval mailbox after local destruction; server expiry is the offline fallback. Peer copies and the peer's independent mailbox are not erased.

## Evidence and limits

Host tests exercise libsignal encryption/decryption, first-contact verification, replay/tampering, card parsing, wire bounds, and relay JSON. Device tests exercise Keystore persistence/rollback, actual native sessions across reopen, two-party queued delivery, corruption/replay, and UI pairing/closure. The live relay fixture uses the real HTTP client and server. Passing these tests is implementation evidence, not an independent security audit.
