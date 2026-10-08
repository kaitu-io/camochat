package app.chencang.shared.intake

/**
 * 粘贴条该不该出现。不读剪贴板内容：输入只有「有无文本」(`primaryClipDescription` 的 MIME)
 * 和剪贴板时间戳；时间戳等于上次已消费的那个就不再提示。
 */
object PasteBarGate {
    fun shouldShow(hasText: Boolean, stamp: Long?, consumed: Long?): Boolean =
        hasText && stamp != null && stamp != consumed
}
