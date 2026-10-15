package com.example.segtest

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max

/**
 * Quality signals computed from ML Kit's foreground confidence mask. There is no ground truth,
 * so these are proxies that point at the failure modes resellers will hit:
 *  - holeFraction   -> white-on-white dropouts (background "holes" punched inside the garment)
 *  - largestShare   -> fragmentation (hanger split off, sleeves or fringe lost as islands)
 *  - softEdgeRatio  -> fuzzy, uncertain edges (fringe, fur, knits, sheer fabric)
 *  - fgFraction     -> grabbed nothing, or swallowed the background
 */
data class MaskMetrics(
    val fgFraction: Float,
    val softEdgeRatio: Float,
    val fragments: Int,
    val largestShare: Float,
    val holeFraction: Float,
    val touchesBorder: Boolean,
) {
    val flags: List<String>
        get() = buildList {
            if (fgFraction < 0.03f) add("NO_SUBJECT")
            if (fgFraction > 0.90f) add("ATE_BACKGROUND")
            if (holeFraction > 0.02f) add("HOLES")
            if (fragments > 1 && largestShare < 0.90f) add("FRAGMENTED")
            if (softEdgeRatio > 0.20f) add("SOFT_EDGES")
            if (touchesBorder) add("TOUCHES_EDGE")
        }
}

object MaskAnalyzer {

    private const val FG = 0.5f
    private const val SOFT_LO = 0.15f
    private const val SOFT_HI = 0.85f
    private const val ANALYSIS_SIDE = 256

    fun analyze(mask: FloatArray, w: Int, h: Int): MaskMetrics {
        require(mask.size == w * h) { "Mask is ${mask.size}, expected ${w * h}" }

        var fg = 0
        var soft = 0
        for (c in mask) {
            if (c > FG) fg++
            if (c > SOFT_LO && c < SOFT_HI) soft++
        }

        // Component analysis on a downsampled binary mask keeps it fast (~65K cells max).
        val step = max(1, max(w, h) / ANALYSIS_SIDE)
        val dw = w / step
        val dh = h / step
        val bin = BooleanArray(dw * dh) { i ->
            mask[(i / dw) * step * w + (i % dw) * step] > FG
        }

        val fgComps = components(bin, dw, dh, target = true)
        val fgCells = fgComps.sumOf { it.size }
        val minArea = max(4, (dw * dh * 0.002f).toInt())
        val significant = fgComps.count { it.size >= minArea }
        val largest = fgComps.maxOfOrNull { it.size } ?: 0

        // Background regions fully enclosed by foreground = holes inside the subject.
        val holeCells = components(bin, dw, dh, target = false)
            .filter { !it.touchesBorder }
            .sumOf { it.size }

        return MaskMetrics(
            fgFraction = fg.toFloat() / mask.size,
            softEdgeRatio = soft.toFloat() / max(fg, 1),
            fragments = significant,
            largestShare = if (fgCells > 0) largest.toFloat() / fgCells else 0f,
            holeFraction = holeCells.toFloat() / max(fgCells, 1),
            touchesBorder = fgComps.any { it.touchesBorder },
        )
    }

    private class Component(val size: Int, val touchesBorder: Boolean)

    private fun components(bin: BooleanArray, w: Int, h: Int, target: Boolean): List<Component> {
        val seen = BooleanArray(bin.size)
        val stack = IntArray(bin.size)
        val out = ArrayList<Component>()
        for (start in bin.indices) {
            if (seen[start] || bin[start] != target) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var size = 0
            var border = false
            while (sp > 0) {
                val p = stack[--sp]
                size++
                val x = p % w
                val y = p / w
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) border = true
                if (x > 0) push(p - 1, bin, seen, stack, sp, target)?.let { sp = it }
                if (x < w - 1) push(p + 1, bin, seen, stack, sp, target)?.let { sp = it }
                if (y > 0) push(p - w, bin, seen, stack, sp, target)?.let { sp = it }
                if (y < h - 1) push(p + w, bin, seen, stack, sp, target)?.let { sp = it }
            }
            out += Component(size, border)
        }
        return out
    }

    private fun push(q: Int, bin: BooleanArray, seen: BooleanArray, stack: IntArray, sp: Int, target: Boolean): Int? {
        if (seen[q] || bin[q] != target) return null
        seen[q] = true
        stack[sp] = q
        return sp + 1
    }
}

/** Builds the bitmaps used for eyeballing and export. All run off the main thread. */
object Compositor {

    /** Soft-alpha composite onto a flat colour. Dark backgrounds expose white-on-white misses. */
    fun onColor(src: Bitmap, mask: FloatArray, bg: Int): Bitmap = mapPixels(src) { i, p ->
        val a = mask[i].coerceIn(0f, 1f)
        Color.rgb(
            (Color.red(p) * a + Color.red(bg) * (1 - a)).toInt(),
            (Color.green(p) * a + Color.green(bg) * (1 - a)).toInt(),
            (Color.blue(p) * a + Color.blue(bg) * (1 - a)).toInt(),
        )
    }

    /** Transparent cutout, the actual deliverable. */
    fun cutout(src: Bitmap, mask: FloatArray): Bitmap = mapPixels(src) { i, p ->
        Color.argb((mask[i].coerceIn(0f, 1f) * 255).toInt(), Color.red(p), Color.green(p), Color.blue(p))
    }

    /** Original dimmed; magenta = uncertain pixels, green tint = confident foreground. */
    fun uncertainty(src: Bitmap, mask: FloatArray): Bitmap = mapPixels(src) { i, p ->
        val c = mask[i]
        val g = (Color.red(p) * 0.3f + Color.green(p) * 0.59f + Color.blue(p) * 0.11f).toInt() / 3
        when {
            c > 0.15f && c < 0.85f -> Color.rgb(255, 0, 200)
            c >= 0.85f -> Color.rgb(g, (g + 90).coerceAtMost(255), g)
            else -> Color.rgb(g, g, g)
        }
    }

    private inline fun mapPixels(src: Bitmap, f: (Int, Int) -> Int): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) px[i] = f(i, px[i])
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    fun scaledForDisplay(b: Bitmap, maxSide: Int = 720): Bitmap {
        val s = maxSide.toFloat() / max(b.width, b.height)
        if (s >= 1f) return b
        return Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
    }
}
