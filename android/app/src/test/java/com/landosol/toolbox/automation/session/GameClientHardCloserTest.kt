package com.landosol.toolbox.automation.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameClientHardCloserTest {
    private val game = "com.bilibili.priconne.m4399"
    private val launcher = "com.android.launcher3"

    @Test
    fun `a foreground game is sent home before its processes are killed`() = runTest {
        var foreground: String? = game
        val events = mutableListOf<String>()
        val closer = GameClientHardCloser(
            foregroundPackage = { foreground },
            pressHome = {
                events += "home"
                foreground = launcher
                true
            },
            killBackgroundProcesses = { events += "kill $it" },
            clock = { currentTime },
        )

        assertEquals(GameClientHardCloseResult.Closed, closer.close(game))
        assertEquals(listOf("home", "kill $game"), events)
    }

    @Test
    fun `a game already in the background is killed without pressing home`() = runTest {
        val events = mutableListOf<String>()
        val closer = GameClientHardCloser(
            foregroundPackage = { "com.landosol.toolbox" },
            pressHome = { events += "home"; true },
            killBackgroundProcesses = { events += "kill $it" },
            clock = { currentTime },
        )

        assertEquals(GameClientHardCloseResult.Closed, closer.close(game))
        assertEquals(listOf("kill $game"), events)
    }

    @Test
    fun `the kill waits until the game has actually left the foreground`() = runTest {
        var foreground: String? = game
        var killedWhileForeground = false
        val closer = GameClientHardCloser(
            foregroundPackage = { foreground },
            pressHome = { true },
            killBackgroundProcesses = { killedWhileForeground = foreground == game },
            clock = { currentTime },
            pollMillis = 100L,
        )
        // The launcher only takes over 1.2 s after the home key.
        backgroundScope.launchLater(1_200L) { foreground = launcher }

        assertEquals(GameClientHardCloseResult.Closed, closer.close(game))
        assertTrue(!killedWhileForeground)
        assertTrue(currentTime >= 1_200L)
    }

    @Test
    fun `a game that never leaves the foreground is not killed and fails within the timeout`() = runTest {
        var killed = false
        val closer = GameClientHardCloser(
            foregroundPackage = { game },
            pressHome = { true },
            killBackgroundProcesses = { killed = true },
            clock = { currentTime },
            leaveForegroundTimeoutMillis = 3_000L,
        )

        val result = closer.close(game)

        assertTrue(result is GameClientHardCloseResult.Failed)
        assertTrue(!killed)
        assertTrue(currentTime in 3_000L..3_500L)
    }

    @Test
    fun `a refused home key fails without killing`() = runTest {
        var killed = false
        val closer = GameClientHardCloser(
            foregroundPackage = { game },
            pressHome = { false },
            killBackgroundProcesses = { killed = true },
            clock = { currentTime },
        )

        assertTrue(closer.close(game) is GameClientHardCloseResult.Failed)
        assertTrue(!killed)
    }

    @Test
    fun `a kill that throws is reported instead of claiming the game closed`() = runTest {
        val closer = GameClientHardCloser(
            foregroundPackage = { launcher },
            pressHome = { true },
            killBackgroundProcesses = { throw SecurityException("no permission") },
            clock = { currentTime },
        )

        val result = closer.close(game)

        assertTrue(result is GameClientHardCloseResult.Failed)
        assertTrue((result as GameClientHardCloseResult.Failed).reason.contains("no permission"))
    }

    private fun CoroutineScope.launchLater(delayMillis: Long, block: () -> Unit) {
        launch {
            delay(delayMillis)
            block()
        }
    }
}
