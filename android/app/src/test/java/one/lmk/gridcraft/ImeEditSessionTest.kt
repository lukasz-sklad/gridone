package one.lmk.gridcraft

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeEditSessionTest {
    @Test
    fun composingThenCommitTracksSeqAndText() {
        val session = ImeEditSession()

        val b1 = session.setComposingText("SUM", 1)
        assertNotNull(b1)
        assertEquals(1L, b1!!.seq)
        assertArrayEquals(intArrayOf(ImeEditSession.OP_PREEDIT), b1.opKinds)
        assertEquals("SUM", b1.texts[0])
        assertEquals("SUM", session.text)

        val b2 = session.commitText("SUM(", 1)
        assertNotNull(b2)
        assertEquals(2L, b2!!.seq)
        assertArrayEquals(intArrayOf(ImeEditSession.OP_COMMIT), b2.opKinds)
        assertEquals("SUM(", session.text)
        assertEquals(-1, session.currentComposingStart)
    }

    @Test
    fun composingRegionReplacesExistingWordWithoutDuplicating() {
        val session = ImeEditSession()
        session.reset("hello", 5, 5)
        session.setComposingRegion(0, 5)

        val batch = session.setComposingText("help", 1)!!
        assertArrayEquals(
            intArrayOf(
                ImeEditSession.OP_DELETE_SURROUNDING,
                ImeEditSession.OP_PREEDIT,
            ),
            batch.opKinds,
        )
        assertEquals(5, batch.intArgs[0])
        assertEquals("help", batch.texts[1])
        assertEquals("help", session.text)
    }

    @Test
    fun syncFromEngineIgnoresStaleAckSeqAndAppliesFreshAck() {
        val session = ImeEditSession()
        session.commitText("abc", 1) // seq = 1

        val stale = session.syncFromEngine("ab", 2, 2, ackSeq = 0L)
        assertFalse(stale.changed)
        assertEquals("abc", session.text)

        val fresh = session.syncFromEngine("=A1", 1, 3, ackSeq = 1L)
        assertTrue(fresh.changed)
        assertEquals("=A1", session.text)
        assertEquals(1, session.selectionStart)
        assertEquals(3, session.selectionEnd)
    }

    @Test
    fun singleLineEnterCommitsAsTapKey() {
        val session = ImeEditSession()
        val batch = session.commitText("\n", 1, singleLine = true)!!
        assertArrayEquals(intArrayOf(ImeEditSession.OP_TAP_KEY), batch.opKinds)
        assertEquals(ImeEditSession.KEYCODE_ENTER, batch.intArgs[0])
    }

    @Test
    fun batchEditCoalescesMultipleOpsIntoSingleSeq() {
        val session = ImeEditSession()
        session.beginBatchEdit()
        assertNull(session.commitText("A", 1))
        assertNull(session.commitText("1", 1))
        val batch = session.endBatchEdit()!!
        assertEquals(1L, batch.seq)
        assertEquals(2, batch.opKinds.size)
        assertEquals("A1", session.text)
    }

    @Test
    fun adversarialBoundsClampSafelyWithoutExceptions() {
        val session = ImeEditSession()
        session.reset("abc", -50, 999999)
        assertEquals(0, session.selectionStart)
        assertEquals(3, session.selectionEnd)

        session.setComposingRegion(-999, Int.MAX_VALUE)
        assertEquals(0, session.currentComposingStart)
        assertEquals(3, session.currentComposingEnd)

        val staleSync = session.syncFromEngine("stale", 0, 1, ackSeq = -1L)
        assertFalse(staleSync.changed)

        val sync = session.syncFromEngine("Żółć🚀", -10, Int.MAX_VALUE, ackSeq = session.latestSeq)
        assertTrue(sync.changed)
        assertEquals(0, session.selectionStart)
        assertEquals("Żółć🚀".length, session.selectionEnd)

        val batch = session.setComposingText("ok", 1000)
        assertNotNull(batch)
        assertEquals("ok", session.text)
    }
}
