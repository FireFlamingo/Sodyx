# Android security and privacy review

**Reviewed:** 2026-09-23
**Scope:** Current native Android client, target SDK 36, minimum SDK 26

This review records the security boundary of the current application. It does
not treat local UUID references, QR invitation payloads, or the current SQLite
message store as cryptographic identity, authenticated contact establishment,
or encrypted storage. The selected libsignal integration remains responsible
for the future protocol boundary described in
[SECURITY_DECISIONS.md](SECURITY_DECISIONS.md).

## Current controls

| Area | Current state | Decision |
| --- | --- | --- |
| Permissions | The manifest requests no Android permissions, including `INTERNET`. | Retain least privilege. Add each capability only with a matching transport or product review. |
| Exported components | The launcher `MainActivity` is the sole component and is exported only because Android requires it for launcher discovery. | No services, receivers, providers, or share targets are exposed. |
| Deep links | No `VIEW` or `BROWSABLE` intent filter exists. | Do not add a deep link until its origin, parser, authentication, and replay behavior have a security review. |
| Backups and device transfer | `allowBackup=false`, `fullBackupContent=false`, and `data_extraction_rules.xml` exclude every application storage domain. | Keep local state out of cloud backup and device-to-device transfer. |
| Network transport | Cleartext is denied both by the manifest and `network_security_config.xml`. The network policy accepts system certificate authorities only. | A future endpoint must use TLS, hostname validation, and an endpoint-specific review before a domain configuration or pinning policy is introduced. |
| Web content | No `WebView` is present. | Do not add web content to private-message flows. |
| Notifications | The app has no notification permission, channels, or notification code. | Any future notification must use generic content and avoid message text, aliases, invitation data, keys, or contact identifiers. |
| Clipboard | The app has no clipboard API calls. | Do not copy private payloads by default. If a user-initiated copy feature is added, require explicit intent, use Android's sensitive-clipboard marking where supported, and clear only when Android permits it without breaking user expectations. |
| Logging and crash reporting | Source review found no `Log`, `println`, analytics, or crash-reporting calls. | Keep plaintext, QR payloads, identifiers, session state, and key material out of logs and crash reports. |
| Build configuration | Release is non-debuggable, shrunk, and resource-shrunk. Dependency versions are fixed in the version catalog and repository resolution is limited to Google and Maven Central. CI scans all Git history for secrets. | Preserve these constraints. Dependency upgrades require changelog, advisory, license, and release-build review. |

## Secure-screen policy

The manifest can prevent backup and network regressions but cannot set Android's
window capture flag. `SensitiveWindowPolicy` is the single security API for
that flag. The conversation host must call `apply(window)` before rendering
private message, invitation, identity-verification, recovery, or key-management
content; it may call `clear(window)` only after all such content leaves the
window. `FLAG_SECURE` blocks ordinary screenshots, screen recordings, and
recent-task snapshots on supported Android versions. It does not protect a
compromised operating system, a camera, or accessibility software with device
control.

The current `MainActivity` has not yet wired this policy because the present
prototype has no authenticated or encrypted conversation state. Wiring it is a
release gate before real message content or key material is displayed.

## Keystore and local state

The current database stores prototype aliases, invitation data, UUID references,
and message text in application-private SQLite storage. Android file-based
encryption protects it while a normally configured device is locked, but the
database is not independently encrypted. A rooted, unlocked, debug-enabled, or
malware-compromised device is outside the protection provided by this store.

Before libsignal state or private messages are production data, use an Android
Keystore non-exportable AES-GCM key to protect a versioned local state envelope.
The wrapper must bind ciphertext to its record type and schema version, handle
key invalidation without silently creating replacement identity material, and
make ratchet-state updates atomic with message acceptance or outbound enqueue.
It must not try to export private Keystore key material. The database layer and
protocol adapter need a separate migration and crash-recovery review before
this change.

## Rooted devices, signing, and supply chain

Root detection is not a trust decision: it is bypassable and can create a false
sense of safety. The app should warn only when a concrete integrity signal is
available and should not claim that root detection protects message secrecy.
The security model must assume that a device under attacker control can read
process memory and act as the user.

Release artifacts need a dedicated signing key kept outside the repository,
Android App Signing or an equivalently protected signing workflow, reproducible
release provenance, and verification of the final signed APK/AAB certificate
digest. `.gitignore` excludes common key, certificate, and local-secret files;
the CI workflow scans repository history with a hash-pinned Gitleaks release.

The project currently depends on repositories with centrally declared, exact
versions. This controls resolution drift but does not prove dependency safety.
Before each release, review transitive dependencies, security advisories,
licenses, native artifacts, and the generated dependency graph. The selected
libsignal Android artifact requires its own ABI, native-library, shrinker, and
provenance review before it is added.

## Remaining release gates

1. Wire `SensitiveWindowPolicy` at the private-content host and test screenshot
   and task-preview behavior on supported devices.
2. Replace the prototype plaintext message and protocol-state persistence with
   a reviewed Keystore-backed design before real user data is stored.
3. Add generic-notification behavior and verify locked-device notification
   previews before notifications are introduced.
4. Add a real TLS endpoint policy, authenticated transport, and device tests
   when network transport begins; do not add broad trust exceptions.
5. Produce and verify a signed release artifact, including its signer digest,
   dependency inventory, license notices, and secret scan.
6. Test expected behavior on a physical device with backup enabled at the OS
   level, a work profile, a user-installed CA, and a locked screen.

## Verification

`AndroidSecurityPolicyTest` parses the source manifest and XML policies to
guard the narrow exported surface, absence of permissions and deep links,
backup exclusions, cleartext denial, and the system-only trust-anchor policy.
The Android instrumented foundation test additionally verifies that the
installed app rejects cleartext traffic and does not set the backup flag.
