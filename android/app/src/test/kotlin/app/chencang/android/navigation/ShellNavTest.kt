package app.chencang.android.navigation

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 用桩内容搭只含三条路由的 NavHost，驱动真实的 ShellNav 扩展函数；整图的链路留给真机验收。 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ShellNavTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var nav: NavController

    private fun host(start: String = Routes.MAIN) {
        compose.setContent {
            val controller = rememberNavController()
            nav = controller
            NavHost(controller, startDestination = start) {
                composable("wizard-stub") { Text("wizard") }
                composable(Routes.MAIN) { Text("main") }
                composable(
                    Routes.CONVERSATION,
                    arguments = listOf(navArgument("peerUsername") { type = NavType.StringType }),
                ) { Text("conversation") }
                composable(
                    Routes.CONTACT,
                    arguments = listOf(navArgument("fingerprintHex") { type = NavType.StringType }),
                ) { Text("contact") }
            }
        }
        compose.waitForIdle()
    }

    private fun run(block: NavController.() -> Unit) {
        compose.runOnUiThread { nav.block() }
        compose.waitForIdle()
    }

    /** 回栈路由序列：`main`、`conversation:<peer>`、`contact:<fp>`。 */
    private fun stack(): List<String> = nav.currentBackStack.value.mapNotNull { e ->
        when (e.destination.route) {
            Routes.MAIN -> "main"
            Routes.CONVERSATION -> "conversation:" + e.arguments?.getString("peerUsername")
            Routes.CONTACT -> "contact:" + e.arguments?.getString("fingerprintHex")
            else -> null
        }
    }

    private fun mainTab(): String? =
        nav.getBackStackEntry(Routes.MAIN).savedStateHandle.get<String>(MAIN_TAB_KEY)

    @Test
    fun `sendMessage from contacts tab lands on chats with thread right above main`() {
        host()
        run { setMainTab(MainTab.Contacts) }
        run { navigate(Routes.contactRoute("fp1")) }
        run { sendMessageFromContact("alice") }
        assertThat(stack()).containsExactly("main", "conversation:alice").inOrder()
        assertThat(mainTab()).isEqualTo("chats")
    }

    @Test
    fun `sendMessage from the same peers thread pops back without growing the stack`() {
        host()
        run { navigate(Routes.conversationRoute("alice")) }
        run { navigate(Routes.contactRoute("fp1")) }
        assertThat(stack()).containsExactly("main", "conversation:alice", "contact:fp1").inOrder()
        run { sendMessageFromContact("alice") }
        assertThat(stack()).containsExactly("main", "conversation:alice").inOrder()
    }

    @Test
    fun `sendMessage from another peers thread replaces the stack`() {
        host()
        run { navigate(Routes.conversationRoute("alice")) }
        run { navigate(Routes.contactRoute("fp2")) }
        run { sendMessageFromContact("bob") }
        assertThat(stack()).containsExactly("main", "conversation:bob").inOrder()
        assertThat(mainTab()).isEqualTo("chats")
    }

    @Test
    fun `contact deleted returns to main and keeps the tab`() {
        host()
        run { setMainTab(MainTab.Contacts) }
        run { navigate(Routes.contactRoute("fp1")) }
        run { backToMainAfterContactDeleted() }
        assertThat(stack()).containsExactly("main")
        assertThat(mainTab()).isEqualTo("contacts")
    }

    @Test
    fun `openThreadOnChats leaves main plus thread`() {
        host()
        run { setMainTab(MainTab.Me) }
        run { navigate(Routes.contactRoute("fpx")) }
        run { openThreadOnChats("carol") }
        assertThat(stack()).containsExactly("main", "conversation:carol").inOrder()
        assertThat(mainTab()).isEqualTo("chats")
    }

    /** I2:全新安装经 App Link 拉起时起始目的地是向导,回栈里没有 `main`;配对完成点「一致」不能崩。 */
    @Test
    fun `openThreadOnChats with the wizard as start destination creates main underneath`() {
        host(start = "wizard-stub")
        assertThat(stack()).isEmpty()
        run { openThreadOnChats("alice") }
        assertThat(stack()).containsExactly("main", "conversation:alice").inOrder()
        assertThat(mainTab()).isEqualTo("chats")
        run { popBackStack() }
        assertThat(stack()).containsExactly("main")
        assertThat(mainTab()).isEqualTo("chats")
    }
}
