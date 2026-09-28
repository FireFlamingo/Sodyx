# BLE transport research

**Status:** Phase 13 research only, 2026-09-23. This document does not
authorize a BLE implementation. It records the decisions that would constrain
Phase 14 (direct delivery) and the later Phase 15 courier experiment.

Sodyx has `minSdk 26`, `targetSdk 36`, and currently declares no Bluetooth
permissions. Android supports BLE discovery, GATT connections, and data
transfer, but actual capability differs by radio and OS build. The direct
transport must carry an existing opaque encrypted envelope; it must not create
an alternate message format, key agreement, identity system, or plaintext
transport protocol.

## Security goal and limits

The goal is short-range delivery between nearby consenting Sodyx devices while
making a passive local observer's correlation and an active peer's abuse harder.
It does **not** provide anonymity against radio observation, prevent jamming,
prove physical proximity, or make an untrusted nearby device trustworthy.

The BLE link is an untrusted carrier. Android warns that BLE data shared after
pairing is accessible to all apps on the same device, so link-layer pairing is
not an application confidentiality boundary. Application envelopes remain
end-to-end encrypted and authenticated before they enter BLE and are verified
after they leave it. [Android BLE overview](https://developer.android.com/develop/connectivity/bluetooth/ble/ble-overview)

### Threat model

| Actor or event | Observable or possible action | Required response |
| --- | --- | --- |
| Passive radio observer | Records addresses, advertisement timing, service UUIDs, signal strength, and advertisement bytes; correlates repeated beacons or traffic bursts. | Never place a stable relationship, account, device, envelope, or recipient identifier in an advertisement. Rotate app-level recognition material on a short schedule; add cover traffic only after measured battery and privacy review. Do not claim rotation prevents location tracking. |
| Active nearby attacker | Scans, advertises lookalike records, connects repeatedly, sends malformed fragments, exhausts GATT slots, replays old data, or jams radio. | Treat all scan records and GATT input as hostile; authenticate after connection; strictly bound parsing, connections, memory, CPU, bytes, and retries; reject replayed/expired envelopes. Jamming remains an availability attack. |
| Malicious courier or relay | Stores, drops, delays, duplicates, reorders, selectively forwards, and observes encounter timing and ciphertext size. | Courier data is opaque; enforce envelope expiry, duplicate suppression, hop/TTL budget, storage quotas, and rate limits. Delivery receipt must never reveal plaintext or a stable identity. |
| Malicious or compromised contact | Recognizes any presence material it is entitled to recognize and may track it, relay it, or send it to others. | State this residual risk in product UX. Do not treat contact-recognition material as an anonymous broadcast credential. Support relationship/session closure so future recognition material stops being issued. |
| Device loss, app compromise, or OS logs | Reads retained envelopes, transport cache, diagnostic data, or relationship material. | Keep the BLE queue in the protected application store, retain only bounded ciphertext metadata, redact logs, and make queue cleanup part of session/key destruction. Hardware-backed and protocol-key decisions remain outside this research phase. |

## Android feasibility facts

Android BLE uses a central/peripheral connection role and separate GATT
client/server data roles. A direct phone-to-phone design therefore needs one
device able to advertise and host a GATT service, and the other able to scan and
connect. This must be checked at runtime: a radio may not support the needed
advertising capability. Android exposes
`BluetoothAdapter.isMultipleAdvertisementSupported()` for this check. [BLE roles](https://developer.android.com/develop/connectivity/bluetooth/ble/ble-overview)
[Adapter capability](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter)

For Android 12 and newer, scan, advertise, and connection operations require
the runtime `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, and `BLUETOOTH_CONNECT`
permissions respectively. For API 26 through 30, legacy Bluetooth permissions
and location permission rules apply. `neverForLocation` is permissible only if
Sodyx can truthfully assert that it never derives physical location from scan
results; Android documents that it can filter some beacon results. Permission
copy must explain nearby delivery and must not promise unobservable operation.
[Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)

Discovery cannot rely on a continuous background scan. Android advises stopping
after finding a target and never scanning in a loop. A filtered `PendingIntent`
scan can wake a stopped process for a matching advertisement, but a normal
callback scan requires the process to stay alive. WorkManager/Job execution can
be interrupted and is appropriate only for short work. Companion-device APIs
are a poor default: Android documents limited filtering and no support for
random MAC addresses, which conflicts with rotating-address privacy goals.
[Finding BLE devices](https://developer.android.com/develop/connectivity/bluetooth/ble/find-ble-devices)
[Background BLE communication](https://developer.android.com/develop/connectivity/bluetooth/ble/background)

A foreground service with the `connectedDevice` type may be needed while a
user-visible direct transfer is active; Android 12+ launch restrictions still
apply. Long-lived presence observation or unrestricted background behavior
requires additional companion-device capabilities and has a battery cost. The
first direct-delivery version should therefore be foreground, user-initiated,
and time-bounded. Background opportunistic delivery remains unverified.

Advertising is only a rendezvous hint, never the envelope carrier. Legacy
advertising can fail when data exceeds 31 bytes. Extended-advertising sizes and
features vary by controller and must be queried rather than assumed. Do not
include the Bluetooth device name or transmit power, as both add metadata.
[Advertising failure limits](https://developer.android.com/reference/android/bluetooth/le/AdvertiseCallback)
[Advertising API](https://developer.android.com/reference/android/bluetooth/le/BluetoothLeAdvertiser)

After a GATT connection, an implementation must use the negotiated MTU reported
by `onMtuChanged`, never assume a payload size. Android 14 requests ATT MTU 517
for the first client request on a connection and ignores later requests; the
peer/controller can still yield a smaller usable value. A write without response
can be truncated to the MTU. [BluetoothGatt.requestMtu](https://developer.android.com/reference/android/bluetooth/BluetoothGatt#requestMtu(int))

## Proposed direct-delivery boundary

These are design decisions for a future Phase 14 review, not an implementation
specification:

1. The BLE adapter accepts and returns `EncryptedEnvelope` bytes plus explicit
   delivery outcome metadata. It has no plaintext, contact alias, profile, or
   message-content API.
2. Advertising and scanning establish only a short-lived rendezvous. They do
   not contain a stable device address under application control, a contact ID,
   a relationship ID, an envelope ID, or an unchanging Sodyx-specific person
   identifier. A common service UUID is allowed only if review accepts that it
   makes Sodyx use detectable without identifying a relationship.
3. A connection must run a versioned, bounded authentication and capability
   exchange before accepting envelope bytes. It must use authenticated material
   already provided by the selected messaging protocol or a separately reviewed
   protocol component. This document deliberately does not invent that
   cryptographic exchange.
4. The direct transfer is foreground and opt-in, has a visible active-transfer
   state, has a strict connection deadline, and tears down GATT state after the
   batch completes or fails.
5. Bluetooth addresses, RSSI, raw advertising bytes, GATT errors, and envelope
   ciphertext are sensitive telemetry. They are not logged in production.

### Rotating identifiers and private recognition

A MAC address is neither a stable identity to publish nor a private-recognition
mechanism. The application cannot assume it controls Android's own BLE address
rotation. Nor can a random advertisement alone tell a trusted contact which
nearby random device is theirs.

The candidate pattern is a short-lived, fixed-length rendezvous token derived
from already authenticated relationship/session state, with an explicit epoch
and an overlap window for clock skew. A scanner computes only the tokens it is
entitled to recognize for the current and adjacent epochs, then connects only
after a match. The token is a hint: it must be authenticated again over GATT and
must be useless after the narrow epoch expires. A passive observer can still
link packets within an epoch and infer co-location from connection timing.

This pattern has an unresolved scale constraint. A device with many
relationships cannot safely advertise an unbounded contact-specific token set
in a small BLE advertisement. A single broadcast token recognizable by many
contacts may let those contacts correlate the broadcaster. Phase 14 must set a
small tested relationship limit or keep BLE direct delivery to an explicit
user-selected peer. It must not silently fall back to a stable broadcast ID.

The following remain **unverified assumptions**, requiring protocol review and
device evidence before adoption:

- a suitably scoped, rotatable secret can be exposed by the selected libsignal
  integration without weakening ratchet state or creating a new protocol;
- the token format fits the smallest supported advertisement path once service
  overhead is included;
- token rotation, scanner filtering, and Android/controller address behavior
  work across the supported API 26+ device set;
- contact closure reliably removes future recognition ability without retaining
  a stable radio identifier.

## Framing, fragmentation, and replay

GATT characteristics transport fragments, not messages. A future adapter needs
a small binary frame header with a protocol version, transfer nonce or ID,
fragment number, total count or bounded total length, and final integrity state
from the authenticated transport/application layer. It must:

- derive a maximum fragment payload from the negotiated MTU and reserve header
  space;
- cap the envelope size, fragment count, concurrent assemblies, per-peer bytes,
  and assembly lifetime before allocating buffers;
- accept fragments out of order only if the bounded reassembly state can do so;
  otherwise specify in-order transfer and a retry rule;
- authenticate the complete envelope before handing it to the message layer;
- never mark delivery successful merely because GATT acknowledged a write;
- delete incomplete state on timeout/disconnect and retain only a bounded
  replay/duplicate cache until expiry.

Replay handling belongs at more than one layer. The selected E2EE session must
reject invalid or already-consumed message state; the BLE adapter also needs a
short-lived transport duplicate cache to prevent repeated connection and
reassembly work. The cache key must be a cryptographic, non-plaintext envelope
identifier supplied by the authenticated envelope design, not a public stable
contact identifier. Final anti-replay semantics cannot be selected until the
Phase 6 envelope format and durable ratchet state are complete.

## Courier and mesh constraints

Phase 15 begins only after direct delivery is stable. A courier stores and
forwards opaque authenticated envelopes. It can observe that it saw a ciphertext
of a given length at a time and that it later offered it to another radio peer;
it can also retain, drop, replay, delay, and correlate it. It must not learn
plaintext, contact aliases, recipient identifiers, or usable long-lived
relationship identifiers.

The future courier record needs an authenticated expiry, a bounded hop/TTL
budget, a cryptographic duplicate key, a maximum ciphertext size, a per-sender
and global queue quota, eviction rules, and a transfer rate budget. A courier
must decrement hops atomically before forwarding and refuse records whose
expiry, hop budget, or storage policy fails. Repeating advertisements or offers
must be rate-limited and jittered only after measurement. None of these fields
should be trusted until the authenticated envelope/courier format validates
them; otherwise a malicious peer can create loops, storage exhaustion, or
permanent replay work.

## Device-validation plan

No emulator result can establish BLE interoperability, controller capability,
or background behavior. Before writing a production implementation, collect
evidence on physical Android devices with Bluetooth enabled, with each device
identified only by model, Android version, API level, vendor build, and whether
it supports advertising/GATT-server roles. Do not put device MAC addresses,
scan logs, or raw rotating tokens in the report.

| Test | Required evidence | Pass criterion |
| --- | --- | --- |
| Capability and permissions | API 26, 30, 31, 34, and 36 coverage where available; at least two vendors; runtime capability and permission outcomes. | Unsupported advertising/GATT-server devices fail closed with clear UI. Permission denial has no scan/advertise retry loop. |
| Nearby direct transfer | Bidirectional transfers between varied vendor pairs, screen on/off, app foreground/background transition, Bluetooth toggled, reconnect after range loss. | Authenticated envelope is either delivered exactly once at the message layer or reported pending/failed; no plaintext or stable ID appears in radio/application diagnostics. |
| MTU and fragmentation | Capture negotiated MTU values and transfer bounded envelopes at minimum observed payload, normal payload, and maximum supported payload. | No truncation, buffer overrun, unbounded allocation, or false delivery acknowledgement. |
| Rotation and recognition | Record only aggregate match/fail rates across epoch boundary, clock skew, and relationship closure; use a controlled passive scanner when permitted. | Known contact recognizes within declared overlap; unknown scanner cannot derive a stable relationship ID; no stable advertisement field persists across epochs. |
| Malformed-peer resilience | Fuzz fragment headers, lengths, order, duplicates, disconnects, service discovery responses, and connection floods on a lab device. | Parser rejects safely; connection, CPU, memory, queue, and retry budgets hold; app remains usable. |
| Replay and expiry | Replay complete and partial transfers before/after expiry and after restart; offer duplicate courier records in loops. | Replays do not create a second message effect, resurrect expired data, or create an unbounded cache/queue. |
| Background and battery | Measure idle scan/advertise and active transfer battery use with Android battery statistics across supported builds. Test PendingIntent wake paths and foreground-service restrictions. | Product claims match observed behavior; no indefinite background scan, and any background mode has explicit opt-in and measured budget. |
| Courier experiment | Three or more physical devices, duplicate paths, partition/rejoin, constrained storage, and malicious drop/replay peer. | TTL/hop/expiry/duplicate/queue controls terminate propagation; courier observes only documented metadata. |

## Implementation gates

Phase 14 may start only when all of the following evidence exists:

1. Phase 6 supplies an authenticated, durable encrypted-envelope boundary and
   a security review approves how BLE rendezvous authentication uses it.
2. A chosen API 26+ device support policy has been validated, including an
   honest fallback for radios that cannot advertise or host the required GATT
   service.
3. The recognition-token design, timing, retention, and contact-closure effect
   have written crypto/privacy review; no stable relationship identifier is in
   advertisements.
4. The framing, replay, storage, expiry, telemetry-redaction, and abuse limits
   have deterministic tests plus the physical-device evidence above.
5. Direct delivery is stable before any mesh/courier implementation begins.

## Sources

- [Android BLE overview and role model](https://developer.android.com/develop/connectivity/bluetooth/ble/ble-overview)
- [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [Find BLE devices](https://developer.android.com/develop/connectivity/bluetooth/ble/find-ble-devices)
- [Communicate in the background](https://developer.android.com/develop/connectivity/bluetooth/ble/background)
- [BluetoothLeScanner API](https://developer.android.com/reference/android/bluetooth/le/BluetoothLeScanner)
- [BluetoothLeAdvertiser API](https://developer.android.com/reference/android/bluetooth/le/BluetoothLeAdvertiser)
- [BluetoothGatt MTU API](https://developer.android.com/reference/android/bluetooth/BluetoothGatt#requestMtu(int))
- [Android 14 BLE MTU behavior](https://developer.android.com/about/versions/14/summary)
