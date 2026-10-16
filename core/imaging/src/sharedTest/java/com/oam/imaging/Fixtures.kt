package com.oam.imaging

import java.io.DataInputStream
import java.util.zip.GZIPInputStream

/** Synthetic and real masks for tests. */
object Fixtures {

    fun rect(w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int) =
        Mask(FloatArray(w * h) { i -> if (i % w in x0..x1 && i / w in y0..y1) 1f else 0f }, w, h)

    fun disc(w: Int, h: Int, cx: Int, cy: Int, r: Int) =
        Mask(FloatArray(w * h) { i -> val dx = i % w - cx; val dy = i / w - cy; if (dx * dx + dy * dy <= r * r) 1f else 0f }, w, h)

    /** Horizontal bands of item/background: the shape of the corrupted masks from the first test phone. */
    fun bands(w: Int, h: Int, bandPx: Int) =
        Mask(FloatArray(w * h) { i -> if ((i / w / bandPx) % 2 == 0 && i % w in w / 5..w * 4 / 5) 1f else 0f }, w, h)

    /** Real masks from device test sessions, 1-bit packed: good cutouts and a striped one. */
    fun real(name: String): Mask {
        val stream = Fixtures::class.java.getResourceAsStream("/masks/$name.bin.gz") ?: error("missing fixture $name")
        DataInputStream(GZIPInputStream(stream)).use { d ->
            val w = d.readInt()
            val h = d.readInt()
            val bytes = d.readBytes()
            return Mask(FloatArray(w * h) { i -> if ((bytes[i / 8].toInt() shr (7 - i % 8)) and 1 == 1) 1f else 0f }, w, h)
        }
    }
}
