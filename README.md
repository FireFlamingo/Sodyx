# Sodyx

Native Android foundation for a privacy-first, one-to-one messenger.

**Phase 1:** a dark, native Compose design study with a conversation list, sample chat,
invitation entry point, privacy/settings page, session-ending demonstration, and
empty states. All content is local sample data; nothing is sent. This is not yet a
usable or security-reviewed messenger. Phase 2 requires explicit authorization.

Use **Settings → Sample conversations** to explore the empty list. Opening Frozen
Lake shows a connection without an active session. Closing a sample session affects
that preview only; Settings can restore it. Drafts and preview choices live only in
memory and reset when the activity is recreated. Invitation buttons explain the
future interaction without creating tokens or opening the camera.

## Build

Use JDK 21 (JDK 17 is also supported by the selected Android build tools), Android SDK
Platform 36, and SDK Build Tools 35.0.0. Install them through Android Studio. Set
`ANDROID_HOME` or create an untracked `local.properties` containing your `sdk.dir`.
Set `JAVA_HOME` to a compatible JDK; the system JDK 25 cannot run Gradle 8.13.
For IDE use, Android Studio Narwhal 3 / 2025.1.3 or a compatible newer version is required.

```sh
./gradlew spotlessCheck testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
```

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
session cancellation, per-conversation closure, empty-list navigation, and unsent
draft behavior. JVM tests check source-manifest policy. Android lint treats warnings as
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
for Phase 1 tokens, typography, interactions, accessibility, and font licensing.
