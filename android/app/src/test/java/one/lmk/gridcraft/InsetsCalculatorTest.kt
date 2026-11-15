package one.lmk.gridcraft

import org.junit.Assert.assertEquals
import org.junit.Test

class InsetsCalculatorTest {
    @Test
    fun takesMaxOfSystemBarsAndCutoutAndZerosHiddenIme() {
        val res = InsetsCalculator.compute(
            sysLeft = 0,
            sysTop = 80,
            sysRight = 0,
            sysBottom = 64,
            cutoutLeft = 24,
            cutoutTop = 112,
            cutoutRight = 0,
            cutoutBottom = 0,
            imeBottom = 900,
            imeVisible = false,
        )
        assertEquals(24, res.left)
        assertEquals(112, res.top)
        assertEquals(0, res.right)
        assertEquals(64, res.bottom)
        assertEquals(0, res.imeBottom)
    }

    @Test
    fun preservesVisibleImeBottomWithoutClampingNegatives() {
        val res = InsetsCalculator.compute(
            sysLeft = -10,
            sysTop = 48,
            sysRight = 12,
            sysBottom = 72,
            cutoutLeft = 0,
            cutoutTop = 0,
            cutoutRight = 36,
            cutoutBottom = 10,
            imeBottom = 840,
            imeVisible = true,
        )
        assertEquals(0, res.left)
        assertEquals(48, res.top)
        assertEquals(36, res.right)
        assertEquals(72, res.bottom)
        assertEquals(840, res.imeBottom)
    }
}
