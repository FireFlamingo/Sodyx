# Sodyx

Native Android foundation for a privacy-first, one-to-one messenger.

**Phase 1:** a dark, native Compose design study with a conversation list, sample chat,
invitation entry point, privacy/settings page, session-ending demonstration, and
empty states. All content is local sample data; nothing is sent. This is not yet a
usable or security-reviewed messenger.

**Phase 2:** an immutable, pure-Kotlin domain model for local identity references,
pairwise relationships, invitation intent, conversations, sessions, lifecycle
transitions, plain messages, and envelope references. It defines boundaries and
validation only; it does not provide keys, persistence, transport, QR redemption,
secure identity generation, or cleanup guarantees. The Phase 1 UI remains a static
preview and does not consume this model.

**Phase 3:** a local persistence adapter around the Phase 2 model. The prototype
uses app-private SQLite through `SQLiteOpenHelper`, with repository-owned
transactions, foreign-key checks, bound parameters, and logical deletion. It
does not add encryption, key management, transport, peer deletion, backups, or
real invitation redemption. See [local persistence](docs/LOCAL_PERSISTENCE.md),
[session lifecycle](docs/SESSION_LIFECYCLE.md), and [identity model](docs/IDENTITY_MODEL.md)
for the boundaries and guarantees.

**Phase 4:** local contact-establishment simulation. The app can create a
versioned one-time invitation, render it as a QR image, import a local text or
handoff representation, enforce expiry and single redemption, create a local
relationship, generate relationship-scoped pseudonyms, and display cosmetic
aliases. QR rendering and import perform no network I/O and do not authenticate
or establish a live contact. The guarantee is per local database; there is no
issuer synchronization or server. Payloads have no authenticity or
confidentiality, so syntactically valid tampering cannot be detected.

Phase 4 does not provide global search, email or phone signup/discovery, public
usernames, camera permission or camera scanning, network transport,
cryptography, key exchange, or remote contact establishment. See [invitation
lifecycle](docs/INVITATION_LIFECYCLE.md) and [the local invitation decision](docs/decisions/0002-local-invitations.md).

**Phase 5:** protocol research is complete and libsignal 0.102.2 is selected
for Phase 6 under an AGPL-compatible Sodyx project and distribution model. The
implementation will use the exact pins
`org.signal:libsignal-android:0.102.2` and
`org.signal:libsignal-client:0.102.2` behind a narrow Sodyx adapter. Signal
states that libsignal is intended for its own use and does not promise a stable
general-purpose external API, so the exact pin is deliberate and API churn is
an explicit maintenance gate. Official artifact metadata has been verified,
and a minimal API compile smoke test passed: libsignal 0.102.2 reports minSdk
23 and four Android ABIs, compatible with Sodyx's minSdk 26. The all-ABI debug
package is 1.05 GB and contains testing native libraries; ABI splits,
production packaging, durable stores, runtime, and two-device protocol tests
remain outstanding. No crypto dependency or production crypto code has been
added and Phase 6 has not started. See the [Phase 5 security
decision](docs/SECURITY_DECISIONS.md). Sodyx is licensed under the [GNU AGPL
version 3 only](LICENSE); qualified review of the exact release obligations
remains required before distribution.

On a fresh install the database is empty. Create a test connection, open it, start
a local session explicitly, and save messages locally as plaintext. Ending a
session logically deletes its stored messages while preserving the relationship
and closed-session tombstone. From the invitation flow, create a local
versioned invitation, view its QR representation, or import a text/handoff
representation to exercise expiry, single redemption, pseudonym creation, and
alias display. These actions remain local simulations: there is no camera
permission, network transport, cryptography, or peer-side deletion.

## Build

Use JDK 21 (JDK 17 is also supported by the selected Android build tools), Android SDK
Platform 36, and SDK Build Tools 35.0.0. Install them through Android Studio. Set
`ANDROID_HOME` or create an untracked `local.properties` containing your `sdk.dir`.
Set `JAVA_HOME` to a compatible JDK; the system JDK 25 cannot run Gradle 8.13.
For IDE use, Android Studio Narwhal 3 / 2025.1.3 or a compatible newer version is required.

```sh
./gradlew spotlessCheck :domain:test testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
```

The Phase 2 domain tests can be run independently with
`./gradlew :domain:test`; the full checks include that task in CI.

Phase 3 repository and SQLite instrumentation tests run locally against an
Android emulator or connected test device. The complete local check includes
host formatting, domain/app tests, lint, debug/release builds, and device
instrumentation; CI continues to run the host checks and builds.

On Windows, use `gradlew.bat`. Format source with `./gradlew spotlessApply`.
The first build downloads dependencies from Google Maven, Maven Central, and the
Gradle Plugin Portal. The wrapper distribution is pinned with SHA-256.

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
Release APK: `app/build/outputs/apk/release/app-release-unsigned.apk`.
Release signing is deliberately unconfigured; never commit signing material.
Debug installs as `io.sodyx.app.debug`, separate from the provisional release ID.

With an emulator or test device connected:

```sh
./gradlew connectedDebugAndroidTest
./gradlew installDebug
adb shell am start -W -n io.sodyx.app.debug/io.sodyx.app.MainActivity
```

Instrumentation tests verify launch, activity recreation, installed privacy defaults,
session cancellation, per-conversation closure, empty-list navigation, unsent
draft behavior, repository round trips, transactions, foreign-key behavior,
stale writes, concurrency, deletion isolation, and local invitation lifecycle
behavior. JVM tests check source-manifest policy; domain tests check identity,
relationship, invitation, message, and session invariants. Android lint treats warnings as
errors except deliberately reviewed SDK/dependency update notices. CI runs the
host checks and builds; device tests currently run locally.

## Contribution safeguards

Install [Gitleaks](https://github.com/gitleaks/gitleaks/releases/tag/v8.28.0)
(tested with 8.28.0) on `PATH`, then enable the repository hook:

```sh
git config core.hooksPath .githooks
```

Alternatively, configure an absolute local scanner path with
`git config sodyx.gitleaksPath /path/to/gitleaks`; this setting stays in `.git/config`.

The hook rejects common local/credential file paths and scans staged content with
redacted output. CI also scans Git history. Ignore rules, hooks, and scanners are
defense in depth: they cannot identify every secret or prevent deliberate bypass.
Always review staged content before committing. The local master brief belongs in
`.git/info/exclude` and must never be staged, committed, or pushed.

See [the foundation decision](docs/decisions/0001-android-foundation.md) for scope,
SDK choices, and current privacy limitations. See [the design system](docs/DESIGN_SYSTEM.md)
for Phase 1 tokens, typography, interactions, accessibility, and font licensing;
Phase 3 screens read local repository state where implemented instead of using
Phase 1-only fixtures. See [local persistence](docs/LOCAL_PERSISTENCE.md) for
repository and deletion boundaries.

## License

Unless otherwise noted, Sodyx is licensed under the GNU Affero General Public
License version 3 only (`AGPL-3.0-only`). See [LICENSE](LICENSE). Bundled
third-party components retain their own licenses and notices.
