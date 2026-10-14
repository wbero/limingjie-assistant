package com.landosol.toolbox.automation.session

import kotlinx.coroutines.delay

sealed interface GameClientHardCloseResult {
    /** The process was asked to die after the game had left the foreground. */
    data object Closed : GameClientHardCloseResult

    data class Failed(val reason: String) : GameClientHardCloseResult
}

/**
 * Hard-closes (大退) a game client without root: leaves it with the home key, then kills its
 * now-background processes (`ActivityManager.killBackgroundProcesses`, a normal permission).
 *
 * Android only lets an ordinary app kill another app's processes once they are in the background,
 * so the home step is required and is confirmed through the accessibility foreground package
 * before the kill. The kill itself reports nothing; the caller cold-starts the game afterwards.
 * Every wait is bounded.
 */
class GameClientHardCloser(
    private val foregroundPackage: () -> String?,
    private val pressHome: suspend () -> Boolean,
    private val killBackgroundProcesses: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val leaveForegroundTimeoutMillis: Long = 5_000L,
    private val pollMillis: Long = 200L,
    /** Lets the system demote the process out of the "previous app" slot before the kill. */
    private val settleMillis: Long = 500L,
    private val trace: (String) -> Unit = {},
) {
    init {
        require(leaveForegroundTimeoutMillis > 0)
        require(pollMillis > 0)
        require(settleMillis >= 0)
    }

    suspend fun close(packageName: String): GameClientHardCloseResult {
        if (foregroundPackage() == packageName) {
            trace("hard-close home pkg=$packageName")
            if (!pressHome()) return GameClientHardCloseResult.Failed("系统拒绝返回桌面，无法让游戏退到后台")
            val deadline = clock() + leaveForegroundTimeoutMillis
            while (foregroundPackage() == packageName) {
                if (clock() >= deadline) {
                    return GameClientHardCloseResult.Failed("游戏 ${leaveForegroundTimeoutMillis / 1_000} 秒内没有退到后台")
                }
                delay(pollMillis)
            }
        }
        delay(settleMillis)
        val killed = runCatching { killBackgroundProcesses(packageName) }
        killed.exceptionOrNull()?.let { error ->
            return GameClientHardCloseResult.Failed("结束游戏进程失败：${error.message ?: error.javaClass.simpleName}")
        }
        trace("hard-close killed pkg=$packageName")
        return GameClientHardCloseResult.Closed
    }
}
