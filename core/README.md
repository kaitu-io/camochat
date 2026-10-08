# chencang-core

CamoChat（陈仓）V1 协议核心库。

## Status

V1 协议，当前客户端在用。协议概览见 `../docs/protocol/README.md`。

## Build

```sh
cargo test --all-features
```

## Architecture

- **Pure library**: no I/O, no async, no platform code
- **Cryptography**: XChaCha20-Poly1305 + X25519 + Ed25519; ML-KEM-768 + ML-DSA-65 for the PQ suite
- **Protocol**: classical suite `0x01` (default) — in-band X3DH + key confirmation, then symmetric-key
  ratchets (the DH ratchet steps only once, on Alice's first send; see protocol notes §6). PQ-hybrid
  suite `0x02` (PQXDH + periodic KEM ratchet) is compiled in but not yet enabled in the apps.
- **Wire**: binary header + AEAD, carried as `🔒` + CJK14 text (CJK ideographs that survive third-party IMs)

## Security

This crate implements security-critical code. **Do not modify without two reviews from cryptographically-literate engineers + KAT verification.**

Audit baseline: V1 protocol spec rev 1.0.3 (2026-05-14).

## Module Map

| Module | Purpose |
|---|---|
| `error` | Unified error types |
| `primitives` | Audited crypto wrappers (X25519, Ed25519, ML-KEM-768, ML-DSA-65, XChaCha20-Poly1305, BLAKE2b/HKDF, Argon2id) |
| `encoding` | Text codecs: CJK14 (current), base32768 (superseded), z-base32 (legacy) |
| `identity` | IK quad keypair + 16-byte all-pubkey fingerprint |
| `prekey` | SPK (Ed25519-signed; Ed25519+ML-DSA double-signed for PQ) + OPK pool |
| `handshake` | Classical X3DH + in-band CBOR bundle/header + key confirmation; PQXDH-hybrid |
| `session` | SessionState + symmetric / DH / KEM ratchets + skipped key buffer |
| `wire` | Binary header + AAD |
| `payload` | L2 app frames (TEXT, MEDIA_REF), L4 text wrap, media-blob key derivation |
| `blob` | `.cca` encrypted media-blob container |
| `safety` | Emoji safety fingerprint |

## Testing

```sh
cargo test                              # unit + integration
cargo test --release                    # production-mode
cargo fuzz run parse_wire -- -max_total_time=300  # fuzz parser 5 min (nightly + cargo-fuzz required)
```

## Known Answer Tests (KAT)

| Primitive | Source |
|---|---|
| X25519 | RFC 7748 §5.2 |
| Ed25519 | RFC 8032 §7.1 |
| XChaCha20-Poly1305 | draft-irtf-cfrg-xchacha-03 §A.3.1 |
| BLAKE2b | RFC 7693 Appendix A |
| ML-KEM-768 | NIST FIPS 203 (round-trip + tamper) |
| ML-DSA-65 | NIST FIPS 204 (sign/verify + tamper) |

## License

AGPL-3.0-or-later（见仓库根目录 `LICENSE`）
