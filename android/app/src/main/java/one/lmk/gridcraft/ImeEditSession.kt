package one.lmk.gridcraft

/**
 * Pure-Kotlin state machine synchronizing Android `InputConnection` edits against GridCraft's
 * Rust `egui` text buffer without composing-span loops or duplicate word commits.
 *
 * Op codes match `apps/gridcraft-android/src/ime.rs`:
 * - `1`: `Preedit(text, anchorCp, focusCp)`
 * - `2`: `Commit(text)`
 * - `3`: `DeleteSurrounding(beforeCp, afterCp)`
 * - `4`: `SetSelectionRelative(deltaAnchorCp, deltaFocusCp)`
 * - `5`: `TapKey(keyCode)`
 */
class ImeEditSession {
    companion object {
        const val OP_PREEDIT = 1
        const val OP_COMMIT = 2
        const val OP_DELETE_SURROUNDING = 3
        const val OP_SET_SELECTION_RELATIVE = 4
        const val OP_TAP_KEY = 5

        const val KEYCODE_TAB = 61
        const val KEYCODE_ENTER = 66
    }

    data class PendingOp(
        val kind: Int,
        val a0: Int = 0,
        val a1: Int = 0,
        val text: String = "",
    )

    class Batch(
        val seq: Long,
        val opKinds: IntArray,
        val intArgs: IntArray,
        val texts: Array<String>,
    )

    data class SyncResult(
        val changed: Boolean,
        val selStart: Int,
        val selEnd: Int,
        val composingStart: Int,
        val composingEnd: Int,
    )

    private val buffer = StringBuilder()
    private var selStart: Int = 0
    private var selEnd: Int = 0
    private var composingStart: Int = -1
    private var composingEnd: Int = -1
    private var enginePreeditActive: Boolean = false
    private var seq: Long = 0
    private var batchDepth: Int = 0
    private val pendingOps = mutableListOf<PendingOp>()

    val text: String
        get() = buffer.toString()

    val selectionStart: Int
        get() = selStart

    val selectionEnd: Int
        get() = selEnd

    val currentComposingStart: Int
        get() = composingStart

    val currentComposingEnd: Int
        get() = composingEnd

    val latestSeq: Long
        get() = seq

    fun reset(newText: String = "", anchorU16: Int = 0, focusU16: Int = 0) {
        buffer.setLength(0)
        buffer.append(newText)
        val clampedAnchor = anchorU16.coerceIn(0, buffer.length)
        val clampedFocus = focusU16.coerceIn(0, buffer.length)
        selStart = minOf(clampedAnchor, clampedFocus)
        selEnd = maxOf(clampedAnchor, clampedFocus)
        composingStart = -1
        composingEnd = -1
        enginePreeditActive = false
        pendingOps.clear()
        batchDepth = 0
    }

    fun beginBatchEdit() {
        batchDepth += 1
    }

    fun endBatchEdit(): Batch? {
        if (batchDepth > 0) {
            batchDepth -= 1
        }
        return if (batchDepth == 0) flushOps() else null
    }

    fun setComposingRegion(start: Int, end: Int) {
        val lo = minOf(start, end).coerceIn(0, buffer.length)
        val hi = maxOf(start, end).coerceIn(0, buffer.length)
        if (lo == hi) {
            composingStart = -1
            composingEnd = -1
            enginePreeditActive = false
            return
        }
        composingStart = lo
        composingEnd = hi
        enginePreeditActive = false
    }

    fun setComposingText(newText: CharSequence, newCursorPosition: Int): Batch? {
        val str = newText.toString()
        val prevFocusCp = codePointsFromStart(selEnd)
        val replaceStart = if (composingStart in 0..buffer.length && composingEnd in composingStart..buffer.length) {
            composingStart
        } else {
            selStart
        }
        val replaceEnd = if (composingStart in 0..buffer.length && composingEnd in composingStart..buffer.length) {
            composingEnd
        } else {
            selEnd
        }

        val hadLiveComposing = composingStart in 0..buffer.length && composingEnd in composingStart..buffer.length && !enginePreeditActive
        val replacedCpCount = if (hadLiveComposing || (!enginePreeditActive && replaceEnd > replaceStart)) {
            Character.codePointCount(buffer, replaceStart, replaceEnd)
        } else {
            0
        }

        if (replacedCpCount > 0) {
            val replaceEndCp = codePointsFromStart(replaceEnd)
            val deltaToEnd = replaceEndCp - prevFocusCp
            if (deltaToEnd != 0) {
                pendingOps.add(PendingOp(OP_SET_SELECTION_RELATIVE, deltaToEnd, deltaToEnd))
            }
            pendingOps.add(PendingOp(OP_DELETE_SURROUNDING, replacedCpCount, 0))
        }

        buffer.replace(replaceStart, replaceEnd, str)
        val insertedEnd = replaceStart + str.length
        if (str.isNotEmpty()) {
            composingStart = replaceStart
            composingEnd = insertedEnd
        } else {
            composingStart = -1
            composingEnd = -1
        }

        val newCaret = computeCursorAfterEdit(replaceStart, insertedEnd, newCursorPosition)
        selStart = newCaret
        selEnd = newCaret

        val strCp = Character.codePointCount(str, 0, str.length)
        pendingOps.add(PendingOp(OP_PREEDIT, strCp, strCp, str))
        enginePreeditActive = str.isNotEmpty()

        return if (batchDepth == 0) flushOps() else null
    }

    fun commitText(newText: CharSequence, newCursorPosition: Int, singleLine: Boolean = false): Batch? {
        val str = newText.toString()
        if (singleLine && str == "\n") {
            return tapKey(KEYCODE_ENTER)
        }

        val prevFocusCp = codePointsFromStart(selEnd)
        val replaceStart = if (composingStart in 0..buffer.length && composingEnd in composingStart..buffer.length) {
            composingStart
        } else {
            selStart
        }
        val replaceEnd = if (composingStart in 0..buffer.length && composingEnd in composingStart..buffer.length) {
            composingEnd
        } else {
            selEnd
        }

        if (!enginePreeditActive && replaceEnd > replaceStart) {
            val replaceEndCp = codePointsFromStart(replaceEnd)
            val deltaToEnd = replaceEndCp - prevFocusCp
            if (deltaToEnd != 0) {
                pendingOps.add(PendingOp(OP_SET_SELECTION_RELATIVE, deltaToEnd, deltaToEnd))
            }
            val cpCount = Character.codePointCount(buffer, replaceStart, replaceEnd)
            if (cpCount > 0) {
                pendingOps.add(PendingOp(OP_DELETE_SURROUNDING, cpCount, 0))
            }
        }

        buffer.replace(replaceStart, replaceEnd, str)
        val insertedEnd = replaceStart + str.length
        composingStart = -1
        composingEnd = -1
        enginePreeditActive = false

        val newCaret = computeCursorAfterEdit(replaceStart, insertedEnd, newCursorPosition)
        selStart = newCaret
        selEnd = newCaret

        pendingOps.add(PendingOp(OP_COMMIT, 0, 0, str))
        return if (batchDepth == 0) flushOps() else null
    }

    fun finishComposingText(): Batch? {
        if (composingStart in 0..buffer.length && composingEnd in composingStart..buffer.length) {
            if (enginePreeditActive) {
                val committed = buffer.substring(composingStart, composingEnd)
                pendingOps.add(PendingOp(OP_COMMIT, 0, 0, committed))
            }
        }
        composingStart = -1
        composingEnd = -1
        enginePreeditActive = false
        return if (batchDepth == 0) flushOps() else null
    }

    fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Batch? {
        if (beforeLength <= 0 && afterLength <= 0) return null
        val safeStart = selStart.coerceIn(0, buffer.length)
        val safeEnd = selEnd.coerceIn(safeStart, buffer.length)
        val delStart = if (beforeLength > 0) (safeStart - beforeLength).coerceAtLeast(0) else safeStart
        val delEnd = if (afterLength > 0) (safeEnd + afterLength).coerceAtMost(buffer.length) else safeEnd
        return applySurroundingDeletion(delStart, safeStart, safeEnd, delEnd)
    }

    fun deleteSurroundingTextInCodePoints(beforeCodePoints: Int, afterCodePoints: Int): Batch? {
        if (beforeCodePoints <= 0 && afterCodePoints <= 0) return null
        val safeStart = selStart.coerceIn(0, buffer.length)
        val safeEnd = selEnd.coerceIn(safeStart, buffer.length)
        val delStart = if (beforeCodePoints > 0) {
            try {
                Character.offsetByCodePoints(buffer, safeStart, -beforeCodePoints.coerceAtMost(safeStart))
            } catch (_: IndexOutOfBoundsException) {
                0
            }
        } else {
            safeStart
        }
        val delEnd = if (afterCodePoints > 0) {
            val remainingCp = Character.codePointCount(buffer, safeEnd, buffer.length)
            if (afterCodePoints >= remainingCp) {
                buffer.length
            } else {
                Character.offsetByCodePoints(buffer, safeEnd, afterCodePoints)
            }
        } else {
            safeEnd
        }
        return applySurroundingDeletion(delStart, safeStart, safeEnd, delEnd)
    }

    fun setSelection(start: Int, end: Int): Batch? {
        val lo = minOf(start, end).coerceIn(0, buffer.length)
        val hi = maxOf(start, end).coerceIn(0, buffer.length)
        val oldAnchorCp = codePointsFromStart(selStart)
        val oldFocusCp = codePointsFromStart(selEnd)
        val newAnchorCp = codePointsFromStart(lo)
        val newFocusCp = codePointsFromStart(hi)
        selStart = lo
        selEnd = hi
        val dAnchor = newAnchorCp - oldAnchorCp
        val dFocus = newFocusCp - oldFocusCp
        if (dAnchor != 0 || dFocus != 0) {
            pendingOps.add(PendingOp(OP_SET_SELECTION_RELATIVE, dAnchor, dFocus))
        }
        return if (batchDepth == 0) flushOps() else null
    }

    fun tapKey(keyCode: Int): Batch? {
        pendingOps.add(PendingOp(OP_TAP_KEY, keyCode, 0))
        return if (batchDepth == 0) flushOps() else null
    }

    fun syncFromEngine(
        engineText: String,
        anchorU16: Int,
        focusU16: Int,
        ackSeq: Long,
    ): SyncResult {
        if (ackSeq < seq || batchDepth > 0 || enginePreeditActive) {
            return SyncResult(
                changed = false,
                selStart = selStart,
                selEnd = selEnd,
                composingStart = composingStart,
                composingEnd = composingEnd,
            )
        }
        val clampedAnchor = anchorU16.coerceIn(0, engineText.length)
        val clampedFocus = focusU16.coerceIn(0, engineText.length)
        val newStart = minOf(clampedAnchor, clampedFocus)
        val newEnd = maxOf(clampedAnchor, clampedFocus)
        val textChanged = buffer.toString() != engineText
        val selChanged = selStart != newStart || selEnd != newEnd
        if (!textChanged && !selChanged) {
            return SyncResult(false, selStart, selEnd, composingStart, composingEnd)
        }
        if (textChanged) {
            buffer.setLength(0)
            buffer.append(engineText)
            composingStart = -1
            composingEnd = -1
        }
        selStart = newStart
        selEnd = newEnd
        return SyncResult(true, selStart, selEnd, composingStart, composingEnd)
    }

    private fun applySurroundingDeletion(
        delStart: Int,
        safeStart: Int,
        safeEnd: Int,
        delEnd: Int,
    ): Batch? {
        val beforeCp = if (delStart < safeStart) Character.codePointCount(buffer, delStart, safeStart) else 0
        val afterCp = if (delEnd > safeEnd) Character.codePointCount(buffer, safeEnd, delEnd) else 0
        if (beforeCp == 0 && afterCp == 0) return null

        val removedAfter = delEnd - safeEnd
        val removedBefore = safeStart - delStart
        if (removedAfter > 0) {
            buffer.delete(safeEnd, delEnd)
        }
        if (removedBefore > 0) {
            buffer.delete(delStart, safeStart)
        }
        selStart = delStart
        selEnd = (safeEnd - removedBefore).coerceIn(delStart, buffer.length)
        if (composingStart >= 0 && composingEnd >= composingStart) {
            composingStart = adjustOffsetForTwoDeletes(composingStart, delStart, safeStart, safeEnd, delEnd)
            composingEnd = adjustOffsetForTwoDeletes(composingEnd, delStart, safeStart, safeEnd, delEnd)
            if (composingStart >= composingEnd) {
                composingStart = -1
                composingEnd = -1
                enginePreeditActive = false
            }
        }
        pendingOps.add(PendingOp(OP_DELETE_SURROUNDING, beforeCp, afterCp))
        return if (batchDepth == 0) flushOps() else null
    }

    private fun adjustOffsetForTwoDeletes(
        pos: Int,
        bStart: Int,
        bEnd: Int,
        aStart: Int,
        aEnd: Int,
    ): Int {
        var p = pos
        if (aEnd > aStart && p > aStart) {
            p = if (p <= aEnd) aStart else p - (aEnd - aStart)
        }
        if (bEnd > bStart && p > bStart) {
            p = if (p <= bEnd) bStart else p - (bEnd - bStart)
        }
        return p.coerceIn(0, buffer.length)
    }

    private fun computeCursorAfterEdit(start: Int, insertedEnd: Int, newCursorPosition: Int): Int {
        val target = if (newCursorPosition > 0) {
            insertedEnd + newCursorPosition - 1
        } else {
            start + newCursorPosition
        }
        return target.coerceIn(0, buffer.length)
    }

    private fun codePointsFromStart(u16Offset: Int): Int {
        val clamped = u16Offset.coerceIn(0, buffer.length)
        return Character.codePointCount(buffer, 0, clamped)
    }

    private fun flushOps(): Batch? {
        if (pendingOps.isEmpty()) return null
        seq += 1L
        val count = pendingOps.size
        val kinds = IntArray(count)
        val args = IntArray(count * 2)
        val texts = Array(count) { "" }
        for (i in 0 until count) {
            val op = pendingOps[i]
            kinds[i] = op.kind
            args[i * 2] = op.a0
            args[i * 2 + 1] = op.a1
            texts[i] = op.text
        }
        pendingOps.clear()
        return Batch(seq, kinds, args, texts)
    }
}
