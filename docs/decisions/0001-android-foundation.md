# ADR 0001: Minimal native Android foundation

Status: accepted for Phase 0, 2026-09-11.

## Decision

Use a single Android application module, Kotlin, and Jetpack Compose. The activity
hosts a blank surface in `io.sodyx.app.ui`. Introduce domain, storage, protocol,
and transport boundaries when their phases require real implementations. No DI,
navigation framework, database, backend, or cryptographic placeholders are needed.
The package/application ID `io.sodyx.app` is provisional and must be confirmed before
distribution; it does not assert ownership of that domain.

Minimum SDK 26 provides an Android 8 baseline without introducing legacy-platform
branches at project inception. It excludes older devices; this is a product
compatibility choice, not a claim that all API 26 devices remain secure. Compile
and target SDK 36 match the locally installed platform and emulator and opt into
Android 16 behavior. Review the current platform and store requirements before
distribution. No production distribution is planned in Phase 0.

Pin AGP 8.13.2, Gradle 8.13, Kotlin/Compose compiler 2.2.20, Compose BOM
2025.09.01, and Activity 1.11.0 as a compatible foundation for the installed
Android Studio 2025.1.3 environment. These are deliberate baseline versions, not
a claim to use every latest release. AGP's documented compatibility includes
API 36.1, Gradle 8.13, and JDK 17 minimum. Use JDK 21 locally and in CI with
Java/Kotlin bytecode targeting 17. Evaluate upgrades together before later phases.

Use Compose Foundation alone for the empty root; defer design tokens, fonts,
screens, and Material component choices to Phase 1. Do not add tests of fictitious
domain behavior. JUnit source-manifest checks and real-device Compose tests give
the otherwise blank app meaningful regression coverage.

## Build and privacy defaults

- No network or runtime permissions, logging calls, analytics, or data storage.
- Backups and cleartext traffic disabled in the manifest; Android 12+ extraction
  rules exclude all supported storage domains from cloud backup and device transfer.
  AndroidX adds an app-private signature permission, a non-exported startup provider,
  and an exported profile installer receiver protected by the system DUMP permission.
- Only the launcher activity is intentionally exported; it handles no application
  commands, credentials, deep links, or user data.
- Release builds are non-debuggable, optimized, and unsigned. Debug and release
  install under separate IDs. Signing and release distribution remain future work.
- Versions are centralized; dependency repositories are controlled in settings.
  Wrapper downloads carry an official SHA-256 checksum; CI actions use commit pins.
- Spotless/ktlint checks formatting; Kotlin compilation and Android lint fail on
  warnings. Version-update lint notices are exempt because upgrades are deliberate.
- Local credential exclusions, a staged-file/Gitleaks hook, and CI history scans
  reduce accidental exposure. They are not a guarantee that secrets cannot escape.

No E2EE, anonymity, metadata resistance, or deletion guarantee exists yet. Disabling
backup is an early default, not a flash-erasure guarantee. Backup and transfer rules
need real-device validation when persistence and key lifecycle are introduced.
Transitive dependency verification/locking and a
full dependency security review remain later hardening work.

## References

- [AGP 8.13 compatibility](https://developer.android.com/build/releases/agp-8-13-0-release-notes)
- [Compose BOM](https://developer.android.com/develop/ui/compose/bom)
- [AndroidX Activity releases](https://developer.android.com/jetpack/androidx/releases/activity)
- [Gradle Java compatibility](https://docs.gradle.org/8.13/userguide/compatibility.html)
