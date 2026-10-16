package com.oam.imaging

/** A soft mask: 0 = background, 1 = item, row-major, [width] x [height]. */
class Mask(val values: FloatArray, val width: Int, val height: Int) {
    init {
        require(values.size == width * height) { "Mask has ${values.size} values, expected ${width * height}" }
    }

    fun fgFraction(): Float {
        var n = 0
        for (v in values) if (v > 0.5f) n++
        return n.toFloat() / values.size
    }
}
