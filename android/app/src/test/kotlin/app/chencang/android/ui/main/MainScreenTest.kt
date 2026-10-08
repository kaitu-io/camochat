package app.chencang.android.ui.main

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import app.chencang.android.navigation.MainTab
import app.chencang.android.ui.chat.ConversationListTestTags
import app.chencang.design.MoyuTheme
import app.chencang.shared.R
import android.content.Context
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MainScreenTest {
    @get:Rule val compose = createComposeRule()

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun str(id: Int) = ctx.getString(id)
    private val calls = mutableListOf<String>()

    private val probe = FakeProbe()
    private val pasteBar = PasteBarState(probe)

    private fun show(initial: MainTab = MainTab.Chats, badge: Int = 0, chats: @androidx.compose.runtime.Composable (PaddingValues) -> Unit = { Text("chats-slot", Modifier.padding(it)) }) {
        compose.setContent {
            MoyuTheme {
                var tab by remember { mutableStateOf(initial) }
                MainScreen(
                    tab = tab,
                    onTabSelected = { tab = it },
                    contactsBadge = badge,
                    pasteBar = pasteBar,
                    onPasteBarPaste = { calls += "bar-paste" },
                    onAddContact = { calls += "add" },
                    chats = chats,
                    contacts = { Text("contacts-slot", Modifier.padding(it)) },
                    me = { Text("me-slot", Modifier.padding(it)) },
                )
            }
        }
    }

    @Test
    fun `three tabs switch content and title`() {
        show()
        val contacts = str(R.string.contacts_tab)
        val me = str(R.string.me_tab)
        compose.onNodeWithText("chats-slot").assertIsDisplayed()
        // 会话 tab 的顶栏标题是产品名；底栏标签是「会话」。
        compose.onNodeWithText(str(R.string.app_name)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.conversations_tab), useUnmergedTree = true).assertIsDisplayed()
        // 底栏文字「联系人」「我」各一处；顶栏标题随 tab 换成同名文字后各变两处。
        compose.onAllNodesWithText(contacts, useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithText(me, useUnmergedTree = true).assertCountEquals(1)

        compose.onNodeWithTag(MainTestTags.TAB_CONTACTS).performClick()
        compose.onNodeWithText("contacts-slot").assertIsDisplayed()
        compose.onAllNodesWithText(contacts, useUnmergedTree = true).assertCountEquals(2)
        compose.onAllNodesWithText(str(R.string.app_name)).assertCountEquals(0)

        compose.onNodeWithTag(MainTestTags.TAB_ME).performClick()
        compose.onNodeWithText("me-slot").assertIsDisplayed()
        compose.onAllNodesWithText(me, useUnmergedTree = true).assertCountEquals(2)

        compose.onNodeWithTag(MainTestTags.TAB_CHATS).performClick()
        compose.onNodeWithText("chats-slot").assertIsDisplayed()
    }

    @Test
    fun `plus button only on chats`() {
        show()
        compose.onNodeWithContentDescription(str(R.string.conversations_add_cd)).assertIsDisplayed()
        compose.onNodeWithTag(MainTestTags.TAB_CONTACTS).performClick()
        compose.onNodeWithTag(ConversationListTestTags.ADD).assertDoesNotExist()
        compose.onNodeWithTag(MainTestTags.TAB_ME).performClick()
        compose.onNodeWithTag(ConversationListTestTags.ADD).assertDoesNotExist()
    }

    @Test
    fun `plus fires onAddContact directly without a menu`() {
        show()
        compose.onNodeWithTag(ConversationListTestTags.ADD).performClick()
        assertEquals(listOf("add"), calls)
        compose.onNodeWithText(str(R.string.pairing_add_contact)).assertDoesNotExist()
    }

    @Test
    fun `badge content description is localized`() {
        show(badge = 3)
        compose.onNodeWithContentDescription(
            ctx.resources.getQuantityString(R.plurals.contacts_badge_cd, 3, 3),
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun `badge shows the count`() {
        show(badge = 2)
        compose.onNodeWithTag(MainTestTags.CONTACTS_BADGE, useUnmergedTree = true).assertTextEquals("2")
    }

    @Test
    fun `badge is absent at zero count`() {
        show(badge = 0)
        compose.onNodeWithTag(MainTestTags.CONTACTS_BADGE, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `tab content keeps scroll position across switches`() {
        show(
            chats = { pad ->
                val state = rememberLazyListState()
                LazyColumn(state = state, contentPadding = pad, modifier = Modifier.testTag("long-list")) {
                    items((0 until 50).toList()) { Text("row-$it") }
                }
            },
        )
        compose.onNodeWithTag("long-list").performScrollToIndex(30)
        compose.onNodeWithText("row-30").assertIsDisplayed()

        compose.onNodeWithTag(MainTestTags.TAB_CONTACTS).performClick()
        compose.onNodeWithTag(MainTestTags.TAB_CHATS).performClick()
        compose.onNodeWithText("row-30").assertIsDisplayed()
    }

    @Test
    fun `tabbar tag exists`() {
        show()
        compose.onNodeWithTag(MainTestTags.TABBAR).assertIsDisplayed()
    }

    @Test
    fun `paste bar shows on chats and contacts but not on me`() {
        probe.text = true; probe.stamp = 5L
        show()
        compose.runOnIdle { pasteBar.onFocusGained() }
        compose.onNodeWithTag(MainTestTags.PASTE_BAR).assertIsDisplayed()
        compose.onNodeWithTag(MainTestTags.TAB_CONTACTS).performClick()
        compose.onNodeWithTag(MainTestTags.PASTE_BAR).assertIsDisplayed()
        compose.onNodeWithTag(MainTestTags.TAB_ME).performClick()
        compose.onNodeWithTag(MainTestTags.PASTE_BAR).assertDoesNotExist()
    }

    @Test
    fun `paste bar paste marks consumed, fires callback and hides`() {
        probe.text = true; probe.stamp = 5L
        show()
        compose.runOnIdle { pasteBar.onFocusGained() }
        compose.onNodeWithTag(MainTestTags.PASTE_BAR_PASTE).performClick()
        assertEquals(listOf("bar-paste"), calls)
        assertEquals(5L, probe.consumed)
        compose.onNodeWithTag(MainTestTags.PASTE_BAR).assertDoesNotExist()
    }

    @Test
    fun `paste bar dismiss marks consumed without callback`() {
        probe.text = true; probe.stamp = 5L
        show()
        compose.runOnIdle { pasteBar.onFocusGained() }
        compose.onNodeWithTag(MainTestTags.PASTE_BAR_DISMISS).performClick()
        assertEquals(emptyList<String>(), calls)
        assertEquals(5L, probe.consumed)
        compose.onNodeWithTag(MainTestTags.PASTE_BAR).assertDoesNotExist()
    }
}
