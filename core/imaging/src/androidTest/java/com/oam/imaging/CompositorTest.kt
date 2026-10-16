package com.oam.imaging

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Runs compositing on a real Android graphics stack. Every pixel of the test photo encodes its own position,
 * so an opaque result (the DST_IN bug) or a shifted/magnified photo (the shader-scale bug) both fail.
 */
@RunWith(AndroidJUnit4::class)
class CompositorTest {

    companion object {
        const val FW = 2048
        const val FH = 1536

        fun photo(): Bitmap = Bitmap.createBitmap(IntArray(FW * FH) { i ->
            val x = i % FW
            val y = i / FW
            Color.rgb(x * 255 / (FW - 1), y * 255 / (FH - 1), ((x / 64) + (y / 64)) % 2 * 200 + 20)
        }, FW, FH, Bitmap.Config.ARGB_8888)

        /** Item in the lower-right quarter at 1024x768: x 1200..1800, y 800..1400 at full size. */
        fun itemMask() = Fixtures.rect(1024, 768, 600, 400, 900, 700)

        fun assertCutout(out: Bitmap, full: Bitmap) {
            assertEquals(FW, out.width)
            assertEquals(FH, out.height)
            for ((x, y) in listOf(1300 to 900, 1500 to 1100, 1700 to 1300, 1250 to 1350)) {
                val c = out.getPixel(x, y)
                val p = full.getPixel(x, y)
                assertTrue("alpha inside at $x,$y = ${Color.alpha(c)}", Color.alpha(c) >= 250)
                assertTrue("red at $x,$y", abs(Color.red(c) - Color.red(p)) <= 4)
                assertTrue("green at $x,$y", abs(Color.green(c) - Color.green(p)) <= 4)
                assertTrue("blue at $x,$y", abs(Color.blue(c) - Color.blue(p)) <= 4)
            }
            for ((x, y) in listOf(100 to 100, 1000 to 400, 1900 to 200, 300 to 1400, 1100 to 1000)) {
                assertTrue("alpha outside at $x,$y", Color.alpha(out.getPixel(x, y)) <= 5)
            }
        }
    }

    @Test fun cutoutKeepsPhotoInsideAndClearsOutside() {
        val full = photo()
        val cut = Compositor.cutout(full, itemMask())
        assertCutout(cut.bitmap, full)
    }

    @Test fun lightAndBackgroundKeepTheShape() {
        val full = photo()
        val cut = Compositor.cutout(full, itemMask()).bitmap
        val lit = AutoLight.apply(cut, LightFix.NONE.copy(exposure = 1.2f))
        val onWhite = Compositor.onColor(lit, Color.WHITE)
        assertEquals(Color.WHITE, onWhite.getPixel(100, 100))
        val inside = onWhite.getPixel(1500, 1100)
        val p = full.getPixel(1500, 1100)
        assertTrue(Color.red(inside) >= Color.red(p) && Color.red(inside) <= minOf(255, (Color.red(p) * 1.2f).toInt() + 3))
    }
}
