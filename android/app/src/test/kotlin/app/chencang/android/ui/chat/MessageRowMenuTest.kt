package app.chencang.android.ui.chat

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import app.chencang.design.MoyuTheme
import app.chencang.shared.R
import app.chencang.shared.chat.ChatMessage
import app.chencang.shared.moderation.ContentFilter
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MessageRowMenuTest {
    @get:Rule val compose = createComposeRule()

    private fun str(id: Int) = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)

    private fun msg(direction: String, body: String) =
        ChatMessage("m1", "alice", direction, body, 0L, kind = ChatMessage.KIND_TEXT)

    private val filter = ContentFilter(listOf("badword"))

    private fun show(message: ChatMessage, onReport: (ThreadRow) -> Unit = {}) = compose.setContent {
        MoyuTheme {
            MessageRow(
                row = ThreadRow("m1", message, null),
                progress = null, busy = false, playing = false, awaitingWindowExpired = false,
                canForward = false, onTapMedia = {}, onRetry = {}, onCopy = {}, onCopyOriginal = {},
                onShare = {}, onReopen = {}, albumCount = 1, canForwardAll = false,
                onForward = { _, _ -> }, onDelete = {}, contentFilter = filter, onReport = onReport,
            )
        }
    }

    private fun tag(message: ChatMessage) =
        (if (message.direction == ChatMessage.DIRECTION_OUT) ConversationTestTags.BUBBLE_OUT_PREFIX else ConversationTestTags.BUBBLE_IN_PREFIX) + "m1"

    private fun longPress(message: ChatMessage) =
        compose.onNodeWithTag(tag(message)).performTouchInput { longClick() }

    @Test
    fun `incoming message menu has report`() {
        val m = msg(ChatMessage.DIRECTION_IN, "hello")
        var reported = 0
        show(m) { reported++ }
        longPress(m)
        compose.onNodeWithText(str(R.string.thread_report)).performClick()
        org.junit.Assert.assertEquals(1, reported)
    }

    @Test
    fun `own message menu has no report`() {
        val m = msg(ChatMessage.DIRECTION_OUT, "hello")
        show(m)
        longPress(m)
        compose.onNodeWithText(str(R.string.thread_copy_original)).assertExists()
        compose.onNodeWithText(str(R.string.thread_report)).assertDoesNotExist()
    }

    @Test
    fun `flagged incoming text is covered until tapped`() {
        val m = msg(ChatMessage.DIRECTION_IN, "a badword b")
        show(m)
        compose.onNodeWithText(str(R.string.filtered_cover)).assertExists()
        compose.onNodeWithText("a badword b").assertDoesNotExist()
        compose.onNodeWithText(str(R.string.filtered_cover)).performClick()
        compose.onNodeWithText("a badword b").assertExists()
    }

    @Test
    fun `own flagged text is not covered`() {
        show(msg(ChatMessage.DIRECTION_OUT, "a badword b"))
        compose.onNodeWithText("a badword b").assertExists()
        compose.onNodeWithText(str(R.string.filtered_cover)).assertDoesNotExist()
    }
}
