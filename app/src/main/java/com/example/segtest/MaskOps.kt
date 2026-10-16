package com.example.segtest

import kotlin.math.max
import kotlin.math.min

/**
 * The v1 pipeline under test: segment a small image (fast), then scale the mask up to the export photo and
 * clean its edges there.
 */
object MaskOps {

    /**
     * Resize via a native ALPHA_8 bitmap scale (filtered). Much faster than [resize] at export sizes; mask
     * precision drops to 8 bits, which is invisible in a cutout.
     */
    fun resizeNative(src: FloatArray, w: Int, h: Int, nw: Int, nh: Int): FloatArray {
        if (w == nw && h == nh) return src.copyOf()
        val small = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ALPHA_8)
        val inStride = small.rowBytes
        val inBytes = ByteArray(inStride * h)
        for (y in 0 until h) for (x in 0 until w) {
            inBytes[y * inStride + x] = (src[y * w + x].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
        }
        small.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(inBytes))
        val big = android.graphics.Bitmap.createScaledBitmap(small, nw, nh, true)
        val outStride = big.rowBytes
        val outBytes = ByteArray(outStride * nh)
        big.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(outBytes))
        small.recycle()
        big.recycle()
        return FloatArray(nw * nh) { i -> (outBytes[(i / nw) * outStride + (i % nw)].toInt() and 0xFF) / 255f }
    }

    /** Bilinear resize of a row-major mask (pure Kotlin reference; slow at export sizes). */
    fun resize(src: FloatArray, w: Int, h: Int, nw: Int, nh: Int): FloatArray {
        if (w == nw && h == nh) return src.copyOf()
        val out = FloatArray(nw * nh)
        val sx = w.toFloat() / nw
        val sy = h.toFloat() / nh
        for (y in 0 until nh) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, (h - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = min(y0 + 1, h - 1)
            val ty = fy - y0
            for (x in 0 until nw) {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, (w - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = min(x0 + 1, w - 1)
                val tx = fx - x0
                val top = src[y0 * w + x0] * (1 - tx) + src[y0 * w + x1] * tx
                val bot = src[y1 * w + x0] * (1 - tx) + src[y1 * w + x1] * tx
                out[y * nw + x] = top * (1 - ty) + bot * ty
            }
        }
        return out
    }

    /**
     * Shrinks the mask by [erodePx] (drops the background specks that cling to the outline) and then softens
     * the edge over [featherPx] so the shrink doesn't leave a hard, jagged border.
     */
    fun cleanEdges(mask: FloatArray, w: Int, h: Int, erodePx: Int, featherPx: Int): FloatArray {
        var m = mask
        if (erodePx > 0) m = minFilter(m, w, h, erodePx)
        if (featherPx > 0) m = boxBlur(m, w, h, featherPx)
        return m
    }

    /** Separable square min filter (an erosion for soft masks). */
    private fun minFilter(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var v = 1f
                for (k in max(0, x - r)..min(w - 1, x + r)) v = min(v, src[row + k])
                tmp[row + x] = v
            }
        }
        val out = FloatArray(src.size)
        for (x in 0 until w) {
            for (y in 0 until h) {
                var v = 1f
                for (k in max(0, y - r)..min(h - 1, y + r)) v = min(v, tmp[k * w + x])
                out[y * w + x] = v
            }
        }
        return out
    }

    /** Separable box blur with a sliding sum; edges clamp. */
    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val n = 2 * r + 1
        val tmp = FloatArray(src.size)
        for (y in 0 until h) {
            val row = y * w
            var sum = 0f
            for (k in -r..r) sum += src[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = sum / n
                sum += src[row + (x + r + 1).coerceAtMost(w - 1)] - src[row + (x - r).coerceAtLeast(0)]
            }
        }
        val out = FloatArray(src.size)
        for (x in 0 until w) {
            var sum = 0f
            for (k in -r..r) sum += tmp[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = sum / n
                sum += tmp[(y + r + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - r).coerceAtLeast(0) * w + x]
            }
        }
        return out
    }
}

/**
 * Applies a small mask to a full-size photo with native drawing (Skia scales and blends in one pass), so no
 * per-pixel Kotlin runs at export size. This is how the real app should composite.
 */
object NativeCompose {

    /** Packs a 0..1 mask into an ALPHA_8 bitmap at the mask's own (small) size. */
    fun maskBitmap(mask: FloatArray, w: Int, h: Int): android.graphics.Bitmap {
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ALPHA_8)
        val stride = bmp.rowBytes
        val bytes = ByteArray(stride * h)
        var i = 0
        for (y in 0 until h) {
            var o = y * stride
            for (x in 0 until w) {
                var v = mask[i++]
                if (v < 0f) v = 0f else if (v > 1f) v = 1f
                bytes[o++] = (v * 255f + 0.5f).toInt().toByte()
            }
        }
        bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes))
        return bmp
    }

    /** Which compositing path produced a cutout, for the CSV. */
    enum class Path { SHADER, KOTLIN }

    /**
     * Full-size transparent cutout: the photo, with the small mask scaled up (filtered) as its alpha.
     *
     * An ALPHA_8 bitmap is drawn as COVERAGE, not as source alpha, so the earlier DST_IN draw left the photo
     * untouched (build 22c6414 exported fully opaque "cutouts"). Here the mask is drawn with the photo as the
     * paint's shader onto a transparent canvas: coverage then becomes the photo's alpha.
     *
     * The mask is scaled to full size FIRST and drawn unscaled. Build ff47abf drew the small mask into a larger
     * rect, and Skia applied that same scale to the shader, so the photo was magnified 2x inside a correct
     * outline (the shirt's shape filled with bedspread). The result is checked for both alpha AND colour on
     * pixels clearly in or out of the mask; if anything is off, an explicit Kotlin path takes over.
     */
    fun cutout(full: android.graphics.Bitmap, mask: FloatArray, sw: Int, sh: Int): Pair<android.graphics.Bitmap, Path> {
        val fw = full.width
        val fh = full.height
        val small = maskBitmap(mask, sw, sh)
        val maskA8 = android.graphics.Bitmap.createScaledBitmap(small, fw, fh, true)
        small.recycle()
        val out = android.graphics.Bitmap.createBitmap(fw, fh, android.graphics.Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint().apply {
            shader = android.graphics.BitmapShader(full, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        }
        // Same size, no scaling: the shader and the mask share one coordinate space.
        android.graphics.Canvas(out).drawBitmap(maskA8, 0f, 0f, paint)
        maskA8.recycle()
        if (followsMask(out, full, mask, sw, sh)) return out to Path.SHADER
        out.recycle()
        return kotlinCutout(full, mask, sw, sh) to Path.KOTLIN
    }

    /**
     * True when the output is transparent where the mask is clearly out, and opaque AND the same colour as the
     * photo where it is clearly in.
     */
    private fun followsMask(out: android.graphics.Bitmap, full: android.graphics.Bitmap, mask: FloatArray, sw: Int, sh: Int): Boolean {
        var inChecked = 0
        var outChecked = 0
        val step = kotlin.math.max(1, kotlin.math.min(sw, sh) / 24)
        var y = 2
        while (y < sh - 2 && (inChecked < 5 || outChecked < 5)) {
            var x = 2
            while (x < sw - 2) {
                val v = mask[y * sw + x]
                val clearlyIn = v > 0.98f && neighbours(mask, sw, x, y) { it > 0.98f }
                val clearlyOut = v < 0.02f && neighbours(mask, sw, x, y) { it < 0.02f }
                if ((clearlyIn && inChecked < 5) || (clearlyOut && outChecked < 5)) {
                    val fx = ((x + 0.5f) * out.width / sw).toInt().coerceIn(0, out.width - 1)
                    val fy = ((y + 0.5f) * out.height / sh).toInt().coerceIn(0, out.height - 1)
                    val c = out.getPixel(fx, fy)
                    val a = c ushr 24
                    if (clearlyIn) {
                        if (a < 230) return false
                        val p = full.getPixel(fx, fy)
                        for (sh8 in intArrayOf(16, 8, 0)) {
                            if (kotlin.math.abs(((c shr sh8) and 0xFF) - ((p shr sh8) and 0xFF)) > 6) return false
                        }
                        inChecked++
                    }
                    else { if (a > 25) return false; outChecked++ }
                }
                x += step
            }
            y += step
        }
        return inChecked > 0 && outChecked > 0
    }

    private inline fun neighbours(m: FloatArray, w: Int, x: Int, y: Int, ok: (Float) -> Boolean): Boolean {
        for (dy in -2..2) for (dx in -2..2) if (!ok(m[(y + dy) * w + x + dx])) return false
        return true
    }

    /** Fallback: scale the mask natively (proven to work in build dbc5477), then copy it into the alpha channel. */
    private fun kotlinCutout(full: android.graphics.Bitmap, mask: FloatArray, sw: Int, sh: Int): android.graphics.Bitmap {
        val fw = full.width
        val fh = full.height
        val small = maskBitmap(mask, sw, sh)
        val big = android.graphics.Bitmap.createScaledBitmap(small, fw, fh, true)
        small.recycle()
        val stride = big.rowBytes
        val a = ByteArray(stride * fh)
        big.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(a))
        big.recycle()
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
        return android.graphics.Bitmap.createBitmap(px, fw, fh, android.graphics.Bitmap.Config.ARGB_8888)
    }

    /** The cutout placed on a flat colour. */
    fun onColor(cutout: android.graphics.Bitmap, color: Int): android.graphics.Bitmap {
        val out = android.graphics.Bitmap.createBitmap(cutout.width, cutout.height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(color)
        canvas.drawBitmap(cutout, 0f, 0f, null)
        return out
    }
}
