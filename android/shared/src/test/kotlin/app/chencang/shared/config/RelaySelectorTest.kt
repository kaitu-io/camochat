package app.chencang.shared.config

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RelaySelectorTest {
    private val a = "https://a.test"
    private val b = "https://b.test"
    private val c = "https://c.test"

    @Test
    fun followsConfigOrderByDefault() {
        val s = RelaySelector({ listOf(a, b, c) }, FakeSharedPreferences())
        assertThat(s.ordered()).containsExactly(a, b, c).inOrder()
    }

    @Test
    fun stickyGoodRelayFirst() {
        val s = RelaySelector({ listOf(a, b, c) }, FakeSharedPreferences())
        s.markGood(c)
        assertThat(s.ordered()).containsExactly(c, a, b).inOrder()
    }

    @Test
    fun staleStickyIgnoredWhenRemovedFromConfig() {
        var list = listOf(a, b, c)
        val s = RelaySelector({ list }, FakeSharedPreferences())
        s.markGood(c)
        list = listOf(a, b)
        assertThat(s.ordered()).containsExactly(a, b).inOrder()
    }

    @Test
    fun stickySurvivesNewSelectorOnSamePrefs() {
        val prefs = FakeSharedPreferences()
        RelaySelector({ listOf(a, b) }, prefs).markGood(b)
        assertThat(RelaySelector({ listOf(a, b) }, prefs).ordered()).containsExactly(b, a).inOrder()
    }
}
