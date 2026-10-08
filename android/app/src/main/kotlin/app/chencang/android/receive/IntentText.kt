package app.chencang.android.receive

/**
 * 一个外部 Intent 带来的"待处理文本"是哪一份（纯函数，便于单测）。
 * 优先级：选中文本菜单 > 系统分享 > 打开链接（仅 https，对应 App Links 的 /p/ 配对链接）。
 * 其它 scheme 的 VIEW 一律不认，返回 null。
 */
object IntentText {
    fun from(
        action: String?,
        processText: CharSequence?,
        sendText: CharSequence?,
        dataString: String?,
    ): CharSequence? =
        processText
            ?: sendText
            ?: dataString?.takeIf {
                action == ACTION_VIEW && it.startsWith("https://", ignoreCase = true)
            }

    private const val ACTION_VIEW = "android.intent.action.VIEW"
}
