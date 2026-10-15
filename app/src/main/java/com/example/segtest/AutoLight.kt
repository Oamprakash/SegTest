package com.example.segtest

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min

/**
 * Lighting correction measured on the item only (mask > 0.5), applied as per-channel gains.
 *
 * Honesty rules, because the app promises real photos of the real item:
 *  - Exposure fixes the photo, not the item: the gain comes from the whole scene's brightest areas
 *    (a white wall or sheet already near white means the photo isn't underexposed, so a black item stays
 *    black), is never more than the item's own highlights allow (no blown-out reflections), and is capped
 *    at [MAX_EXPOSURE]. Measuring the item alone lifted black jeans to charcoal grey in testing.
 *  - Colour-cast correction only runs when enough of the item is neutral (white/grey/black) to judge the
 *    light from, and each channel moves at most [MAX_WB_SHIFT]. A coloured item without neutral areas keeps
 *    its colours untouched.
 *  - Gains only (no curves, no local edits), so nothing about the item's texture or condition changes.
 */
data class LightFix(
    val exposure: Float,
    /** Gain the whole scene asked for, and the gain the item's highlights allowed; exposure is the smaller. */
    val sceneGain: Float,
    val itemGain: Float,
    val gainR: Float,
    val gainG: Float,
    val gainB: Float,
    val wbApplied: Boolean,
    /** Share of item pixels judged neutral; colour correction needs at least [AutoLight.MIN_NEUTRAL_SHARE]. */
    val neutralShare: Float,
)

object AutoLight {

    const val MAX_EXPOSURE = 1.6f
    const val TARGET_WHITE = 240f
    const val MAX_WB_SHIFT = 0.15f
    const val MIN_NEUTRAL_SHARE = 0.02f
    private const val NEUTRAL_CHROMA = 0.12f
    private const val STEP = 3 // sample every 3rd pixel each way; plenty for statistics

    /** Measures on the segmentation-size image and its mask (same dimensions). */
    fun analyze(seg: Bitmap, mask: FloatArray): LightFix {
        val w = seg.width
        val h = seg.height
        val px = IntArray(w * h)
        seg.getPixels(px, 0, w, 0, 0, w, h)

        val maxHist = IntArray(256)   // brightest channel of each item pixel
        val sceneHist = IntArray(256) // brightest channel of every pixel in the photo
        var sceneCount = 0
        val neutralLumHist = IntArray(256)
        var itemCount = 0
        var neutralCount = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val i = y * w + x
                val c0 = px[i]
                sceneHist[max((c0 shr 16) and 0xFF, max((c0 shr 8) and 0xFF, c0 and 0xFF))]++
                sceneCount++
                if (mask[i] > 0.5f) {
                    val c = px[i]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    val mx = max(r, max(g, b))
                    val mn = min(r, min(g, b))
                    maxHist[mx]++
                    itemCount++
                    // Neutral: low chroma relative to brightness, and not lost in noise or clipped.
                    if (mx in 25..250 && (mx - mn) <= NEUTRAL_CHROMA * mx) {
                        neutralLumHist[(r + g + b) / 3]++
                        neutralCount++
                    }
                }
                x += STEP
            }
            y += STEP
        }
        if (itemCount < 100) return LightFix(1f, 1f, 1f, 1f, 1f, 1f, false, 0f)

        // Colour cast from the brighter half of the neutral pixels (more signal, less noise).
        val neutralShare = neutralCount.toFloat() / itemCount
        var gR = 1f
        var gG = 1f
        var gB = 1f
        var wb = false
        if (neutralShare >= MIN_NEUTRAL_SHARE && neutralCount >= 200) {
            val lumCut = percentile(neutralLumHist, neutralCount, 0.5f)
            var sr = 0L
            var sg = 0L
            var sb = 0L
            var n = 0
            y = 0
            while (y < h) {
                var x = 0
                while (x < w) {
                    val i = y * w + x
                    if (mask[i] > 0.5f) {
                        val c = px[i]
                        val r = (c shr 16) and 0xFF
                        val g = (c shr 8) and 0xFF
                        val b = c and 0xFF
                        val mx = max(r, max(g, b))
                        val mn = min(r, min(g, b))
                        if (mx in 25..250 && (mx - mn) <= NEUTRAL_CHROMA * mx && (r + g + b) / 3 >= lumCut) {
                            sr += r; sg += g; sb += b; n++
                        }
                    }
                    x += STEP
                }
                y += STEP
            }
            if (n > 0) {
                val avg = (sr + sg + sb).toFloat() / (3 * n)
                gR = clampShift(avg / (sr.toFloat() / n))
                gG = clampShift(avg / (sg.toFloat() / n))
                gB = clampShift(avg / (sb.toFloat() / n))
                wb = true
            }
        }

        // Exposure: the scene's 99th-percentile brightest channel says how underexposed the photo is; the
        // item's says how far it can go before its own highlights clip. Use the smaller, capped.
        val wbMax = max(gR, max(gG, gB))
        val sceneP99 = percentile(sceneHist, sceneCount, 0.99f).toFloat()
        val itemP99 = percentile(maxHist, itemCount, 0.99f).toFloat() * wbMax
        val sceneGain = if (sceneP99 <= 1f) 1f else TARGET_WHITE / sceneP99
        val itemGain = if (itemP99 <= 1f) 1f else TARGET_WHITE / itemP99
        val exposure = min(sceneGain, itemGain).coerceIn(1f, MAX_EXPOSURE)

        return LightFix(exposure, sceneGain, itemGain, gR, gG, gB, wb, neutralShare)
    }

    /** Applies the gains natively; alpha is left untouched, values clip at 255. */
    fun apply(src: Bitmap, f: LightFix): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix().apply { setScale(f.gainR * f.exposure, f.gainG * f.exposure, f.gainB * f.exposure, 1f) }
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun clampShift(g: Float) = g.coerceIn(1f - MAX_WB_SHIFT, 1f + MAX_WB_SHIFT)

    private fun percentile(hist: IntArray, total: Int, q: Float): Int {
        val target = (total * q).toInt()
        var acc = 0
        for (v in hist.indices) {
            acc += hist[v]
            if (acc > target) return v
        }
        return hist.size - 1
    }
}
