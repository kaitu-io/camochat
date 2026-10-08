package app.chencang.shared.intake

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasteBarGateTest {
    @Test fun `no text never shows`() = assertFalse(PasteBarGate.shouldShow(false, 5L, null))

    @Test fun `first install with text shows`() = assertTrue(PasteBarGate.shouldShow(true, 5L, null))

    @Test fun `same stamp as consumed hides`() = assertFalse(PasteBarGate.shouldShow(true, 5L, 5L))

    @Test fun `unknown stamp hides`() = assertFalse(PasteBarGate.shouldShow(true, null, null))

    @Test fun `newer stamp shows`() = assertTrue(PasteBarGate.shouldShow(true, 6L, 5L))
}
