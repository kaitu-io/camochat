package app.chencang.android.ui.main

class FakeProbe : PasteBarProbe {
    var text = false
    var stamp: Long? = null
    var consumed: Long? = null
    var pendingMarks = 0

    override fun consumePendingFocusMark() {
        if (pendingMarks > 0) { pendingMarks--; consumed = stamp }
    }
    override fun clearPendingFocusMark() { pendingMarks = 0 }
    override fun hasText() = text
    override fun stamp() = stamp
    override fun consumedStamp() = consumed
    override fun markConsumed() { stamp?.let { consumed = it } }
}
