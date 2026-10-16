package com.oam.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.delay

/**
 * ML Kit Subject Segmentation, per-subject confidence masks ONLY.
 *
 * The whole-image foreground mask and foreground bitmap came back as horizontal stripes on the first test
 * phone, even when ML Kit decoded the file itself, while per-subject masks (what Google's own sample uses)
 * were clean. Do not enable the foreground options.
 */
class MlKitSegmenter(private val context: Context) : Segmenter {

    private val client = SubjectSegmentation.getClient(
        SubjectSegmenterOptions.Builder()
            .enableMultipleSubjects(
                SubjectSegmenterOptions.SubjectResultOptions.Builder().enableConfidenceMask().build()
            )
            .build()
    )
    private var warmedUp = false

    override suspend fun prepare(onStatus: (String) -> Unit) {
        val installer = ModuleInstall.getClient(context)
        if (!installer.areModulesAvailable(client).awaitResult().areModulesAvailable()) {
            onStatus("Downloading the cutout model…")
            installer.installModules(ModuleInstallRequest.newBuilder().addApi(client).build()).awaitResult()
            var ready = false
            for (i in 0 until 120) {
                if (installer.areModulesAvailable(client).awaitResult().areModulesAvailable()) { ready = true; break }
                delay(1_000)
            }
            check(ready) { "Cutout model still not available after 2 minutes" }
        }
        if (!warmedUp) {
            // The first inference of a session is slow; pay it here, not on the user's first photo.
            val b = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
            client.process(InputImage.fromBitmap(b, 0)).awaitResult()
            warmedUp = true
        }
    }

    override suspend fun segment(image: Bitmap): SegmentResult {
        val w = image.width
        val h = image.height
        val t = SystemClock.elapsedRealtime()
        val result = client.process(InputImage.fromBitmap(image, 0)).awaitResult()
        val latency = SystemClock.elapsedRealtime() - t

        // Union of subject masks, each placed at its own box (same indexing as Google's sample).
        val mask = FloatArray(w * h)
        for (s in result.subjects) {
            val m = s.confidenceMask ?: continue
            m.rewind()
            for (j in 0 until s.height) {
                val y = s.startY + j
                for (i in 0 until s.width) {
                    val v = m.get() // always consume, even outside the image
                    val x = s.startX + i
                    if (x in 0 until w && y in 0 until h) {
                        val idx = y * w + x
                        if (v > mask[idx]) mask[idx] = v
                    }
                }
            }
        }
        val area = (w * h).toFloat()
        return SegmentResult(Mask(mask, w, h), result.subjects.map { (it.width * it.height) / area }, latency)
    }

    override fun close() = client.close()
}
