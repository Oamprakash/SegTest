package com.oam.imaging

/** Mask clean-up. Pure Kotlin, so it's unit-tested on the JVM. */
object MaskOps {

    /**
     * Edge radius for a mask segmented at [segLongEdge] and exported at [exportLongEdge]: about 2 export
     * pixels, which tested as better-or-same on every shot at 1024 px (1 px). At 2 px it thinned sunglasses.
     */
    fun edgeRadius(segLongEdge: Int, exportLongEdge: Int): Int =
        maxOf(1, Math.round(2f * segLongEdge / exportLongEdge))

    /**
     * Shrinks the mask by [erodePx] (drops background specks clinging to the outline), then softens the edge
     * over [featherPx] so the shrink doesn't leave a hard border.
     */
    fun cleanEdges(mask: Mask, erodePx: Int, featherPx: Int): Mask {
        var v = mask.values
        if (erodePx > 0) v = minFilter(v, mask.width, mask.height, erodePx)
        if (featherPx > 0) v = boxBlur(v, mask.width, mask.height, featherPx)
        return Mask(v, mask.width, mask.height)
    }

    /** Separable square min filter (erosion for soft masks); edges clamp. */
    internal fun minFilter(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var m = 1f
                val lo = if (x - r < 0) 0 else x - r
                val hi = if (x + r >= w) w - 1 else x + r
                var k = lo
                while (k <= hi) { val s = src[row + k]; if (s < m) m = s; k++ }
                tmp[row + x] = m
            }
        }
        val out = FloatArray(src.size)
        for (y in 0 until h) {
            val lo = if (y - r < 0) 0 else y - r
            val hi = if (y + r >= h) h - 1 else y + r
            for (x in 0 until w) {
                var m = 1f
                var k = lo
                while (k <= hi) { val s = tmp[k * w + x]; if (s < m) m = s; k++ }
                out[y * w + x] = m
            }
        }
        return out
    }

    /** Separable box blur with a sliding sum; edges clamp. */
    internal fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val n = (2 * r + 1).toFloat()
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
