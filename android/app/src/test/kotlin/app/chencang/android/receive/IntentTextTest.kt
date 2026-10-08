package app.chencang.android.receive

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IntentTextTest {
    private val view = "android.intent.action.VIEW"
    private val link = "https://d3nnqcgewrb4f0.cloudfront.net/p/#AbC_-123"

    @Test fun `process text extra wins`() {
        assertThat(IntentText.from("android.intent.action.PROCESS_TEXT", "sel", "send", link)).isEqualTo("sel")
    }

    @Test fun `send extra is used when no process text`() {
        assertThat(IntentText.from("android.intent.action.SEND", null, "shared", null)).isEqualTo("shared")
    }

    @Test fun `view https data is the text`() {
        assertThat(IntentText.from(view, null, null, link)).isEqualTo(link)
    }

    @Test fun `view with another scheme carries nothing`() {
        assertThat(IntentText.from(view, null, null, "camo://pair#abc")).isNull()
        assertThat(IntentText.from(view, null, null, "http://x.example/p/#abc")).isNull()
    }

    @Test fun `data without view action carries nothing`() {
        assertThat(IntentText.from("android.intent.action.MAIN", null, null, link)).isNull()
        assertThat(IntentText.from(null, null, null, null)).isNull()
    }
}
