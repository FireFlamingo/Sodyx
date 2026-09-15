# Decision 0002: local invitation simulation

Phase 4 uses a SQLite-persisted local invitation lifecycle. The app creates a
strict versioned opaque payload, renders it as a QR image with ZXing core 3.5.4,
and imports text or handoff representations without camera permission. Expiry
uses `[created, expires)`. Redemption and relationship creation share one
transaction with a guarded single-winner update per local database.

The payload has no authenticity or confidentiality, and syntactically valid
tampering cannot be detected. A pseudonym is relationship-scoped; an alias is a
cosmetic local label. The flow does not provide global search, email or phone
signup/discovery, public usernames, camera scanning, network transport,
cryptography, key exchange, issuer synchronization, or server-side redemption.

The QR image is a local representation. Import is a labeled local simulation,
not proof that a person scanned the code or that a peer is reachable. A created
relationship can exist without an active session. Real authenticated invitations,
key agreement, transport, remote delivery, and production identity verification
remain later work. Phase 4 stops after local lifecycle, relationship creation,
pseudonym generation, alias display, tests, and documentation.
