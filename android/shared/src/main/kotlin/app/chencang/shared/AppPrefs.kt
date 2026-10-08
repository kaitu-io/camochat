package app.chencang.shared

import android.content.Context
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Small app-wide switches and the local-only profile. Mirrors iOS `cc.summaryPrivacy.v1` / `cc.myName.v1`. */
class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("chencang_app_prefs", Context.MODE_PRIVATE)
    private val _summaryPrivacy = MutableStateFlow(prefs.getBoolean(KEY_SUMMARY_PRIVACY, false))

    /** When true the conversation list shows「已加密」instead of the last message body. */
    val summaryPrivacy: StateFlow<Boolean> = _summaryPrivacy

    fun setSummaryPrivacy(on: Boolean) {
        prefs.edit().putBoolean(KEY_SUMMARY_PRIVACY, on).apply()
        _summaryPrivacy.value = on
    }

    /** 封缄卡上次选的动作：决定「分享到微信」「复制」谁是主按钮（对齐 iOS `cc.sealAction.v1`）。 */
    enum class SealAction { SHARE, COPY }

    private val _sealAction = MutableStateFlow(
        if (prefs.getString(KEY_SEAL_ACTION, null) == SealAction.COPY.name) SealAction.COPY else SealAction.SHARE,
    )
    val sealAction: StateFlow<SealAction> = _sealAction

    fun setSealAction(action: SealAction) {
        if (_sealAction.value == action) return
        prefs.edit().putString(KEY_SEAL_ACTION, action.name).apply()
        _sealAction.value = action
    }

    // ── 我的资料（昵称 + 印章头像；只存本机，不进握手、不进日志、不进备份）──────────
    //
    // 三个键的初值**不在构造函数里读**（构造发生在主线程）：由 [loadProfile] 在后台读出后
    // 一次性发布，再置 [profileLoaded]。界面在它为 true 之前不显示「未设置昵称」这类占位，
    // 免得闪一下（spec 2026-10-01 three-tab-shell §7.3 第 6 条）。

    private val _profileLoaded = MutableStateFlow(false)
    val profileLoaded: StateFlow<Boolean> = _profileLoaded

    private val _myName = MutableStateFlow("")

    /** 我的昵称；空串 = 未设置。调用方先过 `normalizeMyName` 再存。 */
    val myName: StateFlow<String> = _myName

    private val _myAvatarGlyph = MutableStateFlow("")

    /** 头像上的字；空串 = 跟随昵称首字。 */
    val myAvatarGlyph: StateFlow<String> = _myAvatarGlyph

    private val _myAvatarColor = MutableStateFlow<Int?>(null)

    /** 头像底色的色板下标；null = 自动（按本机指纹取色）。 */
    val myAvatarColor: StateFlow<Int?> = _myAvatarColor

    /** 读出三个键并发布。读盘：在后台调度器上调用。 */
    suspend fun loadProfile() {
        _myName.value = prefs.getString(KEY_MY_NAME, null).orEmpty()
        _myAvatarGlyph.value = prefs.getString(KEY_MY_AVATAR_GLYPH, null).orEmpty()
        _myAvatarColor.value = if (prefs.contains(KEY_MY_AVATAR_COLOR)) prefs.getInt(KEY_MY_AVATAR_COLOR, 0) else null
        _profileLoaded.value = true
    }

    fun setMyName(name: String) {
        putOrRemove(KEY_MY_NAME, name)
        _myName.value = name
    }

    fun setMyAvatarGlyph(glyph: String) {
        putOrRemove(KEY_MY_AVATAR_GLYPH, glyph)
        _myAvatarGlyph.value = glyph
    }

    fun setMyAvatarColor(index: Int?) {
        prefs.edit().apply { if (index == null) remove(KEY_MY_AVATAR_COLOR) else putInt(KEY_MY_AVATAR_COLOR, index) }.apply()
        _myAvatarColor.value = index
    }

    /**
     * 删账号用：移除三个键并复位三个流。同步落盘（与删账号里清 `devicePrefs` 同一做法）——
     * 紧接着进程级单例就被重置，不能留给异步写；写不下去就抛，由 [AccountWiper] 记为失败步骤。
     */
    fun clearMyProfile() {
        @Suppress("ApplySharedPref")
        val written = prefs.edit()
            .remove(KEY_MY_NAME).remove(KEY_MY_AVATAR_GLYPH).remove(KEY_MY_AVATAR_COLOR).remove(KEY_NAME_PROMPT_DONE)
            .commit()
        if (!written) throw IOException("clearMyProfile: commit failed")
        _myName.value = ""
        _myAvatarGlyph.value = ""
        _myAvatarColor.value = null
    }

    /**
     * 粘贴条已消费的剪贴板时间戳（`ClipDescription.timestamp`）；null = 从未消费。
     * 不走 StateFlow：`AppClipboard` 会在任意处新建 AppPrefs 实例写它，读写都直达 SharedPreferences。
     */
    val pasteBarConsumedStamp: Long?
        get() = if (prefs.contains(KEY_PASTE_BAR_CONSUMED)) prefs.getLong(KEY_PASTE_BAR_CONSUMED, 0L) else null

    fun setPasteBarConsumedStamp(stamp: Long) {
        prefs.edit().putLong(KEY_PASTE_BAR_CONSUMED, stamp).apply()
    }

    /**
     * 「你的名字」问过了（继续或跳过都算）：引导或首次配对问过一次就不再问。
     * 同 [pasteBarConsumedStamp]，直读 SharedPreferences，不走 StateFlow。
     */
    val namePromptDone: Boolean
        get() = prefs.getBoolean(KEY_NAME_PROMPT_DONE, false)

    fun setNamePromptDone(done: Boolean) {
        prefs.edit().putBoolean(KEY_NAME_PROMPT_DONE, done).apply()
    }

    /** 「你的名字」答完：[name] 非空 = 继续并存名字，null = 跳过；两种都记为问过。 */
    fun saveAnsweredName(name: String?) {
        if (name != null) setMyName(name)
        setNamePromptDone(true)
    }

    private val _threadBannerDismissed = MutableStateFlow(prefs.getStringSet(KEY_THREAD_BANNER_DISMISSED, emptySet()).orEmpty().toSet())

    /** 对话页顶部横幅已被关掉的记录，元素为「联系人|横幅类型」；每条横幅按联系人 + 类型各关各的。 */
    val threadBannerDismissed: StateFlow<Set<String>> = _threadBannerDismissed

    fun dismissThreadBanner(entry: String) {
        val next = _threadBannerDismissed.value + entry
        prefs.edit().putStringSet(KEY_THREAD_BANNER_DISMISSED, next).apply()
        _threadBannerDismissed.value = next
    }

    /** 空串不落键：缺省与空串同义（= 未设置）。 */
    private fun putOrRemove(key: String, value: String) {
        prefs.edit().apply { if (value.isEmpty()) remove(key) else putString(key, value) }.apply()
    }

    private companion object {
        const val KEY_SUMMARY_PRIVACY = "summary_privacy"
        const val KEY_SEAL_ACTION = "seal_action"
        const val KEY_PASTE_BAR_CONSUMED = "paste_bar_consumed_stamp"
        const val KEY_THREAD_BANNER_DISMISSED = "thread_banner_dismissed"
        const val KEY_NAME_PROMPT_DONE = "name_prompt_done"
        const val KEY_MY_NAME = "my_name"
        const val KEY_MY_AVATAR_GLYPH = "my_avatar_glyph"
        const val KEY_MY_AVATAR_COLOR = "my_avatar_color"
    }
}
