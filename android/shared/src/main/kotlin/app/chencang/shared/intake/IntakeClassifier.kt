package app.chencang.shared.intake

import app.chencang.shared.chat.WireLocator
import app.chencang.shared.pairing.PairingLink
import app.chencang.shared.pairing.inband.PairingTransport
import uniffi.chencang.decodeWire

/** What a piece of incoming text (selected, shared, pasted) turned out to be. */
sealed interface IntakeKind {
    /** True for the three kinds that carry a located wire. */
    val recognized: Boolean get() = false

    /** Session ciphertext (text or media frame). */
    data class Message(val wire: String) : IntakeKind {
        override val recognized: Boolean get() = true
    }

    /** Someone's pairing code (A to B bundle). */
    data class PairingInvite(val wire: String) : IntakeKind {
        override val recognized: Boolean get() = true
    }

    /** The reply to my pairing code (B to A header). */
    data class PairingResponse(val wire: String) : IntakeKind {
        override val recognized: Boolean get() = true
    }

    /** Blank, or has a lock but no line decodes (only the header line, half a selection). */
    data object Incomplete : IntakeKind

    /** No lock at all, or decodes but the magic is neither session nor pairing. */
    data object NotOurs : IntakeKind

    /**
     * No lock, only our bare share link (`https://<host>/m/` or `/p/`): a long-press that landed on
     * the header line's link copied just the link. Only the paste bar tells this apart (a hint to
     * select all); every other entry treats it as [Incomplete].
     */
    data object LinkOnly : IntakeKind
}

/**
 * The single classifier every entry point routes through (PROCESS_TEXT, share, paste,
 * wizard input). Pure: no I/O, no logging.
 */
object IntakeClassifier {
    fun classify(
        raw: String,
        decode: (String) -> ByteArray = ::decodeWire,
        kindOf: (String) -> PairingTransport.WireKind = PairingTransport::classify,
    ): IntakeKind {
        if (raw.isBlank()) return IntakeKind.Incomplete
        val candidates = WireLocator.candidates(raw)
        if (candidates.isEmpty()) {
            return linkKind(raw, kindOf) ?: if (isBareShareLink(raw)) IntakeKind.LinkOnly else IntakeKind.NotOurs
        }
        // Last line first; a line that decodes but carries an unknown magic does not stop the scan
        // (same as iOS): an earlier line may still be ours.
        var sawUnknown = false
        for (wire in candidates.asReversed()) {
            if (runCatching { decode(wire) }.isFailure) continue
            when (kindOf(wire)) {
                PairingTransport.WireKind.SESSION -> return IntakeKind.Message(wire)
                PairingTransport.WireKind.PAIRING_BUNDLE -> return IntakeKind.PairingInvite(wire)
                PairingTransport.WireKind.PAIRING_HEADER -> return IntakeKind.PairingResponse(wire)
                PairingTransport.WireKind.UNKNOWN -> sawUnknown = true
            }
        }
        // 🔒 扫描没认出东西：再看有没有带配对码的链接（卡片二维码、只复制到链接的情况）。
        linkKind(raw, kindOf)?.let { return it }
        return if (sawUnknown) IntakeKind.NotOurs else IntakeKind.Incomplete
    }

    /** 最后一个合法配对链接对应的分类；没有则 null。 */
    private fun linkKind(raw: String, kindOf: (String) -> PairingTransport.WireKind): IntakeKind? {
        val wire = PairingLink.wires(raw).lastOrNull {
            val k = kindOf(it)
            k == PairingTransport.WireKind.PAIRING_BUNDLE || k == PairingTransport.WireKind.PAIRING_HEADER
        } ?: return null
        return if (kindOf(wire) == PairingTransport.WireKind.PAIRING_BUNDLE) {
            IntakeKind.PairingInvite(wire)
        } else {
            IntakeKind.PairingResponse(wire)
        }
    }

    /** Quotes / brackets ignored around the link: the closing set of [WireLocator.candidates] plus the opening ones. */
    private const val QUOTE_JUNK = "\"'”’」』》)）“‘「『《(（"

    /** A single `https://` URL (any host, scheme case-insensitive) whose path is exactly `/m/` or `/p/`: no query, no fragment. */
    private val BARE_SHARE_LINK = Regex("""(?i:https)://[^\s/?#]+/[mp]/""")

    /** The whole text, trimmed of whitespace and surrounding quotes / brackets, is one bare share link (same rule as iOS). */
    internal fun isBareShareLink(raw: String): Boolean =
        BARE_SHARE_LINK.matches(raw.trim { it.isWhitespace() || it in QUOTE_JUNK })
}
