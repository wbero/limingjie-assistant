package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.AutomationBackendResult
import com.landosol.toolbox.automation.AutomationSessionManager
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleFailureLayout
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleFailureObservation
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session must treat the two-button (relic-revive) failure page as **advance**, not retry.
 *
 * The vision layer already reports `BATTLE_FAILED` with `layout == TWO_BUTTON` for that page.
 * Everything below is about the session's own books: the retry budget, the failed-team signatures
 * and the committed team ids must not move when the lower-right button — which on this layout reads
 * "下一步", not "重新挑战" — is pressed, and `battleWait` must be reset so the "遗物效果结果"
 * popup that follows is handled as a new page. See
 * `android/docs/relic-revive-battle-failure-audit.md`, section 「3. 实现级设计」.
 *
 * The three-button page keeps its old accounting bit for bit; that is the control case.
 */
class LabyrinthBattleFailureAdvanceTest {
    private val advanceKind: LabyrinthPostEntryActionKind = LabyrinthPostEntryActionKind.BATTLE_FAILURE_NEXT

    @Test
    fun `two button advance keeps the retry budget and the team books`() = runBlocking {
        withSession { session, manager ->
            seedFailureBooks(session, retryCount = 2)

            dispatch(session, manager, advanceKind)

            assertEquals("retry budget moved", 2, retryCount(session))
            // A retry appends the current attempt's teams to the failed set and clears the rest.
            // An advance must leave all three books exactly as they were.
            assertEquals("failed team signatures changed", setOf("A,B,C"), failedSignatures(session))
            assertEquals("current team signatures were cleared", setOf("X,Y,Z"), currentSignatures(session))
            assertEquals("committed team ids were cleared", setOf("1002"), committedIds(session))
            assertNull("battle wait was not reset", battleWaitStarted(session))
            // Frame budget reset so the "遗物效果结果" popup that follows is handled as a new page.
            assertEquals(0, frameCounter(session, "postEntryStableFrames"))
            assertEquals(0, frameCounter(session, "postEntryAttempts"))
        }
    }

    @Test
    fun `three button retry still consumes a retry and clears the team books`() = runBlocking {
        withSession { session, manager ->
            seedFailureBooks(session, retryCount = 2)

            dispatch(session, manager, LabyrinthPostEntryActionKind.BATTLE_RETRY)

            assertEquals(3, retryCount(session))
            // The retry path books the attempt that just failed and forgets what was committed.
            assertEquals(setOf("A,B,C", "X,Y,Z"), failedSignatures(session))
            assertTrue("current team signatures survived a retry", currentSignatures(session).isEmpty())
            assertTrue("committed team ids survived a retry", committedIds(session).isEmpty())
            assertNull(battleWaitStarted(session))
            assertEquals(0, frameCounter(session, "postEntryStableFrames"))
            assertEquals(0, frameCounter(session, "postEntryAttempts"))
        }
    }

    @Test
    fun `two button advance is a distinct action kind from the retry`() {
        assertTrue(
            "the session cannot tell the relic-revive advance from a retry",
            LabyrinthPostEntryActionKind.entries.any { it.name == "BATTLE_FAILURE_NEXT" },
        )
        assertTrue(advanceKind != LabyrinthPostEntryActionKind.BATTLE_RETRY)
        assertTrue(advanceKind != LabyrinthPostEntryActionKind.BATTLE_RETRY_SWITCH_MULTI)
    }

    // ------------------------------------------------------- the plan the session builds

    @Test
    fun `the plan keeps the same lower right rect for both layouts and splits only the kind`() {
        val rect = EntryPixelRect(1405, 935, 430, 110)

        val twoButton = labyrinthBattleFailureTapPlan(
            LabyrinthBattleFailureObservation(
                confidence = 0.645848202927849,
                endButtonRect = null,
                retryButtonRect = rect,
                layout = LabyrinthBattleFailureLayout.TWO_BUTTON,
            ),
            pendingSingleBossFallbackToMulti = false,
        )
        assertEquals(LabyrinthPostEntryActionKind.BATTLE_FAILURE_NEXT, twoButton.kind)
        assertEquals(rect, twoButton.rect)
        // The BOSS single-team fallback must not turn an advance into a retry either.
        assertEquals(
            LabyrinthPostEntryActionKind.BATTLE_FAILURE_NEXT,
            labyrinthBattleFailureTapPlan(
                LabyrinthBattleFailureObservation(0.6, null, rect, LabyrinthBattleFailureLayout.TWO_BUTTON),
                pendingSingleBossFallbackToMulti = true,
            ).kind,
        )

        val threeButton = labyrinthBattleFailureTapPlan(
            LabyrinthBattleFailureObservation(0.708, rect, rect),
            pendingSingleBossFallbackToMulti = false,
        )
        assertEquals(LabyrinthPostEntryActionKind.BATTLE_RETRY, threeButton.kind)
        assertEquals("战斗失败：重新挑战", threeButton.label)
        assertEquals(rect, threeButton.rect)

        val switchMulti = labyrinthBattleFailureTapPlan(
            LabyrinthBattleFailureObservation(0.708, rect, rect),
            pendingSingleBossFallbackToMulti = true,
        )
        assertEquals(LabyrinthPostEntryActionKind.BATTLE_RETRY_SWITCH_MULTI, switchMulti.kind)
        assertEquals("Boss单队达到设定重试次数：切换多队重新挑战", switchMulti.label)
    }

    @Test
    fun `only the three button layout runs the retry budget policy`() {
        assertTrue(labyrinthBattleFailureUsesRetryBudget(LabyrinthBattleFailureLayout.THREE_BUTTON))
        assertTrue(!labyrinthBattleFailureUsesRetryBudget(LabyrinthBattleFailureLayout.TWO_BUTTON))
    }

    // ------------------------------------------------------------------ harness

    private suspend fun withSession(
        body: suspend (LabyrinthEntryRecognitionSession, AutomationSessionManager) -> Unit,
    ) {
        val manager = AutomationSessionManager()
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager,
            captureActive = { true },
            processorFactory = { { error("no captured frames in this action test") } },
            actionExecutor = SessionBoundActionExecutor(manager) { AutomationBackendResult.Completed },
            actionsAvailable = { true },
            gameLauncher = { true },
        )
        try {
            assertTrue(session.startAutomation() is LabyrinthEntryRecognitionStartResult.Started)
            body(session, manager)
        } finally {
            session.stop()
        }
    }

    /** Books a 「重新挑战」 tap would touch, plus an armed battle wait. */
    // Reflection strips the generic type of the session's private collections, so the casts below
    // cannot be checked. Same suppress as the pre-existing reflection harnesses
    // (LabyrinthRoleRewardCommitTest, LabyrinthBossEditorProgressTest).
    @Suppress("UNCHECKED_CAST")
    private fun seedFailureBooks(session: LabyrinthEntryRecognitionSession, retryCount: Int) {
        set(session, "battleRetryCount", retryCount)
        (field(session, "failedBattleTeamSignatures").get(session) as MutableSet<String>).add("A,B,C")
        (field(session, "committedBattleCharacterIds").get(session) as MutableSet<String>).add("1002")
        // The team that is on screen now: a retry books it as failed and clears the set; an
        // advance must leave it alone.
        (field(session, "currentBattleTeamSignatures").get(session) as MutableSet<String>).add("X,Y,Z")
        val wait = field(session, "battleWait").get(session) as LabyrinthBattleWaitPolicy
        wait.onStartExecuted(System.currentTimeMillis())
        assertNotNull(battleWaitStarted(session))
    }

    private suspend fun dispatch(
        session: LabyrinthEntryRecognitionSession,
        manager: AutomationSessionManager,
        kind: LabyrinthPostEntryActionKind,
    ) {
        val dispatch = session.javaClass.declaredMethods.single {
            it.name.startsWith("dispatchPostEntryTap-") && !it.name.endsWith("\$default")
        }.apply { isAccessible = true }
        dispatch.invoke(
            session, manager.current()!!.id.value, kind, "battle-failure-test",
            EntryPixelRect(1405, 935, 430, 110), System.currentTimeMillis(),
            null, null, null, null, null, false, emptyList<String>(),
        )
        val inFlight = field(session, "actionInFlight").get(session) as AtomicBoolean
        withTimeout(5_000) { while (inFlight.get()) delay(10) }
    }

    private fun retryCount(session: LabyrinthEntryRecognitionSession): Int =
        field(session, "battleRetryCount").get(session) as Int

    private fun frameCounter(session: LabyrinthEntryRecognitionSession, name: String): Int =
        field(session, name).get(session) as Int

    @Suppress("UNCHECKED_CAST")
    private fun failedSignatures(session: LabyrinthEntryRecognitionSession): Set<String> =
        (field(session, "failedBattleTeamSignatures").get(session) as Set<String>).toSet()

    @Suppress("UNCHECKED_CAST")
    private fun committedIds(session: LabyrinthEntryRecognitionSession): Set<String> =
        (field(session, "committedBattleCharacterIds").get(session) as Set<String>).toSet()

    @Suppress("UNCHECKED_CAST")
    private fun currentSignatures(session: LabyrinthEntryRecognitionSession): Set<String> =
        (field(session, "currentBattleTeamSignatures").get(session) as Set<String>).toSet()

    /** `startedAt` of the battle-wait policy, or null when it was reset. */
    @Suppress("UNCHECKED_CAST")
    private fun battleWaitStarted(session: LabyrinthEntryRecognitionSession): Long? {
        val wait = field(session, "battleWait").get(session)
        return field(wait, "startedAt").get(wait) as Long?
    }

    // `target` is nullable because every read goes through Field.get, whose platform type Kotlin
    // infers as Any?. Reflection itself handles null fine (it returns the field, or throws the same
    // NPE as before when the target is genuinely null).
    private fun field(target: Any?, name: String) =
        target!!.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private fun set(target: Any?, name: String, value: Any?) = field(target, name).set(target, value)
}
