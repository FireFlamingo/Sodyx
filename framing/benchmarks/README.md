# Encrypted-envelope cell-size benchmark

The fixed cell size must be selected from real, serialized E2EE output, never
from plaintext length estimates. Run a two-party libsignal 0.102.2 harness on
Android and record only the following length data. Do not retain plaintext,
ciphertext, identities, keys, session IDs, or message IDs in benchmark output.

For each plaintext length below, capture `serialize().size` from both an initial
`PreKeySignalMessage` and an established-session `SignalMessage`:

```text
0, 1, 16, 64, 256, 768, 1024, 4096, 16384, 65536 bytes
```

The future E2EE adapter must also measure the final Sodyx encrypted-envelope
serialization, including any authenticated session-scoped routing/binding
metadata that framing will carry. Record its byte length in a CSV with columns:

```text
libsignal_version,message_kind,plaintext_bytes,serialized_ciphertext_bytes,sodyx_envelope_bytes
```

Run each row at least 30 times, report minimum/median/maximum, and retain only
those aggregate statistics in the repository. Then evaluate candidate fixed cell
sizes (for example 768, 1024, 1536, and 2048) using:

```text
cells = ceil(sodyx_envelope_bytes / (cell_size - 80))
```

Select the smallest size that keeps the product's measured short-message target
in one cell without unacceptable radio, relay, or battery overhead. If the
selected size changes, change `FrameFormat.VERSION`; same-version decoders must
not accept a different wire-cell length.

This benchmark intentionally measures only size. It does not prove anonymity,
traffic-analysis resistance, E2EE correctness, or transport security.
