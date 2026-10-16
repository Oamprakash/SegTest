package com.oam.imaging

import android.graphics.Bitmap

/** What a segmenter found in one image. */
class SegmentResult(
    /** Union of all subjects, at the input image's size. */
    val mask: Mask,
    /** One entry per detected subject: its bounding box as a share of the image (a small separate one is often a hanger). */
    val subjectBoxShares: List<Float>,
    val latencyMs: Long,
)

/**
 * Finds the item in a photo. Kept as an interface because ML Kit Subject Segmentation is in beta, with no SLA
 * or deprecation policy; a bundled model can replace it without touching the rest of the pipeline.
 */
interface Segmenter : AutoCloseable {
    /** Makes sure the model is on the device. May download it. */
    suspend fun prepare(onStatus: (String) -> Unit = {})

    /** Input must be a plain sRGB ARGB_8888 bitmap (see [ImageIo.normalize]). */
    suspend fun segment(image: Bitmap): SegmentResult
}
