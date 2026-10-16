package com.oam.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.net.Uri
import kotlin.math.max
import kotlin.math.roundToInt

object ImageIo {

    /** Decodes with EXIF rotation applied, long edge at most [longEdge], as a software bitmap. */
    fun decode(context: Context, uri: Uri, longEdge: Int): Bitmap {
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val s = longEdge.toFloat() / max(info.size.width, info.size.height)
            if (s < 1f) decoder.setTargetSize((info.size.width * s).roundToInt(), (info.size.height * s).roundToInt())
        }
    }

    /**
     * Redraws into a plain sRGB ARGB_8888 bitmap with packed rows. Phones can decode camera photos as Display P3,
     * 10-bit or HDR; getPixels() hides that, ML Kit doesn't.
     */
    fun normalize(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, null)
        return out
    }

    fun downscale(src: Bitmap, longEdge: Int): Bitmap {
        val s = longEdge.toFloat() / max(src.width, src.height)
        if (s >= 1f) return src
        return Bitmap.createScaledBitmap(src, (src.width * s).roundToInt(), (src.height * s).roundToInt(), true)
    }
}
