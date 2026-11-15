package one.lmk.gridcraft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastDisplayHelperTest {
    @Test
    fun skipsDefaultAndInvalidDisplaysPicksFirstPresentation() {
        val defaultDisp = CastDisplayHelper.DisplayCandidate(0, true, true, 1080, 2400)
        val invalidDisp = CastDisplayHelper.DisplayCandidate(2, false, true, 1920, 1080)
        val nonPresDisp = CastDisplayHelper.DisplayCandidate(3, true, false, 1920, 1080)
        val hdmiDisp = CastDisplayHelper.DisplayCandidate(4, true, true, 1920, 1080)

        assertNull(CastDisplayHelper.selectBest(listOf(defaultDisp, invalidDisp, nonPresDisp)))
        assertEquals(hdmiDisp, CastDisplayHelper.selectBest(listOf(defaultDisp, invalidDisp, hdmiDisp)))
    }

    @Test
    fun clampsOversizedDisplayDimensionsPreservingAspectRatio() {
        assertEquals(1920 to 1080, CastDisplayHelper.clampDimensions(3840, 2160, 1920))
        assertEquals(1280 to 720, CastDisplayHelper.clampDimensions(1280, 720, 1920))
        assertEquals(1280 to 720, CastDisplayHelper.clampDimensions(0, 0, 1920))
    }
}
