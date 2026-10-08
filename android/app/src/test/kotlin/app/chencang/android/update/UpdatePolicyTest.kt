package app.chencang.android.update

import app.chencang.shared.config.AndroidRelease
import app.chencang.shared.config.LatestApk
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UpdatePolicyTest {
    private val apk = LatestApk(10, "1.0", "00", 1, listOf("https://m/a.apk"))
    private val release = AndroidRelease(minVersionCode = 5, latest = apk)

    @Test fun belowMinIsForced() =
        assertThat(decideUpdate(4, release, null, 0)).isEqualTo(UpdateDecision.Forced(apk))

    @Test fun betweenMinAndLatestIsOptional() =
        assertThat(decideUpdate(7, release, null, 0)).isEqualTo(UpdateDecision.Optional(apk))

    @Test fun atLatestIsNone() = assertThat(decideUpdate(10, release, null, 0)).isEqualTo(UpdateDecision.None)

    @Test fun nullReleaseIsNone() = assertThat(decideUpdate(1, null, null, 0)).isEqualTo(UpdateDecision.None)

    @Test fun snoozeSuppressesSameVersionFor24h() {
        val s = Snooze(10, 1_000)
        assertThat(decideUpdate(7, release, s, 1_000 + 86_399_999)).isEqualTo(UpdateDecision.None)
        assertThat(decideUpdate(7, release, s, 1_000 + 86_400_000)).isEqualTo(UpdateDecision.Optional(apk))
    }

    @Test fun snoozeDoesNotSuppressNewerVersion() =
        assertThat(decideUpdate(7, release, Snooze(9, 1_000), 2_000)).isEqualTo(UpdateDecision.Optional(apk))

    @Test fun snoozeNeverSuppressesForced() =
        assertThat(decideUpdate(4, release, Snooze(10, 1_000), 2_000)).isEqualTo(UpdateDecision.Forced(apk))

    @Test fun manualCheckIgnoresSnooze() =
        assertThat(decideUpdate(7, release, Snooze(10, 1_000), 2_000, ignoreSnooze = true))
            .isEqualTo(UpdateDecision.Optional(apk))
}
