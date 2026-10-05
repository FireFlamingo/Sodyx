# Core version 0.4.0 verification

Verified on 2026-10-05. The active product scope is native Android one-to-one
text messaging with verified contact cards, libsignal encryption, encrypted
local persistence, a durable single relay, retry, and deliberate closure.

## Requirement evidence

| Requirement | Evidence |
| --- | --- |
| Independent pairwise identities and verified setup | `ContactCardTest`, `SignalE2eeEngineTest`, `CoreMessengerIntegrationTest` |
| Real encrypt/decrypt, tampering and replay rejection | Native libsignal host tests and Android integration tests |
| Ratchet/message atomicity and encrypted local storage | `CryptoStoreTest` verifies encryption on disk, rollback, reopen and message records |
| Outgoing retry after a lost acceptance response | `CoreMessengerIntegrationTest` retries persisted ciphertext and accepts it once |
| Actual HTTP relay delivery and reply | `CoreLiveRelayTest`, run against the real Node service through ADB reverse |
| Relay expiry, capability scopes, bounds and revocation | Six Node tests; persistent restart test confirms bearer capabilities are absent from the database |
| Usable pairing, send/receive, restart and close UI | `CoreMessengerUiTest` drives the default app, exchanges cards, sends and receives text, recreates the activity and closes |
| Polling stops when backgrounded | UI test stops the activity for longer than the ten-second polling interval and checks that retrieval calls do not increase |
| Local erasure and relay revocation | Core integration tests verify closed tombstones, database removal and mailbox revocation |
| Platform controls | Manifest tests, lint and installed-app tests verify narrow permissions, disabled backup/cleartext defaults and secure-window flags |

## Results

- Domain: 12 host tests, zero failures.
- libsignal adapter: 4 host tests, zero failures.
- Android app utilities and policy: 17 host tests, zero failures.
- Relay: 6 tests, zero failures.
- Full Android device suite: 41 tests, zero failures, including the live relay.
- Foreground lifecycle correction: the complete core UI test was rerun and passed.
- Final host gate: formatting, all host tests, debug/release lint and both ARM64 builds passed.
- ARM64 package inspection: debug signature verified; testing JNI libraries and desktop natives absent; bundled license matches the project license.
- Release network policy inspection: cleartext disabled, system trust anchors only, and no debug localhost exception.

The runtime device was an isolated Android API 36 x86_64 emulator. ARM64 debug
and minified unsigned-release APKs were built and inspected, but this evidence
does not substitute for testing on a specific physical phone. Release signing
and a real HTTPS relay domain belong to the distributor.

There are no read receipts, push notifications, attachments, groups, BLE, web
client, multi-hop routing, or message-size anonymity guarantees in this scope.
Passing the checks above is implementation evidence, not an independent
cryptographic or production security audit.
