# Protocol implementation status

Sodyx uses `org.signal:libsignal-android:0.102.2` and
`org.signal:libsignal-client:0.102.2` through the isolated `:security` module.
There is no Sodyx key agreement, ratchet, or cipher. The app-owned
`ProtocolStateStore` persists libsignal's opaque records; the Android
implementation encrypts each record with AES-GCM under an Android Keystore key.

The public peer identity key must be verified through a separate trusted
exchange before either `establishSession` or first prekey-message decryption.
`confirmVerifiedIdentity` pins that key. An identity change requires a separate
explicit replacement action, which deletes existing sessions for that peer.
The Phase 4 invitation QR is still unauthenticated local simulation and must
not be used as this verification step.

Each relationship needs a distinct `EncryptedProtocolStateStore` database and
therefore a distinct local libsignal identity key. A session's ratchet state is
stored under a relationship-scoped peer address. Prekeys and session state are
updated inside store transactions. The app must also persist outbound
ciphertext, inbound replay decisions, and message state in the same durable
transaction before connecting this engine to live delivery. The existing
plaintext `messages` table does not meet that requirement.

Current automated evidence covers two independent libsignal stores exchanging
an initial prekey message and a reply, rejecting a replay and an altered
message, and refusing unverified setup. Android instrumentation verifies that
protocol records are encrypted on disk, survive restart, and roll back on a
failed transaction. The relay sees only opaque bytes; it has no decryption key.

The following remain required before Sodyx can claim endpoint encryption in
the app: real peer bundle exchange and QR/fingerprint verification, atomic
encrypted message and ratchet persistence, identity rotation and prekey
exhaustion handling, process-death and two-device tests, release/runtime checks
for every shipped ABI, session key destruction, and log review. Framing's
current 1024-byte cell size is provisional and its visible ciphertext-length
field still exposes exact serialized-envelope length; it is not a finished
message-size protection layer.
