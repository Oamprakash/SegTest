package com.oam.imaging

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** The whole pipeline with a stand-in segmenter (ML Kit itself needs Play services, not available in CI). */
@RunWith(AndroidJUnit4::class)
class PipelineTest {

    private class FakeSegmenter(private val mask: (Int, Int) -> Mask) : Segmenter {
        var lastSize = 0 to 0
        override suspend fun prepare(onStatus: (String) -> Unit) {}
        override suspend fun segment(image: Bitmap): SegmentResult {
            lastSize = image.width to image.height
            return SegmentResult(mask(image.width, image.height), listOf(0.1f), 5)
        }
        override fun close() {}
    }

    @Test fun goodMaskGivesFullSizeCutout() = runTest {
        val seg = FakeSegmenter { w, h -> Fixtures.rect(w, h, w * 600 / 1024, h * 400 / 768, w * 900 / 1024, h * 700 / 768) }
        val out = ListingPhotoPipeline(seg).process(CompositorTest.photo())
        assertEquals(1024 to 768, seg.lastSize)
        assertEquals(MaskVerdict.OK, out.verdict)
        assertNotNull(out.cutout)
        val cut = out.cutout!!
        assertEquals(CompositorTest.FW, cut.width)
        assertEquals(0, android.graphics.Color.alpha(cut.getPixel(100, 100)))
        assertEquals(255, android.graphics.Color.alpha(cut.getPixel(1500, 1100)))
    }

    @Test fun stripedMaskIsStoppedBeforeCompositing() = runTest {
        val out = ListingPhotoPipeline(FakeSegmenter { w, h -> Fixtures.bands(w, h, 6) }).process(CompositorTest.photo())
        assertEquals(MaskVerdict.CORRUPTED, out.verdict)
        assertNull(out.cutout)
    }
}
