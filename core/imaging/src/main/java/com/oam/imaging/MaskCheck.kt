package com.oam.imaging

/** Whether a mask can be used as-is. Pure Kotlin, so it's unit-tested on the JVM. */
enum class MaskVerdict {
    OK,
    /** Nothing found: low contrast, or the item isn't in the photo. */
    EMPTY,
    /** Everything is "item": the item fills the frame, or the model gave up. */
    FULL,
    /** Banded or speckled garbage, like the striped masks from the first test phone. */
    CORRUPTED,
}

object MaskCheck {

    const val MIN_FG = 0.01f
    const val MAX_FG = 0.97f

    /**
     * Share of neighbouring pixels (row-to-row plus column-to-column) that flip between item and background.
     * Calibrated on real masks at 1024 px: good cutouts 0.002–0.004 (white shirts, hangers, sunglasses, flat
     * lays), striped ones from the broken foreground output 0.12–0.38. 0.02 leaves a wide margin both ways;
     * very thin or fringed items are the ones to watch.
     */
    const val MAX_FLIP = 0.02f

    fun check(mask: Mask): MaskVerdict {
        val fg = mask.fgFraction()
        if (fg < MIN_FG) return MaskVerdict.EMPTY
        if (fg > MAX_FG) return MaskVerdict.FULL
        return if (flipRate(mask) > MAX_FLIP) MaskVerdict.CORRUPTED else MaskVerdict.OK
    }

    fun flipRate(mask: Mask): Float {
        val w = mask.width
        val h = mask.height
        val v = mask.values
        var rowFlips = 0L
        var colFlips = 0L
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val here = v[row + x] > 0.5f
                if (x + 1 < w && here != (v[row + x + 1] > 0.5f)) colFlips++
                if (y + 1 < h && here != (v[row + w + x] > 0.5f)) rowFlips++
            }
        }
        val rowRate = rowFlips.toFloat() / (w.toLong() * (h - 1)).coerceAtLeast(1)
        val colRate = colFlips.toFloat() / ((w - 1).toLong() * h).coerceAtLeast(1)
        return rowRate + colRate
    }
}
