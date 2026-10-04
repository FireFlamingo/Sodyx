# Android security controls

The current core app requests only `INTERNET`. Its only exported component is the launcher activity; there are no deep links, WebViews, analytics, crash reporters, camera, Bluetooth, contacts, location, or notification permissions.

- Android backups and device-transfer extraction are disabled.
- MainActivity applies `FLAG_SECURE`, reducing screenshots and recent-app previews on supported Android versions.
- Each connection's private protocol records, relay secrets, and local message text are AES-GCM encrypted with a separate Android Keystore key. SQLite still exposes row counts, local IDs, expiry and display metadata.
- Release traffic requires HTTPS and system trust anchors; redirects are disabled. Debug-only localhost access is restricted to integration fixtures.
- Contact cards are shared only through an explicit user action. The app does not automatically copy plaintext to the clipboard.
- No message, key, capability, or network request logging is present. UI errors are generic rather than displaying library or network exception contents.
- Closing a connection destroys its wrapping key and database. Physical flash overwriting, another person's copies, and a compromised endpoint are not promised.
- Dependencies are version-pinned. Testing JNI libraries and desktop native libraries are excluded from APK packaging.
- Release builds use minification and resource shrinking. The owner must provide release signing material outside Git.

Android Keystore protection depends on the device and its supported hardware. Root or OS compromise can expose live app memory and actions. Root detection would not establish trust and is outside this release.
