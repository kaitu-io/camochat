package app.chencang.android.navigation

import androidx.navigation.NavController

/**
 * A pairing code / reply handed to the wizard from outside its own input (QR scan return, an intake
 * entry that recognized a pairing code): set on the wizard entry's savedStateHandle, consumed once.
 */
const val INCOMING_WIRE_KEY = "incoming_wire"

/** Opens the add-contact wizard on "enter their code"; a non-null [wire] is submitted right away. */
fun NavController.openWizardWithWire(wire: String?) {
    navigate(Routes.pairingWizardRoute("redeemer"))
    if (wire != null) currentBackStackEntry?.savedStateHandle?.set(INCOMING_WIRE_KEY, wire)
}

/** 当前 tab 存在 `main` 条目的 savedStateHandle 里：随进程重建保留，冷启动恒为「会话」。 */
const val MAIN_TAB_KEY = "main_tab"

enum class MainTab(val key: String) {
    Chats("chats"),
    Contacts("contacts"),
    Me("me"),
    ;

    companion object {
        fun fromKey(key: String?): MainTab = entries.firstOrNull { it.key == key } ?: Chats
    }
}

fun NavController.setMainTab(tab: MainTab) {
    getBackStackEntry(Routes.MAIN).savedStateHandle[MAIN_TAB_KEY] = tab.key
}

/** 会话 tab 落在 `main` 上，线程压在它正上方（回栈恰为 [main, 线程]）。 */
fun NavController.openThreadOnChats(peerUsername: String) {
    // 全新安装经 App Link 拉起时起始目的地是向导,回栈里没有 `main`:先把 main 置为栈底(向导随之出栈)。
    val hasMain = try { getBackStackEntry(Routes.MAIN); true } catch (_: IllegalArgumentException) { false }
    if (!hasMain) {
        navigate(Routes.MAIN) { popUpTo(0) { inclusive = true } }
    }
    setMainTab(MainTab.Chats)
    navigate(Routes.conversationRoute(peerUsername)) { popUpTo(Routes.MAIN) }
}

/** 详情页「发消息」：上一屏已是同一位对端的线程就直接回去，否则按 [openThreadOnChats] 重建回栈。 */
fun NavController.sendMessageFromContact(peerUsername: String) {
    val previous = previousBackStackEntry
    val isSameThread = previous?.destination?.route == Routes.CONVERSATION &&
        previous.arguments?.getString("peerUsername") == peerUsername
    if (isSameThread) popBackStack() else openThreadOnChats(peerUsername)
}

/** 删联系人后回到 `main`，不改 tab（从哪个 tab 进来的就回哪个）。 */
fun NavController.backToMainAfterContactDeleted() {
    popBackStack(Routes.MAIN, inclusive = false)
}
