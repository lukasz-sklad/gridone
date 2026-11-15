package one.lmk.gridcraft

/**
 * Pure JVM helper computing safe display insets and IME bottom height in physical pixels.
 *
 * `bottomPx` (system navigation bar / bottom cutout) and `imeBottomPx` (on-screen keyboard)
 * are both measured upward from the physical bottom edge of the screen; the native layer
 * takes `max(bottom, imeBottom)` so overlapping gesture bars and keyboards are never
 * double-counted.
 */
object InsetsCalculator {
    data class SafeInsetsPx(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val imeBottom: Int,
    )

    fun compute(
        sysLeft: Int,
        sysTop: Int,
        sysRight: Int,
        sysBottom: Int,
        cutoutLeft: Int,
        cutoutTop: Int,
        cutoutRight: Int,
        cutoutBottom: Int,
        imeBottom: Int,
        imeVisible: Boolean,
    ): SafeInsetsPx {
        val left = maxOf(sysLeft.coerceAtLeast(0), cutoutLeft.coerceAtLeast(0))
        val top = maxOf(sysTop.coerceAtLeast(0), cutoutTop.coerceAtLeast(0))
        val right = maxOf(sysRight.coerceAtLeast(0), cutoutRight.coerceAtLeast(0))
        val bottom = maxOf(sysBottom.coerceAtLeast(0), cutoutBottom.coerceAtLeast(0))
        val keyboard = if (imeVisible) imeBottom.coerceAtLeast(0) else 0
        return SafeInsetsPx(
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            imeBottom = keyboard,
        )
    }
}
