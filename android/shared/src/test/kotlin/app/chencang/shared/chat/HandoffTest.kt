package app.chencang.shared.chat

import app.chencang.shared.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HandoffTest {
    @Test fun `sealed and unknown statuses are not sent`() {
        assertThat(Handoff.of("sealed")).isEqualTo(Handoff.NOT_SENT)
        assertThat(Handoff.of("weird")).isEqualTo(Handoff.NOT_SENT)
    }

    @Test fun `copied maps to COPIED and legacy sent maps to SHARED`() {
        assertThat(Handoff.of("copied")).isEqualTo(Handoff.COPIED)
        assertThat(Handoff.of("sent")).isEqualTo(Handoff.SHARED)
    }

    @Test fun `status text resources`() {
        assertThat(Handoff.NOT_SENT.statusRes).isEqualTo(R.string.status_encrypted_not_sent)
        assertThat(Handoff.COPIED.statusRes).isEqualTo(R.string.status_copied)
        assertThat(Handoff.SHARED.statusRes).isEqualTo(R.string.status_shared)
    }
}
