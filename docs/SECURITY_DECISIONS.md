# Phase 5 security decision: protocol evaluation

**Date:** 2026-09-15
**Status:** Research complete; implementation deliberately blocked

Phase 5 evaluates a maintained, established cryptographic protocol or library
for Sodyx's one-to-one messenger. The review covers authenticated identity
establishment, asynchronous initiation, forward secrecy, post-compromise
security where available, replay handling, Android support, maintenance,
licensing, testability, and long-term integration risk.

The decision is to select **no implementation yet**. No production
cryptography, key generation, session engine, transport, or Phase 6 work has
been added. The current Phase 4 invitation and QR flow remains a local
simulation and must not be described as authenticated contact establishment.

## Requirements matrix

The matrix records the state of the review on 2026-09-15. “Evidence” means the
candidate's primary documentation or source repository; it does not mean that
Sodyx has validated the candidate in an Android build.

| Candidate | Identity and async model | Security properties | Android, maintenance, and testability evidence | Licensing and decision |
| --- | --- | --- | --- | --- |
| **Signal libsignal** | X3DH describes authenticated key agreement with an offline recipient and prekey bundles; Sesame describes asynchronous session management. | X3DH documents forward secrecy; Double Ratchet documents per-message keys, DH ratcheting, skipped-message bounds, and break-in recovery. Identity authentication still requires an explicit user verification step. | Current Rust implementation exposes Java/Android bindings and Android ABIs from Signal's artifact repository. The repository is active; latest reviewed release was v0.102.2, published 2026-09-10. Upstream specifications and tests are evidence only; Sodyx still needs clean-build, two-device, state-migration, and lifecycle validation. | Current libsignal is AGPL-3.0. Leading technical candidate, but blocked by a qualified license review, exact artifact availability, native packaging validation, API/state migration review, and Sodyx test evidence. |
| **matrix-sdk-crypto** | Matrix's Rust crypto implementation follows Matrix's Olm/Megolm and cross-signing/event model, documented in the Matrix spec and repository. | Credible mature ecosystem, but its identity, device, room, and event semantics are broader than Sodyx's pairwise model. Replay behavior, post-compromise recovery, and the exact Sodyx artifact/API surface remain unverified. | Rust bindings and Android-facing integration exist in the Matrix ecosystem, but Sodyx has not validated the required artifact/API surface, maintenance fit, or integration lifecycle. Upstream tests do not substitute for Sodyx pairwise and Android tests. | Apache-2.0. Credible alternative only if Sodyx intentionally adopts Matrix identity and event semantics and completes license, maintenance, and test review. Not selected for the current pairwise scope. |
| **OpenMLS** | MLS provides group key-establishment semantics rather than Sodyx's current one-to-one/session model. | Standards-based MLS implementation with group security properties; it is not a drop-in answer for Sodyx's pairwise asynchronous flow. | The official README says Android targets are unsupported, although they are built on CI; no supported Android/Kotlin API was established by this review. Active upstream tests and releases do not establish a supported Sodyx integration. | MIT. Not selected. Reopen only with verified Android packaging, long-term API/version ownership, and a decision to adopt MLS/group semantics. |
| **Noise** | Noise patterns provide authenticated handshakes when correctly configured, but do not by themselves define Sodyx's asynchronous prekey service, mailbox/session management, or identity UX. | Handshake framework, not a complete messenger protocol; replay, ratcheting, offline initiation, and post-compromise behavior depend on the chosen pattern and surrounding design. | The specification is maintained as a protocol reference, but no single maintained Android protocol product, complete messenger test suite, or Sodyx integration path was established. | The Noise specification is public domain; implementation licenses vary. Not selected as a complete protocol because it would leave security-critical protocol and lifecycle design to Sodyx. |
| **Tink / libsodium** | Primitive libraries; neither supplies the required authenticated asynchronous messaging protocol and session lifecycle. | Useful building blocks, not replacements for X3DH/Double Ratchet or MLS. | Android/JVM usage and upstream tests are available, but packaging and primitive test coverage do not solve protocol composition, lifecycle correctness, or long-term Sodyx maintenance. | Tink is Apache-2.0; libsodium is ISC. Not selected as the protocol choice; composing a new protocol would violate the no-invented-cryptography boundary. |
| **libsignal-protocol-java** | Historical prekey/session API is documented in its archived README. | Historical Signal Protocol implementation, not the current maintained Signal implementation. | Signal archived the repository on 2022-02-12; it is read-only. Historical tests do not establish current maintenance, Android packaging, or Sodyx integration support. | GPL-3.0. Rejected for new work. It is retained only as historical reference. |
| **libolm / stale bindings** | Historical Olm implementation and wrappers do not establish a current Sodyx support path. | No current evidence in this review justified choosing stale bindings over maintained alternatives. | libolm is deprecated and its official repository is archived; its maintenance, Android artifact, API-support posture, and upstream tests are insufficient for selection. | Apache-2.0. Rejected. |

## Primary evidence

Signal's current sources are the strongest fit for the required pairwise
properties:

- [libsignal repository](https://github.com/signalapp/libsignal) describes the
  current Rust implementation, Java/Android packages, Android ABIs, and the
  replacement relationship to `libsignal-protocol-java`.
- [libsignal releases](https://github.com/signalapp/libsignal/releases) lists
  the current release history; the reviewed latest release was v0.102.2,
  published 2026-09-10T21:42:33Z.
- [X3DH specification](https://signal.org/docs/specifications/x3dh/) defines
  mutually authenticated asynchronous initiation using identity keys and
  prekeys, and describes forward secrecy and deniability.
- [Double Ratchet specification](https://signal.org/docs/specifications/doubleratchet/)
  defines message-key ratcheting, DH ratchets, skipped-message handling, a
  bounded `MAX_SKIP`, forward security, and break-in recovery.
- [Sesame specification](https://signal.org/docs/specifications/sesame/)
  describes asynchronous session management and explicitly states that users
  must authenticate identity keys, for example by comparing fingerprints or
  scanning a QR code.
- [PQXDH specification](https://signal.org/docs/specifications/pqxdh/) and
  [ML-KEM Braid specification](https://signal.org/docs/specifications/mlkembraid/)
  document newer post-quantum directions. Their existence does not establish
  that every algorithm or API is exposed by the Android artifact selected for
  Sodyx.
- [Archived libsignal-protocol-java repository](https://github.com/signalapp/libsignal-protocol-java)
  records the 2022-02-12 archive date and its historical GPLv3 project state.
- [Current libsignal license](https://github.com/signalapp/libsignal/blob/main/LICENSE)
  is authoritative; [Signal's licensing discussion](https://github.com/signalapp/libsignal/issues/684)
  is supplementary. A qualified review must determine compatibility with
  Sodyx's intended distribution.
- [Matrix cryptography repository](https://github.com/matrix-org/matrix-rust-sdk/tree/main/crates/matrix-sdk-crypto)
  and [Matrix specification](https://spec.matrix.org/latest/) provide the
  primary Matrix identity/event and cryptography references.
- [OpenMLS repository](https://github.com/openmls/openmls) and [OpenMLS book](https://book.openmls.tech/)
  provide the primary MLS implementation and protocol integration references.
- [Official libolm repository](https://github.com/matrix-org/olm) is the
  archived/deprecated source for the historical Olm implementation.
- [Noise protocol framework](https://noiseprotocol.org/) documents the
  handshake framework and its deliberate division between patterns and
  application-specific session management.
- [Google Tink repository](https://github.com/tink-crypto/tink-java) and
  [libsodium documentation](https://doc.libsodium.org/) document primitive
  libraries rather than complete asynchronous messenger protocols.

## Decision and blockers

The review does not justify selecting a production implementation. libsignal
is the leading technical candidate because its documented protocol family
matches the pairwise requirements most closely, but that is not an adoption
decision.

The repository currently has no project `LICENSE` file. The intended Sodyx
distribution and licensing model must therefore be defined before AGPL-3.0
compatibility can be assessed; this records a product decision gate, not a
legal conclusion.

Adoption is blocked until all of these are resolved:

1. A qualified license review determines whether the current libsignal license
   is compatible with Sodyx's intended distribution model.
2. A specific `org.signal:libsignal-android` version and artifact source are
   pinned and verified in a clean Android build for every supported ABI.
3. The required Java/Kotlin API is mapped and wrapped behind a Sodyx adapter;
   undocumented internal state formats are not treated as a persistence API.
4. Two-device tests demonstrate identity verification, asynchronous prekey
   initiation, message encryption/decryption, duplicate and replay handling,
   out-of-order delivery, process death/restart, identity-key change, prekey
   exhaustion, and session replacement.
5. A mocked transport and durable stores are reviewed before any real network
   service is added.

The protocol specifications do not remove application-level obligations. In
particular, Double Ratchet's skipped-message state and `MAX_SKIP` bound are
state-management mechanisms, not a verified replay or duplicate guarantee for
Sodyx. Duplicate, replayed, delayed, and reordered envelopes therefore remain
mandatory two-device validation cases. X3DH's identity binding also requires
external fingerprint or QR verification; the Phase 4 QR flow does not perform
that cryptographic verification.

The decision may be reopened when those gates have evidence. It may also be
reopened for matrix-sdk-crypto if Sodyx deliberately adopts Matrix device,
cross-signing, room/event, and multi-device semantics, or for OpenMLS if Sodyx
changes its model to groups and verifies a supported Android integration.

## Phase boundary

Phase 5 ends here. Phase 6 must not begin until a later decision record names a
justified implementation and records the license, artifact, API, storage, and
test evidence. No production crypto or Phase 6 implementation was added by
this review.
