# Sodyx fixed-size encrypted-cell framing

This is a pure Kotlin transport-framing module. It splits one already-encrypted
envelope into fixed-size cells and reassembles them under strict resource limits.
It has no cryptographic primitives, key handling, serialization of plaintext, or
transport code.

## Boundary

```text
application plaintext -> E2EE -> opaque ciphertext + E2EE binding
                                      -> this module -> fixed 1024-byte cells
```

`OpaqueEncryptedEnvelope.ciphertext` is treated as opaque bytes. The optional
`e2eeBinding` is also opaque to this module. It must be authenticated by the
E2EE layer, typically by binding it into that layer's authenticated data or
encrypted payload before framing. `EnvelopeAuthenticator` is supplied by that
layer and is the only component allowed to decide that an assembled ciphertext
is acceptable.

The framing header is deliberately not a cryptographic authenticator. Altering
or replaying cells can still cause drops, expiry, buffering pressure, and failed
authentication. Framing prevents malformed values from reaching the E2EE layer
and bounds those effects; it cannot prevent a network attacker from withholding
or flooding cells. A completed message is returned only after the caller's E2EE
authenticator accepts the reconstructed ciphertext and binding.

## Wire format and limits

Every wire cell is exactly 1024 bytes. Its 80-byte header is repeated in every
cell so cells can arrive in any order:

| Bytes | Field |
| --- | --- |
| 0–3 | `SDYX` magic |
| 4 | format version (`1`) |
| 5 | E2EE-binding length (0–32) |
| 6–7 | reserved, zero |
| 8–23 | opaque 16-byte message ID |
| 24–27 | zero-based cell index |
| 28–31 | cell count |
| 32–35 | ciphertext length |
| 36–43 | expiry, Unix milliseconds |
| 44–75 | E2EE binding, zero-padded |
| 76–79 | reserved, zero |
| 80–1023 | ciphertext segment followed by zero padding |

The payload capacity is therefore 944 bytes. Maximum ciphertext is 1 MiB and
maximum cells per message is derived from that limit. A reassembler retains at
most 16 incomplete messages and 2 MiB of payload. It rejects bad magic,
versions, lengths, indices, nonzero reserved bytes/padding, metadata conflicts,
conflicting duplicates, expired cells, and attempts that exceed a configured
limit. Exact duplicate cells are idempotent. Cells may be reordered. Missing
cells remain incomplete until expiry and are removed by `expire` or the next
`accept` call.

The message ID is a transport correlation value. It must be freshly generated
by the E2EE/transport owner using an appropriate source of randomness, scoped to
the active session, and must not be a globally reusable user identifier.

## Cell-size evidence

The selected 1024-byte cell size is provisional until the Phase 6 two-party
libsignal harness produces the captured, serialized ciphertext data listed in
`benchmarks/README.md`. The benchmark is deliberately part of this module so
the choice can be reproduced from actual encrypted envelopes and revised in a
format-version bump. **No production integration may claim that 1024 bytes was
selected from synthetic plaintext sizes.**

The initial capacity arithmetic is recorded for integration planning only:
1024-byte cells leave 944 bytes for ciphertext after the fixed header. It gives
one-cell carriage for a typical short encrypted message and limits the visible
length of such messages to a single cell. The benchmark must confirm this with
real libsignal serialized envelopes before this format is released.

## Integration contract

1. E2EE must create and later verify the `e2eeBinding`; it should bind the
   session-scoped message ID and expiry policy. Do not use aliases, contacts, or
   a stable account identity in this binding.
2. The transport must preserve each cell byte-for-byte. Cells are not transport
   authenticated by this module.
3. Pass a bounded expiry accepted by the product policy. Do not derive it from
   untrusted network time.
4. Only release `CompletedEnvelope` after `EnvelopeAuthenticator.verify` has
   succeeded. Decrypting or interpreting a rejected value is a caller bug.
5. Choose production limits from device and relay measurements. Attachments
   require a separate format/version and are intentionally unsupported here.

## Benchmark procedure

See [benchmarks/README.md](benchmarks/README.md). It requires length-only,
redacted captures from real libsignal serializations for both initial PreKey and
established Signal messages. The harness must run before this module is wired
into the app, and any changed cell size requires a new format version plus the
full malformed-input regression suite.
