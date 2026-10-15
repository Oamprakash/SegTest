package com.example.segtest

import kotlin.math.max
import kotlin.math.min

/**
 * The v1 pipeline under test: segment a small image (fast), then scale the mask up to the export photo and
 * clean its edges there.
 */
object MaskOps {

    /** Bilinear resize of a row-major mask. */
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
