package one.lmk.gridcraft

/** Pure JVM selection and frame sizing logic for external Android Presentation displays. */
object CastDisplayHelper {
    const val DEFAULT_DISPLAY_ID: Int = 0
    const val DEFAULT_MAX_EDGE_PX: Int = 1920

    data class DisplayCandidate(
        val displayId: Int,
        val isValid: Boolean,
        val isPresentation: Boolean,
        val widthPx: Int,
        val heightPx: Int,
    )

    fun isEligible(candidate: DisplayCandidate): Boolean {
        if (!candidate.isValid) return false
        if (candidate.displayId == DEFAULT_DISPLAY_ID) return false
        return candidate.isPresentation && candidate.widthPx > 0 && candidate.heightPx > 0
    }

    fun selectBest(candidates: List<DisplayCandidate>): DisplayCandidate? =
        candidates.firstOrNull { isEligible(it) }

    fun clampDimensions(
        widthPx: Int,
        heightPx: Int,
        maxEdgePx: Int = DEFAULT_MAX_EDGE_PX,
    ): Pair<Int, Int> {
        if (widthPx <= 0 || heightPx <= 0 || maxEdgePx <= 0) {
            return 1280 to 720
        }
        val longest = maxOf(widthPx, heightPx)
        if (longest <= maxEdgePx) {
            return widthPx to heightPx
        }
        val scale = maxEdgePx.toDouble() / longest.toDouble()
        return maxOf(1, (widthPx * scale).toInt()) to maxOf(1, (heightPx * scale).toInt())
    }
}
