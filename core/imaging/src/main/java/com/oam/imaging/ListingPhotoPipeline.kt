package com.oam.imaging

import android.graphics.Bitmap
import android.os.SystemClock
import kotlin.math.max

/** Sizes settled by testing: segment at 1024 px (same quality as 1600/2048, faster), export at 2048 px. */
object PipelineDefaults {
    const val SEGMENT_LONG_EDGE = 1024
    const val EXPORT_LONG_EDGE = 2048
}

class StepTimings(
    val segmentMs: Long,
    val cleanMs: Long,
    val compositeMs: Long,
    val lightMs: Long,
) {
    val totalMs get() = segmentMs + cleanMs + compositeMs + lightMs
}

/** Result for one photo. On anything but [MaskVerdict.OK], [cutout] is null and the UI offers retry or the brush. */
class ProcessedPhoto(
    val verdict: MaskVerdict,
    /** Full-size transparent cutout, edges cleaned and lighting corrected. Background is chosen at export. */
    val cutout: Bitmap?,
    /** The cleaned mask at segmentation size; the editor's brush edits this, then the cutout is rebuilt. */
    val mask: Mask,
    val light: LightFix,
    val compositePath: Compositor.Path?,
    val subjectBoxShares: List<Float>,
    val timings: StepTimings,
)

/**
 * One photo in, one listing-ready cutout out. Runs entirely on the device. [full] must already be normalized at
 * export size (see [ImageIo]); the caller owns threading (call it from a background dispatcher).
 */
class ListingPhotoPipeline(
    private val segmenter: Segmenter,
    private val segmentLongEdge: Int = PipelineDefaults.SEGMENT_LONG_EDGE,
) {

    suspend fun process(full: Bitmap): ProcessedPhoto {
        val small = ImageIo.downscale(full, segmentLongEdge)
        val seg = segmenter.segment(small)

        val verdict = MaskCheck.check(seg.mask)
        if (verdict != MaskVerdict.OK) {
            return ProcessedPhoto(verdict, null, seg.mask, LightFix.NONE, null, seg.subjectBoxShares,
                StepTimings(seg.latencyMs, 0, 0, 0))
        }

        val t0 = SystemClock.elapsedRealtime()
        val r = MaskOps.edgeRadius(max(small.width, small.height), max(full.width, full.height))
        val clean = MaskOps.cleanEdges(seg.mask, erodePx = r, featherPx = r)
        val t1 = SystemClock.elapsedRealtime()
        val cut = Compositor.cutout(full, clean)
        val t2 = SystemClock.elapsedRealtime()
        val light = AutoLight.analyze(small, clean)
        val lit = AutoLight.apply(cut.bitmap, light)
        cut.bitmap.recycle()
        val t3 = SystemClock.elapsedRealtime()

        return ProcessedPhoto(MaskVerdict.OK, lit, clean, light, cut.path, seg.subjectBoxShares,
            StepTimings(seg.latencyMs, t1 - t0, t2 - t1, t3 - t2))
    }
}
