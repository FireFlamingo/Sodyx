# Phase 2 identity model

Phase 2 defines the local domain vocabulary for one-to-one relationships. It is an
immutable, in-memory model; it is not an identity provider, key store, directory, or
wire protocol.

`LocalDeviceIdentityRef` is a caller-supplied UUID wrapper for this device's local
registry. It is not a public device identity, credential, key, capability, or wire
ID. Its `.value` remains accessible to callers; the redacted `toString()` is only a
logging convenience and is not data-loss prevention. The registry enforces
uniqueness only within one `LocalIdentity` value: relationship references cannot
repeat, and a pairwise identity cannot be reused in two relationships. Separate
aggregates with the same owner do not provide global uniqueness, and no secure
identity generation is promised.

Each relationship contains exactly two distinct `PairwiseIdentity` values. Those
references are relationship-scoped and deliberately separate from the local device
reference. `DisplayAlias` values are cosmetic labels: they must be trimmed, contain
no ISO control characters, and fit at most 64 UTF-16 code units. A session stores
aliases as immutable participant snapshots, so changing a later display choice cannot rewrite
an existing session's view or alter relationship identity.

`LocalIdentity.relationships` returns a defensive list snapshot; mutating that
returned list cannot mutate the aggregate. Adding a relationship returns a new
aggregate; callers must replace their old reference to retain it. The model does not
erase stale snapshots, persist data, manage secure keys, encode wire messages, or
guarantee identity uniqueness beyond the checks described above.

## Phase 4 invitation boundary

Phase 4 pseudonyms are locally generated, relationship-scoped references. They
are not global identities, public usernames, credentials, keys, phone numbers,
email addresses, or searchable handles. `DisplayAlias` remains a cosmetic local
label and does not verify or identify a person.

An imported invitation can create a local relationship, but it does not prove
that the payload came from a claimed person. The versioned payload has no
authenticity or confidentiality protection; syntactically valid tampering cannot
be detected. See [invitation lifecycle](INVITATION_LIFECYCLE.md).
