package com.landosol.toolbox.labyrinth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The relic page used to latch the same way the event page once did: a tap the game ignored left
 * [LabyrinthEntryRecognitionSession] waiting forever because the committed flag only cleared on a
 * page change, and the page was the thing that never changed.
 *
 * Contrast the 2026-10-12 bundles: a RELIC_CHOICE page sat motionless for 120 s with actionCount
 * frozen and was only ended by the page-stall watchdog. Retrying is safe because the relic page
 * accepts a single selection per round — if the tap did land and the page merely lagged, the page
 * has changed by the time the retry is planned.
 */
class LabyrinthSelectionCommitRetryTest {
    @Test fun `a committed relic tap the game ignored is due for a retry`() {
        val committedAt = 1_000L
        // Nothing is retried while the page may still legitimately be settling.
        assertFalse(labyrinthSelectionCommitExpired(committedAt, committedAt))
        assertFalse(labyrinthSelectionCommitExpired(committedAt, committedAt + 2_000L))
        assertFalse(
            labyrinthSelectionCommitExpired(committedAt, committedAt + SELECTION_COMMIT_RETRY_MILLIS - 1),
        )
        // A real selection leaves the page well inside the window.
        assertTrue(
            labyrinthSelectionCommitExpired(committedAt, committedAt + SELECTION_COMMIT_RETRY_MILLIS),
        )
        // The 120 s this actually cost is far past the point of retrying.
        assertTrue(labyrinthSelectionCommitExpired(committedAt, committedAt + 120_000L))
    }

    @Test fun `nothing expires before a tap has been committed`() {
        assertFalse(labyrinthSelectionCommitExpired(Long.MIN_VALUE, 500_000L))
    }

    @Test fun `a clock that goes backwards does not retry immediately`() {
        assertFalse(labyrinthSelectionCommitExpired(10_000L, 9_000L))
    }

    @Test fun `the relic window matches the event window so both pages behave alike`() {
        assertTrue(SELECTION_COMMIT_RETRY_MILLIS > 0L)
        assertTrue(SELECTION_COMMIT_RETRY_MILLIS == EVENT_CHOICE_COMMIT_RETRY_MILLIS)
    }
}
