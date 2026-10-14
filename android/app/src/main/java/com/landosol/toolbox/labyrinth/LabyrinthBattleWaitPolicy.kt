package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState

internal enum class LabyrinthBattleWaitDecision { NONE, WAIT, TIMED_OUT }

/** A fixed deadline, not an inactivity timer: recognition jitter cannot extend a battle forever. */
internal class LabyrinthBattleWaitPolicy {
    private var startedAt: Long? = null
    private var startPageGrace = false

    @Synchronized
    fun onStartExecuted(now: Long) {
        if (startedAt == null) {
            startedAt = now
            startPageGrace = true
        }
    }

    @Synchronized
    fun observe(
        page: LabyrinthEntryPageState,
        now: Long,
        battleResultNextReady: Boolean = false,
    ): LabyrinthBattleWaitDecision {
        if (page == LabyrinthEntryPageState.BATTLE_IN_PROGRESS && startedAt == null) {
            // Also support resuming automation during an already recognized battle.
            startedAt = now
        }
        val start = startedAt ?: return LabyrinthBattleWaitDecision.NONE
        val elapsed = (now - start).coerceAtLeast(0L)
        val waitingPage = when (page) {
            LabyrinthEntryPageState.UNKNOWN,
            LabyrinthEntryPageState.GAME_LOADING_PROGRESS,
            LabyrinthEntryPageState.PRE_HOME_DATA_LOADING,
            LabyrinthEntryPageState.BATTLE_IN_PROGRESS -> true
            // WIN can classify as BATTLE_RESULT before its “下一步” button is drawn. Preserve
            // the battle deadline through that transient state so the following UNKNOWN frames
            // remain under battle waiting instead of reaching the generic 30-second stop.
            LabyrinthEntryPageState.BATTLE_RESULT -> !battleResultNextReady
            // Permit a short stale editor/challenge frame after injection, but do not hide a failed start.
            LabyrinthEntryPageState.BATTLE_TEAM_SELECTION,
            LabyrinthEntryPageState.BATTLE_CHALLENGE -> startPageGrace && elapsed < 3_000L
            else -> false
        }
        if (!waitingPage) {
            reset()
            return LabyrinthBattleWaitDecision.NONE
        }
        if (page != LabyrinthEntryPageState.BATTLE_TEAM_SELECTION &&
            page != LabyrinthEntryPageState.BATTLE_CHALLENGE
        ) startPageGrace = false
        return if (elapsed >= 300_000L) LabyrinthBattleWaitDecision.TIMED_OUT
        else LabyrinthBattleWaitDecision.WAIT
    }

    @Synchronized
    fun reset() {
        startedAt = null
        startPageGrace = false
    }
}
