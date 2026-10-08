# Quickstart

An end-to-end "hello world" with the classical suite (`0x01`): in-band pairing, then one text message.
Both sides run locally here for clarity. In the apps, the two pairing blobs and every message travel as
text through a third-party messenger (copy-paste / share sheet) — there is no server, no prekey
publishing and no network on the text path. Protocol background: [`docs/protocol/README.md`](../../docs/protocol/README.md) §4–§7.

Roles follow the core's naming: **Alice** creates the invite bundle (responder), **Bob** receives it
and computes the session first (initiator).

The snippet is Kotlin (`uniffi.chencang.*`). The Swift API has the same functions and types with
labeled arguments (`try deriveInitiatorClassical(bobIk:aliceBundle:)`, `try encodeClassicalBundle(bundle:)`, …);
the production versions of this flow are
`android/shared/src/main/kotlin/app/chencang/shared/pairing/inband/InbandPairing.kt` and
`ios/ChencangShared/Sources/ChencangShared/Pairing/Inband/InbandPairing.swift`.

```kotlin
import java.security.SecureRandom
import uniffi.chencang.*

val rng = SecureRandom()

// Long-term keys (persist with serializeForLocalStorage() / serialize()).
val aliceIk = SecretIdentity()
val aliceSpk = SecretSignedPreKey(aliceIk, 1u)
val bobIk = SecretIdentity()

// --- Round 1 (Alice -> Bob): classical prekey bundle ---
val nonce = ByteArray(16).also { rng.nextBytes(it) }
val aIk = aliceIk.publicIdentity()
val aSpk = aliceSpk.publicForm()
val bundleBytes = encodeClassicalBundle(
    ClassicalPreKeyBundle(
        version = 1u, suiteId = 1u,
        ik = ClassicalPublicIdentity(ed25519 = aIk.ikSigEd25519, x25519 = aIk.ikDhX25519),
        spk = ClassicalSignedPreKey(
            x25519 = aSpk.spkX25519, sigEd25519 = aSpk.ed25519SigClassical, epoch = aSpk.spkVersion,
        ),
        opk = null,
        pairingNonce = nonce,
        inviterUsername = "Alice",
    )
)

// --- Bob: verify the bundle, derive the session, answer with Round 2 ---
val bundle = decodeClassicalBundle(bundleBytes)
val init = deriveInitiatorClassical(bobIk, bundle)          // verifies the SPK signature
val sid = ByteArray(5).also { rng.nextBytes(it) }
val headerBytes = encodeClassicalHeader(
    ClassicalInbandHeader(
        version = 1u, suiteId = 1u,
        bobIk = ClassicalPublicIdentity(ed25519 = init.bobIk.ikSigEd25519, x25519 = init.bobIk.ikDhX25519),
        ekX25519Pub = init.ekX25519Pub,
        sessionId = sid,
        confirmB = computeConfirmTag(init.sessionRootKey, init.transcript, confirmWhoInitiator()),
        bobDisplayName = "Bob",
    )
)
val bobSession = Session.initiatorAfterHandshakeClassical(
    init.sessionRootKey, sid, bundle.ik.x25519, init.ekX25519Secret,
)

// --- Alice: re-derive, check Bob's key confirmation, build her session ---
val header = decodeClassicalHeader(headerBytes)
val resp = deriveResponderClassical(
    aliceIk, aliceSpk, null, header.bobIk, header.ekX25519Pub, null, nonce,
)
check(verifyConfirmTag(resp.sessionRootKey, resp.transcript, confirmWhoInitiator(), header.confirmB))
val aliceSession = Session.responderAfterHandshakeClassical(
    resp.sessionRootKey, header.sessionId, header.ekX25519Pub,
)

// Both sides compare the 8-emoji safety fingerprint out loud.
check(deriveSafetyEmoji(init.sessionRootKey) == deriveSafetyEmoji(resp.sessionRootKey))

// --- A message: L2 frame -> L3 ciphertext -> L4 "🔒…" text ---
val wire: String = encodeWire(bobSession.encryptToBytes(encodeTextFrame("hello")))
val msg = decodeFrame(aliceSession.decryptFromBytes(decodeWire(wire)))
println((msg as DecodedMessage.Text).value)   // -> "hello"
```

## What happens next

- Persist `SecretIdentity` via `serializeForLocalStorage()` / `SecretIdentity.fromLocalStorage()` and the
  SPK via `serialize()` / `SecretSignedPreKey.fromSerialized()`. Treat the bytes as top-tier secrets
  (Keychain / Keystore).
- Persist each `Session` via `serializeState()` / `Session.fromSerializedState()` after every encrypt and
  decrypt. Skipped-key buffers and ratchet state are included; do **not** share the bytes across devices.
- In the apps, the pairing blobs are wrapped as `0xCB || type || CBOR` and sent as `🔒` text or a
  `https://<site>/p/#<base64url>` link (protocol notes §5).
- Media (voice / image / video): `encryptMediaBlob` + `encodeMediaRefFrame`; see protocol notes §8.

See [INTEGRATION.md](./INTEGRATION.md) for production concerns (threading, error mapping, release tagging).
