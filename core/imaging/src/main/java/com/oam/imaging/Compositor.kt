package com.oam.imaging

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Turns a small mask plus the full photo into a full-size transparent cutout.
 *
 * Two traps found on device, both now covered by CompositeTest on an emulator:
 *  - An ALPHA_8 bitmap is drawn as COVERAGE, so a DST_IN draw leaves the photo unchanged (opaque "cutouts").
 *  - Drawing a small ALPHA_8 into a bigger rect scales the paint's shader too (photo magnified inside the mask).
 * So: scale the mask to full size first, then draw it unscaled with the photo as the shader. Each result is
 * checked on pixels clearly in and out of the mask; if a device draws differently, a Kotlin path takes over.
 */
object Compositor {

    enum class Path { SHADER, KOTLIN }

    class Cutout(val bitmap: Bitmap, val path: Path)

    fun cutout(full: Bitmap, mask: Mask): Cutout {
        val fw = full.width
        val fh = full.height
        val small = alpha8(mask)
        val big = Bitmap.createScaledBitmap(small, fw, fh, true)
        small.recycle()
        val out = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { shader = BitmapShader(full, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        Canvas(out).drawBitmap(big, 0f, 0f, paint)
        if (followsMask(out, full, mask)) {
            big.recycle()
            return Cutout(out, Path.SHADER)
        }
        out.recycle()
        return Cutout(kotlinCutout(full, big), Path.KOTLIN).also { big.recycle() }
    }

    /** The cutout on a flat colour (white, light grey…). */
    fun onColor(cutout: Bitmap, color: Int): Bitmap {
        val out = Bitmap.createBitmap(cutout.width, cutout.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(color)
        canvas.drawBitmap(cutout, 0f, 0f, null)
        return out
    }

    internal fun alpha8(mask: Mask): Bitmap {
        val bmp = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ALPHA_8)
        val stride = bmp.rowBytes
        val bytes = ByteArray(stride * mask.height)
        var i = 0
        for (y in 0 until mask.height) {
            var o = y * stride
            for (x in 0 until mask.width) {
                var v = mask.values[i++]
                if (v < 0f) v = 0f else if (v > 1f) v = 1f
                bytes[o++] = (v * 255f + 0.5f).toInt().toByte()
            }
        }
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        return bmp
    }

    private fun kotlinCutout(full: Bitmap, bigA8: Bitmap): Bitmap {
        val fw = full.width
        val fh = full.height
        val stride = bigA8.rowBytes
        val a = ByteArray(stride * fh)
        bigA8.copyPixelsToBuffer(ByteBuffer.wrap(a))
        val px = IntArray(fw * fh)
        full.getPixels(px, 0, fw, 0, 0, fw, fh)
        var i = 0
        for (y in 0 until fh) {
            var o = y * stride
            for (x in 0 until fw) {
                px[i] = (px[i] and 0x00FFFFFF) or ((a[o++].toInt() and 0xFF) shl 24)
                i++
            }
        }
        return Bitmap.createBitmap(px, fw, fh, Bitmap.Config.ARGB_8888)
    }

    /** Transparent where the mask is clearly out; opaque and the photo's own colour where it is clearly in. */
    private fun followsMask(out: Bitmap, full: Bitmap, mask: Mask): Boolean {
        val sw = mask.width
        val sh = mask.height
        val m = mask.values
        var inChecked = 0
        var outChecked = 0
        val step = maxOf(1, minOf(sw, sh) / 24)
        var y = 2
        while (y < sh - 2 && (inChecked < 5 || outChecked < 5)) {
            var x = 2
            while (x < sw - 2) {
                val v = m[y * sw + x]
                val clearlyIn = v > 0.98f && around(m, sw, x, y) { it > 0.98f }
                val clearlyOut = v < 0.02f && around(m, sw, x, y) { it < 0.02f }
                if ((clearlyIn && inChecked < 5) || (clearlyOut && outChecked < 5)) {
                    val fx = ((x + 0.5f) * out.width / sw).toInt().coerceIn(0, out.width - 1)
                    val fy = ((y + 0.5f) * out.height / sh).toInt().coerceIn(0, out.height - 1)
                    val c = out.getPixel(fx, fy)
                    if (clearlyIn) {
                        if ((c ushr 24) < 230) return false
                        val p = full.getPixel(fx, fy)
                        for (s in intArrayOf(16, 8, 0)) if (abs(((c shr s) and 0xFF) - ((p shr s) and 0xFF)) > 6) return false
                        inChecked++
                    } else {
                        if ((c ushr 24) > 25) return false
                        outChecked++
                    }
                }
                x += step
            }
            y += step
        }
        return inChecked > 0 && outChecked > 0
    }

    private inline fun around(m: FloatArray, w: Int, x: Int, y: Int, ok: (Float) -> Boolean): Boolean {
        for (dy in -2..2) for (dx in -2..2) if (!ok(m[(y + dy) * w + x + dx])) return false
        return true
    }
}
