package com.oam.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskCheckTest {

    @Test fun realGoodMasksPass() {
        for (name in listOf("good_white_shirt", "good_sunglasses", "good_hanger")) {
            val m = Fixtures.real(name)
            assertEquals("$name flip rate ${MaskCheck.flipRate(m)}", MaskVerdict.OK, MaskCheck.check(m))
        }
    }

    @Test fun realStripedMaskIsCaught() {
        val m = Fixtures.real("bad_striped")
        assertEquals("flip rate ${MaskCheck.flipRate(m)}", MaskVerdict.CORRUPTED, MaskCheck.check(m))
    }

    @Test fun syntheticShapes() {
        assertEquals(MaskVerdict.OK, MaskCheck.check(Fixtures.disc(1024, 768, 512, 384, 250)))
        assertEquals(MaskVerdict.CORRUPTED, MaskCheck.check(Fixtures.bands(1024, 768, 6)))
        assertEquals(MaskVerdict.EMPTY, MaskCheck.check(Mask(FloatArray(1024 * 768), 1024, 768)))
        assertEquals(MaskVerdict.FULL, MaskCheck.check(Mask(FloatArray(1024 * 768) { 1f }, 1024, 768)))
    }

    @Test fun goodMasksHaveWideMargin() {
        for (name in listOf("good_white_shirt", "good_sunglasses", "good_hanger")) {
            assertTrue(name, MaskCheck.flipRate(Fixtures.real(name)) < MaskCheck.MAX_FLIP / 3)
        }
        assertTrue(MaskCheck.flipRate(Fixtures.real("bad_striped")) > MaskCheck.MAX_FLIP * 3)
    }
}
