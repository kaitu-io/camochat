package app.chencang.android.update

import android.content.SharedPreferences
import app.chencang.shared.config.AndroidRelease
import app.chencang.shared.config.AppConfig
import app.chencang.shared.config.LatestApk
import app.chencang.shared.config.RefreshResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.Proxy
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DirectUpdateControllerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val apk = LatestApk(10, "1.0", "00", 1, listOf("https://m.test/a.apk"))

    private fun cfg(min: Int, latest: Int = 10) =
        AppConfig(1, 1, emptyList(), emptyList(), "https://s.test", AndroidRelease(min, apk.copy(versionCode = latest)))

    private fun controller(
        config: AppConfig,
        current: Int,
        result: RefreshResult,
        clock: () -> Long = { 1_000L },
        downloader: ApkDownloader = ApkDownloader(File(tmp.root, "u")),
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher()),
        configFlow: MutableStateFlow<AppConfig> = MutableStateFlow(config),
        refresh: suspend (Boolean) -> RefreshResult = { result },
    ): DirectUpdateController {
        val prefs = UpdatePrefs(fakePrefs())
        return DirectUpdateController(
            current, configFlow, refresh, prefs,
            downloader, clock, scope,
        )
    }

    private fun readyDownloader(file: File) = object : ApkDownloader(File(tmp.root, "u")) {
        override fun download(apk: LatestApk, onProgress: (Long, Long) -> Unit, isCancelled: () -> Boolean): File = file
    }

    private fun readyOptional(): Pair<DirectUpdateController, File> {
        val file = File(tmp.root, "ready.apk").also { it.writeBytes(byteArrayOf(1)) }
        val c = controller(cfg(min = 1), current = 5, result = RefreshResult.UNCHANGED, downloader = readyDownloader(file))
        c.onForeground()
        c.startDownload()
        assertThat(c.state.value).isInstanceOf(UpdateUiState.ReadyToInstall::class.java)
        return c to file
    }

    @Test fun optionalReadyCanBeDismissed() {
        val (c, file) = readyOptional()
        c.dismissOptional()
        assertThat(c.state.value).isEqualTo(UpdateUiState.Idle)
        assertThat(file.isFile).isTrue()
        // The dismissed ready state must not auto-install when it comes back.
        assertThat(c.consumeInstallRequest()).isFalse()
    }

    @Test fun optionalReadyLaterSnoozes() {
        val (c, file) = readyOptional()
        c.snooze()
        assertThat(c.state.value).isEqualTo(UpdateUiState.Idle)
        assertThat(file.isFile).isTrue()
        c.onForeground()
        assertThat(c.state.value).isEqualTo(UpdateUiState.Idle)
    }

    @Test fun installRequestIsConsumedOnce() {
        val (c, _) = readyOptional()
        assertThat(c.consumeInstallRequest()).isTrue()
        assertThat(c.consumeInstallRequest()).isFalse()
    }

    @Test fun forcedAppliedBeforeRefreshCompletes() {
        val gate = CompletableDeferred<RefreshResult>()
        val c = controller(cfg(min = 8), current = 5, result = RefreshResult.UNCHANGED, refresh = { gate.await() })
        c.onForeground()
        assertThat((c.state.value as UpdateUiState.Prompt).decision).isInstanceOf(UpdateDecision.Forced::class.java)
        gate.complete(RefreshResult.UNCHANGED)
        assertThat((c.state.value as UpdateUiState.Prompt).decision).isInstanceOf(UpdateDecision.Forced::class.java)
    }

    @Test fun forcedRetryRefreshesConfigFirst() {
        val forces = mutableListOf<Boolean>()
        val requested = mutableListOf<Int>()
        val file = File(tmp.root, "ok.apk").also { it.writeBytes(byteArrayOf(1)) }
        val dl = object : ApkDownloader(File(tmp.root, "u")) {
            override fun download(apk: LatestApk, onProgress: (Long, Long) -> Unit, isCancelled: () -> Boolean): File {
                requested += apk.versionCode
                if (requested.size == 1) throw ApkDownloadException(DownloadFailure.CHECKSUM)
                return file
            }
        }
        val flow = MutableStateFlow(cfg(min = 8))
        val c = controller(
            cfg(min = 8), current = 5, result = RefreshResult.UNCHANGED, downloader = dl, configFlow = flow,
            refresh = { force ->
                forces += force
                if (force) flow.value = cfg(min = 8, latest = 11)
                RefreshResult.UPDATED
            },
        )
        c.onForeground()
        c.startDownload()
        assertThat(c.state.value).isInstanceOf(UpdateUiState.Failed::class.java)
        forces.clear()
        c.startDownload()
        assertThat(forces).containsExactly(true)
        assertThat(requested).containsExactly(10, 11).inOrder()
        assertThat(c.state.value).isInstanceOf(UpdateUiState.ReadyToInstall::class.java)
    }

    @Test fun forcedFromCachedConfigEvenWhenRefreshFails() {
        val c = controller(cfg(min = 8), current = 5, result = RefreshResult.FAILED)
        c.onForeground()
        assertThat(c.state.value).isInstanceOf(UpdateUiState.Prompt::class.java)
        assertThat((c.state.value as UpdateUiState.Prompt).decision).isInstanceOf(UpdateDecision.Forced::class.java)
    }

    @Test fun optionalPromptThenSnoozeReturnsIdle() {
        var t = 1_000L
        val c = controller(cfg(min = 1), current = 5, result = RefreshResult.UNCHANGED, clock = { t })
        c.onForeground()
        assertThat((c.state.value as UpdateUiState.Prompt).decision).isInstanceOf(UpdateDecision.Optional::class.java)
        c.snooze()
        assertThat(c.state.value).isEqualTo(UpdateUiState.Idle)
        c.onForeground()
        assertThat(c.state.value).isEqualTo(UpdateUiState.Idle)
    }

    private fun failedOptional(): DirectUpdateController {
        val boom = object : ApkDownloader(File(tmp.root, "u")) {
            override fun download(apk: LatestApk, onProgress: (Long, Long) -> Unit, isCancelled: () -> Boolean): File =
                throw ApkDownloadException(DownloadFailure.NETWORK)
        }
        val c = controller(cfg(min = 1), current = 5, result = RefreshResult.UNCHANGED, downloader = boom)
        c.onForeground()
        c.startDownload()
        assertThat(c.state.value).isInstanceOf(UpdateUiState.Failed::class.java)
        return c
    }

    @Test fun dismissOptionalFromFailedReturnsIdle() {
        val c = failedOptional()
        c.dismissOptional()
        assertThat(c.state.value).isEqualTo(UpdateUiState.Idle)
    }

    @Test fun dismissOptionalFromPromptReturnsIdle() {
        val c = controller(cfg(min = 1), current = 5, result = RefreshResult.UNCHANGED)
        c.onForeground()
        c.dismissOptional()
        assertThat(c.state.value).isEqualTo(UpdateUiState.Idle)
    }

    @Test fun snoozeFromFailedOptionalSuppressesNextForeground() {
        val c = failedOptional()
        c.snooze()
        assertThat(c.state.value).isEqualTo(UpdateUiState.Idle)
        c.onForeground()
        assertThat(c.state.value).isEqualTo(UpdateUiState.Idle)
    }

    @Test fun checkNowReportsUpToDate() = runTest {
        val c = controller(cfg(min = 1, latest = 5), current = 5, result = RefreshResult.UNCHANGED)
        assertThat(c.checkNow()).isEqualTo(ManualCheckResult.UP_TO_DATE)
    }

    @Test fun checkNowReportsFailedWhenAllSourcesDown() = runTest {
        val c = controller(cfg(min = 1, latest = 5), current = 5, result = RefreshResult.FAILED)
        assertThat(c.checkNow()).isEqualTo(ManualCheckResult.FAILED)
    }

    @Test fun unexpectedExceptionBecomesFailedNotCrash() = runTest {
        val boom = object : ApkDownloader(File(tmp.root, "u")) {
            override fun download(apk: LatestApk, onProgress: (Long, Long) -> Unit, isCancelled: () -> Boolean): File =
                throw IllegalStateException("boom")
        }
        val c = controller(cfg(min = 8), current = 5, result = RefreshResult.UNCHANGED, downloader = boom)
        c.checkNow()
        c.startDownload()
        assertThat(c.state.value).isInstanceOf(UpdateUiState.Failed::class.java)
        assertThat((c.state.value as UpdateUiState.Failed).reason).isEqualTo(DownloadFailure.NETWORK)
    }

    @Test fun cancelThenRestartDoesNotReviveOldJob() = runTest {
        val release = CountDownLatch(1)
        val oldDone = CountDownLatch(1)
        val oldStarted = CountDownLatch(1)
        val calls = AtomicInteger()
        val file = File(tmp.root, "ok.apk").also { it.writeBytes(byteArrayOf(1)) }
        val fake = object : ApkDownloader(File(tmp.root, "u")) {
            override fun download(apk: LatestApk, onProgress: (Long, Long) -> Unit, isCancelled: () -> Boolean): File {
                if (calls.incrementAndGet() == 1) {
                    try {
                        oldStarted.countDown()
                        release.await()
                        throw ApkDownloadException(DownloadFailure.NETWORK)
                    } finally {
                        oldDone.countDown()
                    }
                }
                return file
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val c = controller(cfg(min = 8), current = 5, result = RefreshResult.UNCHANGED, downloader = fake, scope = scope)
        c.checkNow()
        c.startDownload()
        oldStarted.await()
        c.cancelDownload()
        c.startDownload()
        val deadline = System.currentTimeMillis() + 5_000
        while (c.state.value !is UpdateUiState.ReadyToInstall && System.currentTimeMillis() < deadline) Thread.sleep(10)
        release.countDown()
        oldDone.await()
        Thread.sleep(200)
        assertThat(c.state.value).isInstanceOf(UpdateUiState.ReadyToInstall::class.java)
    }

    /** In-memory SharedPreferences (Robolectric's Application init needs AndroidKeyStore, absent on the JVM). */
    private fun fakePrefs(): SharedPreferences {
        val map = HashMap<String, Any>()
        val editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { p, m, a ->
            when (m.name) {
                "putInt", "putLong" -> { map[a[0] as String] = a[1]; p }
                "apply", "commit" -> true
                else -> p
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, m, a ->
            when (m.name) {
                "contains" -> map.containsKey(a[0] as String)
                "getInt", "getLong" -> map[a[0] as String] ?: a[1]
                "edit" -> editor
                else -> null
            }
        } as SharedPreferences
    }
}
