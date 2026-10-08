package app.chencang.android.ui.splash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashTimelineTest {
    private val eps = 1e-4f

    /** 第一帧就是系统启动页上那只静止的猫：没有气泡、爪子、字标，不缩放。 */
    private fun assertRestPose(f: SplashFrame) {
        assertEquals(0f, f.catY, eps)
        assertEquals(100f, f.clipBottom, eps)
        assertEquals(34f, f.decoyY, eps)
        assertEquals(0f, f.decoyAlpha, eps)
        assertEquals(0f, f.pawsAlpha, eps)
        assertEquals(0f, f.wordmarkAlpha, eps)
        assertEquals(0f, f.lidScaleY, eps)
        assertEquals(1f, f.overlayAlpha, eps)
        assertEquals(1f, f.contentScale, eps)
    }

    @Test
    fun `full - first frame matches the system launch screen`() {
        assertRestPose(SplashVariant.FULL.frameAt(0f))
    }

    @Test
    fun `full - at 550 the cat is ducked behind the risen decoy and the wordmark starts`() {
        val f = SplashVariant.FULL.frameAt(550f)
        assertEquals(16f, f.catY, eps)
        assertEquals(80f, f.clipBottom, eps)
        assertEquals(0f, f.decoyY, eps)
        assertEquals(1f, f.decoyAlpha, eps)
        assertEquals(0f, f.pawsAlpha, eps)
        assertEquals(0f, f.wordmarkAlpha, eps)
        assertEquals(6f, f.wordmarkY, eps)
    }

    @Test
    fun `full - at 1200 the cat peeks with paws on the edge and the h is half hidden`() {
        val f = SplashVariant.FULL.frameAt(1200f)
        assertEquals(-4f, f.catY, eps)
        assertEquals(1f, f.pawsAlpha, eps)
        assertEquals(0f, f.pawsY, eps)
        assertEquals(1f, f.wordmarkAlpha, eps)
        assertEquals(0f, f.wordmarkY, eps)
        // g = ease(0.5) = 0.875
        assertEquals(0.125f, f.hFillAlpha, eps)
        assertEquals(0.875f, f.hGhostAlpha, eps)
        assertEquals(0f, f.lidScaleY, eps)
        assertEquals(1f, f.overlayAlpha, eps)
    }

    @Test
    fun `full - 1475 is mid-blink`() {
        val f = SplashVariant.FULL.frameAt(1475f)
        assertEquals(0.9f, f.lidScaleY, eps)
        assertEquals(0f, f.hFillAlpha, eps)
        assertEquals(1f, f.hGhostAlpha, eps)
    }

    @Test
    fun `full - 2200 is fully faded and scaled up`() {
        val v = SplashVariant.FULL
        val f = v.frameAt(2200f)
        assertEquals(0f, f.overlayAlpha, eps)
        assertEquals(1.06f, f.contentScale, eps)
        assertEquals(2200f, v.duration, eps)
        assertEquals(1900f, v.skipTo, eps)
        assertEquals(1f, v.frameAt(1900f).overlayAlpha, eps)
    }

    @Test
    fun `back easing overshoots then settles`() {
        assertEquals(0f, SplashVariant.back(0f), eps)
        assertEquals(1f, SplashVariant.back(1f), eps)
        assertTrue(SplashVariant.back(0.7f) > 1f)
    }

    @Test
    fun `short - first frame matches the system launch screen`() {
        assertRestPose(SplashVariant.SHORT.frameAt(0f))
    }

    @Test
    fun `short - never shows the wordmark`() {
        for (t in 0..1000 step 25) {
            assertEquals(0f, SplashVariant.SHORT.frameAt(t.toFloat()).wordmarkAlpha, 0f)
        }
    }

    @Test
    fun `short - ducks by 380, peeks by 620 and blinks at 680`() {
        val v = SplashVariant.SHORT
        val ducked = v.frameAt(380f)
        assertEquals(16f, ducked.catY, eps)
        assertEquals(80f, ducked.clipBottom, eps)
        assertEquals(1f, ducked.decoyAlpha, eps)

        val peeked = v.frameAt(620f)
        assertEquals(-4f, peeked.catY, eps)
        assertEquals(1f, peeked.pawsAlpha, eps)
        assertEquals(0f, peeked.pawsY, eps)
        assertEquals(0f, peeked.lidScaleY, eps)

        assertEquals(0.9f, v.frameAt(680f).lidScaleY, eps)
        assertEquals(1f, v.frameAt(750f).overlayAlpha, eps)
    }

    @Test
    fun `short - done at 1000, skip jumps to 750`() {
        val v = SplashVariant.SHORT
        val f = v.frameAt(1000f)
        assertEquals(0f, f.overlayAlpha, eps)
        assertEquals(1.06f, f.contentScale, eps)
        assertEquals(1000f, v.duration, eps)
        assertEquals(750f, v.skipTo, eps)
    }
}
