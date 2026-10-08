package app.chencang.shared.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HoldToTalkTest {
    private fun machine() = HoldToTalk(cancelThresholdPx = 100f)

    @Test
    fun `release before one second is too short`() {
        val m = machine()
        m.press(0)
        assertThat(m.release(999)).isEqualTo(HoldToTalk.Outcome.TooShort)
        assertThat(m.phase.value).isEqualTo(HoldToTalk.Phase.Idle)
    }

    @Test
    fun `release at one second or more sends with the elapsed duration`() {
        val m = machine()
        m.press(1_000)
        assertThat(m.release(2_000)).isEqualTo(HoldToTalk.Outcome.Send(1_000))
    }

    @Test
    fun `sliding up past the threshold enters the cancel zone and releasing cancels`() {
        val m = machine()
        m.press(0)
        m.move(-150f, 3_000)
        assertThat((m.phase.value as HoldToTalk.Phase.Recording).inCancelZone).isTrue()
        assertThat(m.release(3_500)).isEqualTo(HoldToTalk.Outcome.Cancelled)
    }

    @Test
    fun `sliding back down leaves the cancel zone`() {
        val m = machine()
        m.press(0)
        m.move(-150f, 2_000)
        m.move(-20f, 2_100)
        assertThat((m.phase.value as HoldToTalk.Phase.Recording).inCancelZone).isFalse()
        assertThat(m.release(2_200)).isEqualTo(HoldToTalk.Outcome.Send(2_200))
    }

    @Test
    fun `countdown shows from 50 s`() {
        val m = machine()
        m.press(0)
        assertThat(m.tick(49_999)).isNull()
        assertThat((m.phase.value as HoldToTalk.Phase.Recording).countdownSec).isNull()
        m.tick(50_000)
        assertThat((m.phase.value as HoldToTalk.Phase.Recording).countdownSec).isEqualTo(10)
        m.tick(59_001)
        assertThat((m.phase.value as HoldToTalk.Phase.Recording).countdownSec).isEqualTo(1)
    }

    @Test
    fun `60 s auto-sends once and the later finger-up is ignored`() {
        val m = machine()
        m.press(0)
        assertThat(m.tick(60_000)).isEqualTo(HoldToTalk.Outcome.Send(60_000))
        assertThat(m.phase.value).isEqualTo(HoldToTalk.Phase.Idle)
        assertThat(m.release(61_000)).isNull()
    }

    @Test
    fun `events while idle do nothing`() {
        val m = machine()
        m.move(-500f, 10)
        assertThat(m.tick(10)).isNull()
        assertThat(m.release(10)).isNull()
        assertThat(m.phase.value).isEqualTo(HoldToTalk.Phase.Idle)
    }
}
