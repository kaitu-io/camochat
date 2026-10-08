package app.chencang.shared.pairing.inband

import java.security.MessageDigest
import uniffi.chencang.decodeWire
import uniffi.chencang.encodeWire

/**
 * In-band pairing transport + 🔒 wire disambiguation.
 *
 * Both pairing blobs (A→B [uniffi.chencang.ClassicalPreKeyBundle], B→A
 * [uniffi.chencang.ClassicalInbandHeader]) and L3 session ciphertext (DR wire,
 * text and media alike) travel over the SAME 🔒 (U+1F512) wire — `encodeWire`
 * prepends 🔒 + Base32768(bytes). A received/pasted 🔒 text must be
 * disambiguated into session-ciphertext vs pairing-bundle vs pairing-header
 * BEFORE it is fed to the right consumer.
 *
 * To make that robust we wrap the pairing CBOR in an explicit 2-byte ENVELOPE
 * prefix rather than sniffing CBOR map-header bytes (which shift when optional
 * fields like `inviterUsername` / `bobDisplayName` are omitted):
 *
 * ```
 * pairing wire bytes = [0xCB, type] ++ CBOR
 *   type 0x01 = bundle (A→B)
 *   type 0x02 = header (B→A)
 * ```
 *
 * `0xCB` is chosen to sit one below the session wire MAGIC's first byte `0xCC`
 * (see `core/src/wire/header.rs` `MAGIC = [0xCC, 0xC8]`), so a pairing envelope
 * can NEVER collide with a session ciphertext on the very first byte.
 *
 * CROSS-PLATFORM CONTRACT: this 0xCB / 0x01 / 0x02 envelope is the in-band
 * pairing wire format shared with iOS. **iOS must use the identical envelope
 * when its in-band pairing is built (Phase 4).** Do not change these constants
 * without updating both platforms in lockstep.
 */
object PairingTransport {

    /** Envelope discriminator byte — distinct from session MAGIC[0] (0xCC). */
    const val PAIRING_MAGIC_0: Byte = 0xCB.toByte()

    /** Envelope type: A→B classical prekey bundle. */
    const val TYPE_BUNDLE: Byte = 0x01.toByte()

    /** Envelope type: B→A classical in-band response header. */
    const val TYPE_HEADER: Byte = 0x02.toByte()

    /**
     * Wire MAGIC — mirror of `core/src/wire/header.rs` `MAGIC = [0xCC, 0xC8]`.
     * Not exported to Kotlin, so defined locally; keep in sync with core.
     */
    private val SESSION_MAGIC_0: Byte = 0xCC.toByte()
    private val SESSION_MAGIC_1: Byte = 0xC8.toByte()

    /** What a 🔒 wire text decoded to, after inspecting its leading bytes. */
    enum class WireKind {
        /** L3 session ciphertext (DR wire, text and media alike). */
        SESSION,

        /** A→B pairing prekey bundle (0xCB 0x01 envelope). */
        PAIRING_BUNDLE,

        /** B→A pairing response header (0xCB 0x02 envelope). */
        PAIRING_HEADER,

        /** Not a 🔒 wire, or an unrecognised / too-short payload. */
        UNKNOWN,
    }

    /** Wrap an A→B bundle CBOR as a 🔒 pairing wire. */
    fun bundleToWire(cbor: ByteArray): String =
        encodeWire(byteArrayOf(PAIRING_MAGIC_0, TYPE_BUNDLE) + cbor)

    /** Wrap a B→A header CBOR as a 🔒 pairing wire. */
    fun headerToWire(cbor: ByteArray): String =
        encodeWire(byteArrayOf(PAIRING_MAGIC_0, TYPE_HEADER) + cbor)

    /**
     * Classify a received/pasted text. Non-🔒 text, undecodable bodies, and
     * unrecognised / too-short payloads all map to [WireKind.UNKNOWN] — never a
     * crash.
     */
    fun classify(wireText: String): WireKind {
        val bytes = try {
            decodeWire(wireText)
        } catch (e: Exception) {
            // Missing 🔒 prefix or malformed Base32768 body.
            return WireKind.UNKNOWN
        }
        if (bytes.size < 2) return WireKind.UNKNOWN
        return when {
            bytes[0] == SESSION_MAGIC_0 && bytes[1] == SESSION_MAGIC_1 -> WireKind.SESSION
            bytes[0] == PAIRING_MAGIC_0 && bytes[1] == TYPE_BUNDLE -> WireKind.PAIRING_BUNDLE
            bytes[0] == PAIRING_MAGIC_0 && bytes[1] == TYPE_HEADER -> WireKind.PAIRING_HEADER
            else -> WireKind.UNKNOWN
        }
    }

    /**
     * Strip the 🔒 wrapper AND the 2-byte pairing envelope, returning the raw
     * CBOR ready to feed into the engine's `decodeClassicalBundle` /
     * `decodeClassicalHeader`.
     *
     * @throws IllegalArgumentException if [wireText] is not a pairing wire
     *   (non-🔒, session ciphertext, or unrecognised envelope).
     */
    fun pairingPayload(wireText: String): ByteArray {
        val kind = classify(wireText)
        require(kind == WireKind.PAIRING_BUNDLE || kind == WireKind.PAIRING_HEADER) {
            "not a pairing wire (kind=$kind)"
        }
        // classify already proved this decodes and carries the 2-byte envelope.
        val bytes = decodeWire(wireText)
        return bytes.copyOfRange(2, bytes.size)
    }

    /**
     * Digest that identifies one invite: SHA-256 (lowercase hex) of the invite's payload bytes,
     * i.e. with the 🔒 wrapper and the 2-byte envelope stripped. Public content only.
     *
     * @throws IllegalArgumentException if [wireText] is not a pairing bundle.
     */
    fun inviteDigest(wireText: String): String {
        require(classify(wireText) == WireKind.PAIRING_BUNDLE) { "not a pairing invite" }
        return MessageDigest.getInstance("SHA-256")
            .digest(pairingPayload(wireText))
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Find the wire inside pasted text: the substring starting at the first 🔒, with surrounding
     * whitespace trimmed — WeChat copies often carry a leading "他发来的：" line or a trailing
     * newline. Text without a 🔒 is only trimmed (and will classify as [WireKind.UNKNOWN]).
     */
    fun locate(raw: String): String {
        val start = raw.indexOf(LOCK)
        return (if (start >= 0) raw.substring(start) else raw).trim()
    }

    private const val LOCK = "\uD83D\uDD12" // 🔒
}
