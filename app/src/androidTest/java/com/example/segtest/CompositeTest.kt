package com.example.segtest

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Runs the real compositing code on a real Android graphics stack (emulator in CI), on a synthetic photo whose
 * every pixel is known. Builds d5d8b1c/22c6414 (opaque cutouts) and ff47abf (photo magnified inside the mask)
 * would both fail here.
 */
@RunWith(AndroidJUnit4::class)
class CompositeTest {

    private val fw = 2048
    private val fh = 1536
    private val sw = 1024
    private val sh = 768

    /** Every pixel's colour encodes its own position, so any shift or scale shows up. */
    private fun photo(): Bitmap {
        val px = IntArray(fw * fh) { i ->
            val x = i % fw
            val y = i / fw
            Color.rgb(x * 255 / (fw - 1), y * 255 / (fh - 1), ((x / 64) + (y / 64)) % 2 * 200 + 20)
        }
        return Bitmap.createBitmap(px, fw, fh, Bitmap.Config.ARGB_8888)
    }

    /** A rectangle of foreground in the lower-right quarter, at segmentation size. */
    private fun mask() = FloatArray(sw * sh) { i ->
        val x = i % sw
        val y = i / sw
        if (x in 600..900 && y in 400..700) 1f else 0f
    }

    private fun assertComposite(out: Bitmap, full: Bitmap) {
        assertEquals(fw, out.width)
        assertEquals(fh, out.height)
        // Clearly inside the foreground (in full-size coordinates: x 1200..1800, y 800..1400).
        for ((x, y) in listOf(1300 to 900, 1500 to 1100, 1700 to 1300, 1250 to 1350)) {
            val c = out.getPixel(x, y)
            val p = full.getPixel(x, y)
            assertTrue("alpha inside at $x,$y = ${Color.alpha(c)}", Color.alpha(c) >= 250)
            for ((name, a, b) in listOf(
                Triple("r", Color.red(c), Color.red(p)),
                Triple("g", Color.green(c), Color.green(p)),
                Triple("b", Color.blue(c), Color.blue(p)),
            )) assertTrue("$name inside at $x,$y: got $a, photo $b", abs(a - b) <= 4)
        }
        // Clearly outside.
        for ((x, y) in listOf(100 to 100, 1000 to 400, 1900 to 200, 300 to 1400, 1100 to 1000)) {
            assertTrue("alpha outside at $x,$y = ${Color.alpha(out.getPixel(x, y))}", Color.alpha(out.getPixel(x, y)) <= 5)
        }
    }

    @Test
    fun cutoutKeepsPhotoInsideAndClearsOutside() {
        val full = photo()
        val (out, path) = NativeCompose.cutout(full, mask(), sw, sh)
        assertComposite(out, full)
        // Report which path the emulator used; both must be correct, the shader one should be the norm.
        println("composite path on emulator: $path")
    }

    @Test
    fun onColorAndAutoLightKeepTheCutoutShape() {
        val full = photo()
        val (cut, _) = NativeCompose.cutout(full, mask(), sw, sh)
        val fix = LightFix(1.2f, 1.2f, 1.2f, 1f, 1f, 1f, false, 0f)
        val lit = AutoLight.apply(cut, fix)
        assertTrue("brightened cutout lost transparency", Color.alpha(lit.getPixel(100, 100)) <= 5)
        assertTrue("brightened cutout lost the item", Color.alpha(lit.getPixel(1500, 1100)) >= 250)
        val onWhite = NativeCompose.onColor(lit, Color.WHITE)
        assertEquals("background should be white", Color.WHITE, onWhite.getPixel(100, 100))
        val inside = onWhite.getPixel(1500, 1100)
        val p = full.getPixel(1500, 1100)
        // Brightened 1.2x (clipped at 255), never darker than the photo, and same pixel.
        assertTrue("inside not brightened from the same pixel", Color.red(inside) >= Color.red(p) && Color.red(inside) <= minOf(255, (Color.red(p) * 1.2f).toInt() + 3))
    }
}
