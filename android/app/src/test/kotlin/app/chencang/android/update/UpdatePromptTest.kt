package app.chencang.android.update

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import app.chencang.design.MoyuTheme
import app.chencang.shared.config.LatestApk
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UpdatePromptTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val apk = LatestApk(
        versionCode = 9, versionName = "9.9.9", sha256 = "00", size = 200, mirrors = emptyList(),
        notes = mapOf("en" to "English notes"),
    )

    private fun show(
        state: UpdateUiState,
        onLater: () -> Unit = {},
        onCancel: () -> Unit = {},
        onInstall: () -> Unit = {},
        onUpdate: () -> Unit = {},
    ) = compose.setContent {
        MoyuTheme {
            UpdatePrompt(
                state = state,
                notes = { pickNotes(it.notes, "en") },
                onUpdate = onUpdate,
                onLater = onLater,
                onCancel = onCancel,
                onInstall = onInstall,
            )
        }
    }

    @Test
    fun forcedBlocksBack() {
        show(UpdateUiState.Prompt(UpdateDecision.Forced(apk)))
        compose.onNodeWithTag(UpdateTestTags.FORCED).assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag(UpdateTestTags.FORCED).assertIsDisplayed()
        assertThat(compose.activity.isFinishing).isFalse()
    }

    @Test
    fun optionalLaterInvokesCallback() {
        var later = 0
        show(UpdateUiState.Prompt(UpdateDecision.Optional(apk)), onLater = { later++ })
        compose.onNodeWithTag(UpdateTestTags.OPTIONAL).assertIsDisplayed()
        compose.onNodeWithText("Later").performClick()
        assertThat(later).isEqualTo(1)
    }

    @Test
    fun progressShowsPercent() {
        show(UpdateUiState.Downloading(UpdateDecision.Optional(apk), done = 50, total = 200))
        compose.onNodeWithTag(UpdateTestTags.PROGRESS).assertIsDisplayed()
        compose.onNode(hasText("25%", substring = true)).assertIsDisplayed()
    }

    @Test
    fun readyToInstallDoesNotAutoInstallFromPrompt() {
        // Auto-install is a one-shot owned by UpdateHost (controller token), never a recomposition side effect.
        var installs = 0
        show(UpdateUiState.ReadyToInstall(UpdateDecision.Forced(apk), File("x.apk")), onInstall = { installs++ })
        compose.waitForIdle()
        assertThat(installs).isEqualTo(0)
    }

    @Test
    fun optionalReadyInstallButtonInstalls() {
        var installs = 0
        var later = 0
        show(
            UpdateUiState.ReadyToInstall(UpdateDecision.Optional(apk), File("x.apk")),
            onInstall = { installs++ }, onLater = { later++ },
        )
        compose.onNodeWithText("Update").performClick()
        assertThat(installs).isEqualTo(1)
        compose.onNodeWithText("Later").performClick()
        assertThat(later).isEqualTo(1)
    }

    @Test
    fun failedShowsReasonAndRetry() {
        var updates = 0
        show(UpdateUiState.Failed(UpdateDecision.Forced(apk), DownloadFailure.CHECKSUM), onUpdate = { updates++ })
        compose.onNode(hasText("verification", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertThat(updates).isEqualTo(1)
    }

    @Test
    fun notesFallbackToOtherLanguage() {
        assertThat(pickNotes(mapOf("en" to "E"), "zh-CN")).isEqualTo("E")
        assertThat(pickNotes(mapOf("zh" to "Z", "en" to "E"), "zh-CN")).isEqualTo("Z")
        assertThat(pickNotes(mapOf("zh" to "Z", "en" to "E"), "fr")).isEqualTo("E")
        assertThat(pickNotes(mapOf("zh" to "Z"), "en")).isEqualTo("Z")
        assertThat(pickNotes(emptyMap(), "en")).isNull()
    }

    @Test
    fun forcedReadyShowsInstallButton() {
        var installs = 0
        show(UpdateUiState.ReadyToInstall(UpdateDecision.Forced(apk), File("x.apk")), onInstall = { installs++ })
        compose.onNodeWithText("Update").performClick()
        assertThat(installs).isEqualTo(1)
    }

    @Test
    fun forcedScreenBlocksTouchesToContentBelow() {
        var below = 0
        compose.setContent {
            MoyuTheme {
                androidx.compose.foundation.layout.Box {
                    androidx.compose.material3.Text(
                        "below",
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { below++ }
                            .testTag("below"),
                    )
                    UpdatePrompt(
                        state = UpdateUiState.Prompt(UpdateDecision.Forced(apk)),
                        notes = { null }, onUpdate = {}, onLater = {}, onCancel = {}, onInstall = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("below").performTouchInput { click(center) }
        assertThat(below).isEqualTo(0)
    }
}
