# Sodyx

A native Android messenger for private conversations between two people.

The core application includes:

- A separate libsignal identity and Android Keystore-backed encrypted database for each connection.
- Two-way contact card exchange with an explicit verification-code comparison.
- End-to-end encrypted text messages through a simple self-hosted relay.
- Durable outgoing queues, retry, foreground retrieval, and replay/tamper rejection.
- Local key and message destruction when a connection is closed, with relay mailbox revocation when reachable.

BLE, web clients, multi-hop routing, groups, attachments, and fixed-size cells are outside this release. Earlier experiments remain in Git history or dormant directories; they are not product features.

## Use

1. Run the [relay](relay/README.md) behind HTTPS.
2. On both phones, tap **Add connection**, enter a local name and the relay HTTPS address, and create a contact card.
3. Share each card only with the intended person. Paste their card into your connection.
4. Compare the displayed code with the code on their own phone, in person or through a separate trusted call. Tap **The codes match** only after comparing it.
5. Once both phones finish pairing, send messages. The app checks for messages every ten seconds while the conversation is open; **Refresh** also retries queued messages.
6. **Erase and close** destroys this connection's local keys and stored messages. A new conversation requires new contact cards.

Mailbox capabilities expire after seven days. Messages sent while the recipient's app is closed remain on the relay until retrieved or expired. There are no background push notifications or read receipts; **Relay accepted** means the relay accepted ciphertext, not that the other person read it.

The relay cannot decrypt messages, but it can observe connections, timing, sizes, and mailbox use. This release makes no anonymity claim. A compromised phone, copied text, or another person's retained messages are outside local erasure guarantees.

## Build and verify

Use JDK 21, Android SDK Platform 36, and Build Tools 35.0.0. Configure `ANDROID_HOME` or an untracked `local.properties`. On Windows use `gradlew.bat`.

```sh
./gradlew spotlessCheck :domain:test :security:testDebugUnitTest :app:testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
cd relay
npm test
```

The default Android build includes arm64-v8a and x86_64. Build one ABI for a smaller APK:

```sh
./gradlew :app:assembleDebug -Psodyx.abi=arm64-v8a
./gradlew :app:assembleDebugAndroidTest -Psodyx.abi=x86_64
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk` (`io.sodyx.app.debug`).
Unsigned release APK: `app/build/outputs/apk/release/app-release-unsigned.apk`.
Release signing belongs to the distributor; signing material must stay outside Git.

Device tests cover encrypted storage, real libsignal state across reopen, pairing, queued delivery, tampering, replay, UI navigation, and closure. The live relay test requires an ADB-reversed local relay and the `sodyx.liveRelay` instrumentation argument. See [protocol details](docs/PROTOCOL.md) and [Android controls](docs/ANDROID_SECURITY.md).
The [verification record](docs/VALIDATION.md) maps the core requirements to test evidence.

## Source control and license

Secrets, local configuration, build outputs, and signing keys are ignored. Enable the staged secret-scan hook with `git config core.hooksPath .githooks`; CI scans history as well.

Sodyx is licensed under GNU AGPL version 3 only (`AGPL-3.0-only`); see [LICENSE](LICENSE). Bundled dependencies retain their own licenses and notices. The selected protocol implementation is pinned to libsignal 0.102.2 because Signal does not support external consumers or promise a stable API. Upgrades require protocol and state-compatibility testing.
