package com.oam.imaging

import org.junit.Assert.assertEquals
import org.junit.Test

class MaskOpsTest {

    @Test fun edgeRadiusMatchesTestedSettings() {
        assertEquals(1, MaskOps.edgeRadius(1024, 2048))
        assertEquals(2, MaskOps.edgeRadius(1600, 2048))
        assertEquals(2, MaskOps.edgeRadius(2048, 2048))
    }

    @Test fun cleanShrinksByRadiusAndKeepsInterior() {
        val m = Fixtures.rect(100, 80, 20, 20, 79, 59)
        val c = MaskOps.cleanEdges(m, erodePx = 1, featherPx = 1)
        assertEquals(1f, c.values[40 * 100 + 50], 1e-6f) // deep inside: untouched
        assertEquals(0f, c.values[5 * 100 + 5], 1e-6f)   // far outside: untouched
        assertEquals(0f, c.values[40 * 100 + 19], 1e-6f) // just outside: still clear
        // The old edge column (x = 20) was eroded away, then softened: partly transparent.
        val edge = c.values[40 * 100 + 20]
        assert(edge > 0f && edge < 1f) { "edge value $edge" }
    }

    @Test fun cleanRemovesOnePixelSpecks() {
        val v = FloatArray(50 * 50)
        v[25 * 50 + 25] = 1f
        val c = MaskOps.cleanEdges(Mask(v, 50, 50), erodePx = 1, featherPx = 1)
        assertEquals(0f, c.values.max(), 1e-6f)
    }
}
