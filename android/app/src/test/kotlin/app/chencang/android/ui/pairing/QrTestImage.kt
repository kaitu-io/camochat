package app.chencang.android.ui.pairing

import android.graphics.Bitmap
import android.graphics.Color

/** 测试用：把 [PairingQr.matrix] 画成带 4 模块 quiet zone 的黑白位图。 */
internal fun qrBitmapForTest(text: String, modulePx: Int = 10): Bitmap {
    val m = PairingQr.matrix(text)
    val side = (m.width + 8) * modulePx
    val pixels = IntArray(side * side) { Color.WHITE }
    for (y in 0 until m.height) for (x in 0 until m.width) {
        if (!m[x, y]) continue
        for (dy in 0 until modulePx) for (dx in 0 until modulePx) {
            pixels[((y + 4) * modulePx + dy) * side + (x + 4) * modulePx + dx] = Color.BLACK
        }
    }
    return Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).apply { setPixels(pixels, 0, side, 0, 0, side, side) }
}
