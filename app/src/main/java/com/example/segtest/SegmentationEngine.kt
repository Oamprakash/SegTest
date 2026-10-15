package com.example.segtest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

data class SegOutput(
    val input: Bitmap,
    /** Full-image confidence mask (0..1), row-major, input width x height. */
    val mask: FloatArray,
    val latencyMs: Long,
    val subjectCount: Int,
    /** Each subject's bounding-box area as a share of the image: a small separate box is often the hanger. */
    val subjectBoxShares: List<Float>,
)

class SegmentationEngine(private val context: Context) {

    /*
     * Same configuration as Google's vision-quickstart sample, which works on the test phone: per-subject
     * confidence masks only. The whole-image foreground mask/bitmap options returned corrupted, striped output
     * on that device (even when ML Kit decoded the file itself), so they are deliberately not enabled.
     */
    private val segmenter = SubjectSegmentation.getClient(
        SubjectSegmenterOptions.Builder()
            .enableMultipleSubjects(
                SubjectSegmenterOptions.SubjectResultOptions.Builder()
                    .enableConfidenceMask()
                    .build()
            )
            .build()
    )

    /** The model is unbundled (delivered by Play services). Make sure it is on the device before timing anything. */
    suspend fun ensureModel(onStatus: (String) -> Unit) {
        val client = ModuleInstall.getClient(context)
        if (client.areModulesAvailable(segmenter).awaitResult().areModulesAvailable()) return
        onStatus("Downloading segmentation model…")
        client.installModules(ModuleInstallRequest.newBuilder().addApi(segmenter).build()).awaitResult()
        repeat(120) {
            if (client.areModulesAvailable(segmenter).awaitResult().areModulesAvailable()) return
            delay(1_000)
        }
        error("Model still not available after 2 minutes. Check Play services and network.")
    }

    /** First inference per session is slow (model init). Returns that cold latency so it can be reported separately. */
    suspend fun warmUp(): Long {
        val b = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        val t = SystemClock.elapsedRealtime()
        segmenter.process(InputImage.fromBitmap(b, 0)).awaitResult()
        return SystemClock.elapsedRealtime() - t
    }

    suspend fun segment(bitmap: Bitmap): SegOutput {
        val w = bitmap.width
        val h = bitmap.height
        val t = SystemClock.elapsedRealtime()
        val result = segmenter.process(InputImage.fromBitmap(bitmap, 0)).awaitResult()
        val latency = SystemClock.elapsedRealtime() - t

        // Union of subject masks, placed at each subject's box (same indexing as the Google sample).
        val mask = FloatArray(w * h)
        for (s in result.subjects) {
            val m = s.confidenceMask ?: continue
            m.rewind()
            for (j in 0 until s.height) {
                val y = s.startY + j
                for (i in 0 until s.width) {
                    val v = m.get() // always consume, even if the pixel falls outside the image
                    val x = s.startX + i
                    if (x in 0 until w && y in 0 until h) {
                        val idx = y * w + x
                        if (v > mask[idx]) mask[idx] = v
                    }
                }
            }
        }

        val area = (w * h).toFloat()
        val shares = result.subjects.map { (it.width * it.height) / area }
        return SegOutput(bitmap, mask, latency, result.subjects.size, shares)
    }

    fun close() = segmenter.close()

    companion object {
        /**
         * Decodes with EXIF rotation applied, downscaled so the long edge <= [longEdge], into a software bitmap.
         * Pass the result through [normalize] before ML Kit.
         */
        @RequiresApi(Build.VERSION_CODES.P)
        fun decode(context: Context, uri: Uri, longEdge: Int): Bitmap {
            val src = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                val s = longEdge.toFloat() / max(info.size.width, info.size.height)
                if (s < 1f) {
                    decoder.setTargetSize(
                        (info.size.width * s).roundToInt(),
                        (info.size.height * s).roundToInt(),
                    )
                }
            }
        }

        /** Redraws into a plain sRGB ARGB_8888 bitmap with tightly packed rows (guards against wide-gamut/HDR decodes). */
        fun normalize(src: Bitmap): Bitmap {
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(src, 0f, 0f, null)
            return out
        }

        /** One-line description of a decoded bitmap, logged to the CSV so odd formats show up. */
        fun describe(b: Bitmap): String =
            "${b.config} ${b.width}x${b.height} rowBytes=${b.rowBytes}/${b.width * 4} " +
                "cs=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) b.colorSpace?.name else "?"}"
    }
}
