package com.landosol.toolbox.automation.session

import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GameClientTaskResetterTest {
    private val game = "com.bilibili.priconne.m4399"
    private val launcher = "com.android.launcher3"

    @Test
    fun `a foreground game is sent home before task restart`() = runTest {
        var foreground: String? = game
        val events = mutableListOf<String>()
        val resetter = GameClientTaskResetter(
            foregroundPackage = { foreground },
            pressHome = {
                events += "home"
                foreground = launcher
                true
            },
            clock = { currentTime },
            trace = { events += it },
        )

        assertEquals(GameClientTaskResetResult.ReadyToRestart, resetter.prepare(game))
        assertEquals(listOf("task-reset home pkg=$game", "home", "task-reset backgrounded pkg=$game"), events)
    }

    @Test
    fun `a game already in the background is ready for task restart`() = runTest {
        val events = mutableListOf<String>()
        val resetter = GameClientTaskResetter(
            foregroundPackage = { "com.landosol.toolbox" },
            pressHome = { events += "home"; true },
            clock = { currentTime },
            trace = { events += it },
        )

        assertEquals(GameClientTaskResetResult.ReadyToRestart, resetter.prepare(game))
        assertEquals(listOf("task-reset backgrounded pkg=$game"), events)
    }

    @Test
    fun `preparation waits until the game has actually left the foreground`() = runTest {
        var foreground: String? = game
        val resetter = GameClientTaskResetter(
            foregroundPackage = { foreground },
            pressHome = { true },
            clock = { currentTime },
            pollMillis = 100L,
        )
        backgroundScope.launchLater(1_200L) { foreground = launcher }

        assertEquals(GameClientTaskResetResult.ReadyToRestart, resetter.prepare(game))
        assertTrue(currentTime >= 1_200L)
    }

    @Test
    fun `a game that never leaves the foreground fails within the timeout`() = runTest {
        val resetter = GameClientTaskResetter(
            foregroundPackage = { game },
            pressHome = { true },
            clock = { currentTime },
            leaveForegroundTimeoutMillis = 3_000L,
        )

        val result = resetter.prepare(game)

        assertTrue(result is GameClientTaskResetResult.Failed)
        assertTrue(currentTime in 3_000L..3_500L)
    }

    @Test
    fun `a refused home key fails`() = runTest {
        val resetter = GameClientTaskResetter(
            foregroundPackage = { game },
            pressHome = { false },
            clock = { currentTime },
        )

        assertTrue(resetter.prepare(game) is GameClientTaskResetResult.Failed)
    }

    @Test
    fun `the task launch gate requires the title before in-game pages`() {
        val gate = FreshTaskLaunchGate()

        assertEquals(FreshTaskLaunchVerdict.PENDING, gate.observe(LabyrinthEntryPageState.UNKNOWN))
        assertEquals(FreshTaskLaunchVerdict.PENDING, gate.observe(LabyrinthEntryPageState.GAME_LOADING_PROGRESS))
        assertEquals(FreshTaskLaunchVerdict.CONFIRMED, gate.observe(LabyrinthEntryPageState.TITLE_WAITING_TAP))
        assertEquals(FreshTaskLaunchVerdict.CONFIRMED, gate.observe(LabyrinthEntryPageState.HOME))
        assertTrue(gate.titleSeen)
    }

    @Test
    fun `a stale in-game task is rejected before the title`() {
        val gate = FreshTaskLaunchGate()

        assertEquals(FreshTaskLaunchVerdict.PENDING, gate.observe(LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE))
        assertEquals(FreshTaskLaunchVerdict.STALE_TASK, gate.observe(LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE))
        assertTrue(!gate.titleSeen)
    }

    @Test
    fun `one stray in-game frame between loading frames does not stop the reset`() {
        val gate = FreshTaskLaunchGate()

        assertEquals(FreshTaskLaunchVerdict.PENDING, gate.observe(LabyrinthEntryPageState.HOME))
        assertEquals(FreshTaskLaunchVerdict.PENDING, gate.observe(LabyrinthEntryPageState.UNKNOWN))
        assertEquals(FreshTaskLaunchVerdict.PENDING, gate.observe(LabyrinthEntryPageState.HOME))
        assertEquals(FreshTaskLaunchVerdict.CONFIRMED, gate.observe(LabyrinthEntryPageState.TITLE_WAITING_TAP))
    }

    @Test
    fun `a task reset ticket only counts for the account and client it was issued for`() {
        val ledger = GameClientTaskResetLedger()

        ledger.issue(accountId = 1L, packageName = game)
        assertTrue(!ledger.consume(2L, game))
        assertTrue(!ledger.consume(1L, game))

        ledger.issue(accountId = 1L, packageName = game)
        assertTrue(!ledger.consume(1L, "com.bilibili.priconne.mi"))
        assertTrue(!ledger.consume(1L, game))

        ledger.issue(accountId = 1L, packageName = game)
        assertTrue(!ledger.consume(null, game))
    }

    @Test
    fun `a failed channel reroll does not leak task reset into the next channel`() {
        val ledger = GameClientTaskResetLedger()
        ledger.issue(accountId = 1L, packageName = game)
        ledger.clear()

        assertTrue(!ledger.consume(2L, "com.bilibili.priconne.mi"))
    }

    @Test
    fun `a stale ticket is refused for another account`() {
        val ledger = GameClientTaskResetLedger()
        ledger.issue(accountId = 1L, packageName = game)

        assertTrue(!ledger.consume(2L, "com.bilibili.priconne.mi"))
    }

    private fun CoroutineScope.launchLater(delayMillis: Long, block: () -> Unit) {
        launch {
            delay(delayMillis)
            block()
        }
    }
}
