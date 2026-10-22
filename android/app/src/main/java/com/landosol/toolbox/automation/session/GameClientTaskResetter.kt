package com.landosol.toolbox.automation.session

import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay

sealed interface GameClientTaskResetResult {
    data object ReadyToRestart : GameClientTaskResetResult
    data class Failed(val reason: String) : GameClientTaskResetResult
}

/**
 * Prepares for a task-stack restart by sending the game to the background. The later launch uses
 * CLEAR_TASK | NEW_TASK; this resets the Activity task, but does not guarantee process termination.
 */
class GameClientTaskResetter(
    private val foregroundPackage: () -> String?,
    private val pressHome: suspend () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val leaveForegroundTimeoutMillis: Long = 5_000L,
    private val pollMillis: Long = 200L,
    private val settleMillis: Long = 500L,
    private val trace: (String) -> Unit = {},
) {
    init {
        require(leaveForegroundTimeoutMillis > 0)
        require(pollMillis > 0)
        require(settleMillis >= 0)
    }

    suspend fun prepare(packageName: String): GameClientTaskResetResult {
        if (foregroundPackage() == packageName) {
            trace("task-reset home pkg=$packageName")
            if (!pressHome()) return GameClientTaskResetResult.Failed("系统拒绝返回桌面，无法让游戏退到后台")
            val deadline = clock() + leaveForegroundTimeoutMillis
            while (foregroundPackage() == packageName) {
                if (clock() >= deadline) {
                    return GameClientTaskResetResult.Failed("游戏 ${leaveForegroundTimeoutMillis / 1_000} 秒内没有退到后台")
                }
                delay(pollMillis)
            }
        }
        delay(settleMillis)
        trace("task-reset backgrounded pkg=$packageName")
        return GameClientTaskResetResult.ReadyToRestart
    }
}

data class GameClientTaskResetTicket(val accountId: Long, val packageName: String)

/** One-shot marker that the next matching client sync must restart the task stack. */
class GameClientTaskResetLedger {
    private val ticket = AtomicReference<GameClientTaskResetTicket?>(null)

    fun clear() {
        ticket.set(null)
    }

    fun issue(accountId: Long, packageName: String) {
        ticket.set(GameClientTaskResetTicket(accountId, packageName))
    }

    fun consume(accountId: Long?, packageName: String?): Boolean {
        val taken = ticket.getAndSet(null) ?: return false
        return accountId != null && packageName != null &&
            taken == GameClientTaskResetTicket(accountId, packageName)
    }
}

enum class FreshTaskLaunchVerdict {
    PENDING,
    CONFIRMED,
    STALE_TASK,
}

/**
 * Requires the title page to appear before an in-game page after a task-stack restart.
 * This validates the visible launch flow, not that Android terminated the app process.
 */
class FreshTaskLaunchGate(private val requiredSightings: Int = 2) {
    private var confirmed = false
    private var previousPage: LabyrinthEntryPageState? = null
    private var repeatedPageSightings = 0

    val titleSeen: Boolean get() = confirmed

    init {
        require(requiredSightings > 0)
    }

    fun observe(page: LabyrinthEntryPageState): FreshTaskLaunchVerdict {
        if (confirmed) return FreshTaskLaunchVerdict.CONFIRMED
        if (page == LabyrinthEntryPageState.TITLE_WAITING_TAP) {
            confirmed = true
            return FreshTaskLaunchVerdict.CONFIRMED
        }
        if (page in BEFORE_TITLE) {
            previousPage = null
            repeatedPageSightings = 0
            return FreshTaskLaunchVerdict.PENDING
        }
        repeatedPageSightings = if (page == previousPage) repeatedPageSightings + 1 else 1
        previousPage = page
        return if (repeatedPageSightings >= requiredSightings) {
            FreshTaskLaunchVerdict.STALE_TASK
        } else {
            FreshTaskLaunchVerdict.PENDING
        }
    }

    private companion object {
        val BEFORE_TITLE = setOf(
            LabyrinthEntryPageState.UNKNOWN,
            LabyrinthEntryPageState.GAME_LOADING_PROGRESS,
        )
    }
}
