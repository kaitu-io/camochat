package app.chencang.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import app.chencang.design.Moyu
import app.chencang.design.moyuColors
import app.chencang.shared.R
import app.chencang.shared.model.Contact

/** 对话页顶部横幅的种类；[key] 写进「已关闭」记录，不能改。 */
enum class ThreadBanner(val key: String) {
    /** 我是接受方，对方还没发来任何消息：等对方完成添加（此时不能核对）。 */
    WAITING_PEER("waiting_peer"),

    /** 我是发起方，还没发过消息：先发一句话试试。 */
    SAY_HI("say_hi"),

    /** 还没核对表情图案：可以和对方一起核对。 */
    VERIFY_LATER("verify_later"),
}

object ThreadBanners {
    /** 「已关闭」记录的元素：联系人 + 横幅类型。 */
    fun dismissKey(contact: Contact, banner: ThreadBanner) = "${contact.fingerprintHex}|${banner.key}"

    /**
     * 现在该显示哪条横幅（至多一条）。优先级：等对方（接受方且对方没发过消息）> 先说句话（发起方且没发过消息）>
     * 未核对。等对方被关掉后也不往下落到「核对」——对方还没加完，现在核对不了；其余被关掉的才落到下一条。
     */
    fun pick(contact: Contact?, hasIncoming: Boolean, hasOutgoing: Boolean, dismissed: Set<String>): ThreadBanner? {
        contact ?: return null
        fun open(b: ThreadBanner) = dismissKey(contact, b) !in dismissed
        val iAccepted = contact.acceptedInviteDigest != null
        if (iAccepted && !hasIncoming) return ThreadBanner.WAITING_PEER.takeIf(::open)
        if (!iAccepted && !hasOutgoing && open(ThreadBanner.SAY_HI)) return ThreadBanner.SAY_HI
        if (!contact.verified && open(ThreadBanner.VERIFY_LATER)) return ThreadBanner.VERIFY_LATER
        return null
    }
}

/** 顶部状态横幅：一句话 + 可选动作（仅「核对」）+ 关闭。 */
@Composable
internal fun ThreadBannerBar(
    banner: ThreadBanner,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(moyuColors.accentContainer)
            .heightIn(min = Moyu.Size.TouchMin)
            .padding(start = Moyu.Space.L)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(ConversationTestTags.BANNER),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(
                when (banner) {
                    ThreadBanner.WAITING_PEER -> R.string.thread_banner_waiting_peer
                    ThreadBanner.SAY_HI -> R.string.thread_banner_say_hi
                    ThreadBanner.VERIFY_LATER -> R.string.verify_later_banner
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = moyuColors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (banner == ThreadBanner.VERIFY_LATER) {
            TextButton(onClick = onAction, modifier = Modifier.testTag(ConversationTestTags.BANNER_ACTION)) {
                Text(stringResource(R.string.verify_later_banner_action), color = moyuColors.accentPrimary)
            }
        }
        IconButton(onClick = onDismiss, modifier = Modifier.testTag(ConversationTestTags.BANNER_DISMISS)) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.paste_bar_dismiss_cd), tint = moyuColors.textSecondary)
        }
    }
}
