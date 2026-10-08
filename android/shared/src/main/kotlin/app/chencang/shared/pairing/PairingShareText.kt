package app.chencang.shared.pairing

/**
 * What the "Send code" screen copies to the clipboard: the localized multi-line header, whose last
 * line is the pairing link ([PairingLink.make] as the header's `{link}`, for invite and reply
 * alike). No 🔒 wire is appended; the receiving side finds the code in the link via
 * `PairingLink.wires` / `IntakeClassifier`. The shared artifact is the card image, not this text.
 */
object PairingShareText {
    fun compose(headerLine: String): String = headerLine
}
