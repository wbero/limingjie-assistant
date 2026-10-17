package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.AutomationBackendResult
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.automation.AutomationSessionManager
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryAnchorMatch
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthAnchorScores
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleCharacterMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleElementFilter
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleScrollbarObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamRecognitionState
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Boss 切队成功即算「编组页动作被画面收下」。
 *
 * 判据是**编组页签名变了**（页面类型 + Boss 队伍页签），不是「页面类型变了」：Boss 切队成功后页面
 * 仍是编组页，只有页签变。只看页面类型会把一次成功的切队当成「点了没反应」，于是
 * `pendingBattleTeamAction` 与 `battleTeamActionUnconfirmedAttempts` 会带到下一队去——队伍1 失败两次
 * 再队伍2 失败两次就被误判成连续四次而停机。
 *
 * 反向用例同样必须成立：签名没变的编组页帧什么都不能清，那正是「派发了但画面没动」的本体。
 */
class LabyrinthBossTeamSwitchProgressTest {
    private val startButtonRect = EntryPixelRect(1565, 850, 270, 110)
    private val bossTeamTabs = listOf(
        EntryPixelRect(60, 120, 150, 90),
        EntryPixelRect(240, 120, 150, 90),
        EntryPixelRect(420, 120, 150, 90),
    )
    private val teamIds = listOf("r0", "r1", "r2", "r3", "r4")

    @Test
    fun `a boss tab change clears the pending action and the consecutive failure count`() {
        val session = bossSession()
        // 队伍1 上既有编组路径派发「切换队伍2」：派发即记账，画面还没确认。
        assertEquals(
            "Boss第1队编组完成，切换队伍2",
            feedFrame(session, bossTeamFrame(bossTeamIndex = 1, withStartButton = true), 1_000L),
        )
        assertNotNull("a NEXT_BOSS_TEAM dispatch must be remembered", pendingAction(session))
        assertEquals("BATTLE_TEAM_SELECTION:1", pendingSignature(session))
        // 队伍1 已经失败过两次（回滚重规划后的计数）。
        setField(session, "battleTeamActionUnconfirmedAttempts", 2)

        // 稳定的队伍2 页签帧：签名变了 ⇒ 上一次动作确实被游戏收下了。
        invoke(session, "handleBattleWaitFrame", SESSION_ID, bossTeamFrame(bossTeamIndex = 2), 2_500L)

        assertNull("a boss tab change is progress", pendingAction(session))
        assertEquals(0, unconfirmedAttempts(session))
    }

    @Test
    fun `a team page frame with an unchanged tab signature keeps the pending action and the count`() {
        val session = bossSession()
        assertEquals(
            "Boss第1队编组完成，切换队伍2",
            feedFrame(session, bossTeamFrame(bossTeamIndex = 1, withStartButton = true), 1_000L),
        )
        assertNotNull(pendingAction(session))
        setField(session, "battleTeamActionUnconfirmedAttempts", 2)

        // 同一页签、同一页面类型：画面没动，必须原样保留（否则真实未推进会被掩盖）。
        invoke(session, "handleBattleWaitFrame", SESSION_ID, bossTeamFrame(bossTeamIndex = 1), 2_500L)

        assertNotNull("an unchanged signature must not be counted as progress", pendingAction(session))
        assertEquals("BATTLE_TEAM_SELECTION:1", pendingSignature(session))
        assertEquals(2, unconfirmedAttempts(session))
    }

    // ---- 会话级脚手架 ----

    /**
     * Boss 多队编组页：推荐、编组计划与当前队伍一致，Boss 预处理已完成，特别装备不介入
     * （本用例只看编组页动作确认）。
     */
    private fun bossSession(): LabyrinthEntryRecognitionSession {
        val manager = AutomationSessionManager()
        val created = LabyrinthEntryRecognitionSession(
            sessionManager = manager,
            processorFactory = { { error("测试不投喂帧") } },
            actionExecutor = SessionBoundActionExecutor(manager) { AutomationBackendResult.Completed },
            actionsAvailable = { true },
        )
        setField(created, "activeSessionId", SESSION_ID)
        setField(created, "combatContext", LabyrinthCombatContext(LabyrinthCombatKind.BOSS))
        setField(created, "bossTeamMode", LabyrinthBossTeamMode.MULTI_TEAM)
        setField(created, "bossEditorPreparationStage", LabyrinthBossEditorPreparationStage.COMPLETE)
        setState(created) { state ->
            state.copy(
                battleTeamSelectionPlan = readyPlan(),
                battleTeamRecommendation = readyRecommendation(),
            )
        }
        return created
    }

    private fun readyPlan(selectedIds: List<String> = teamIds): LabyrinthBattleTeamSelectionPlan =
        LabyrinthBattleTeamSelectionPlan(
            sessionId = SESSION_ID,
            recommendedIds = selectedIds,
            recommendedNames = emptyMap(),
            currentlySelectedIds = selectedIds,
            currentlySelectedNames = emptyMap(),
            alreadyCorrectIds = selectedIds,
            needSelectIds = emptyList(),
            needDeselectIds = emptyList(),
            visibleSelectTargets = emptyList(),
            visibleDeselectTargets = emptyList(),
            unsafeVisibleMatches = emptyList(),
            notCurrentlyVisibleIds = emptyList(),
            currentFilter = LabyrinthBattleElementFilter.ALL,
            recommendedNextFilter = null,
            recommendedFilterTarget = null,
            missingTargetFilters = emptyList(),
            scrollRequired = false,
            viewportRevision = 0L,
            readyToExecute = true,
            blockedReason = null,
            teamReady = true,
            minimumCharacterConfidence = 0.0,
        )

    private fun readyRecommendation(ids: List<String> = teamIds): LabyrinthBattleTeamRecommendation {
        val members = ids.map { id -> LabyrinthRecommendedTeamMember(characterId = id, displayName = "角色$id") }
        return LabyrinthBattleTeamRecommendation(
            members = members,
            damageType = LabyrinthTeamDamageType.PHYSICAL,
            score = 1_000.0,
            vanguard = members.first(),
            survivalAnchor = null,
            reasons = emptyList(),
            defenseMarkStacks = 0,
            targetCount = 1,
        )
    }

    /** 按真实顺序喂一帧编组页：先过特别装备闸门（本用例关闭），再走既有编组/开战路径。 */
    private fun feedFrame(session: Any, result: LabyrinthEntryFrameResult, now: Long): String? {
        resetFrameTrace(session)
        releaseActionLatch(session)
        if (invoke(session, "handleSpecialEquipmentFlowFrame", SESSION_ID, result, now) as Boolean) {
            return frameActionLabel(session)
        }
        invoke(session, "handleBattleTeamSelectionExecution", SESSION_ID, result, 1920, 1080, now)
        return frameActionLabel(session)
    }

    private fun bossTeamFrame(
        bossTeamIndex: Int,
        withStartButton: Boolean = false,
    ): LabyrinthEntryFrameResult = LabyrinthEntryFrameResult(
        observation = LabyrinthEntryPageObservation(
            LabyrinthEntryPageState.BATTLE_TEAM_SELECTION,
            1.0,
            emptyMap(),
            LabyrinthAnchorScores(emptyMap()),
        ),
        matchedFeatures = emptyList(),
        elapsedMillis = 0,
        anchorMatches = if (withStartButton) {
            mapOf(EntryAnchorId.BATTLE_TEAM_START_BUTTON to EntryAnchorMatch(0.92, startButtonRect))
        } else {
            emptyMap()
        },
        battleTeamSelection = LabyrinthBattleTeamObservation(
            currentFilter = LabyrinthBattleElementFilter.ALL,
            filters = emptyList(),
            visibleCharacters = emptyList(),
            selectedCharacters = teamIds.mapIndexed { index, id ->
                LabyrinthBattleCharacterMatch(
                    slotId = "selected_$index",
                    characterId = id,
                    displayName = "角色${index + 1}",
                    iconVariant = null,
                    confidence = 0.95,
                    screenRect = EntryPixelRect(200 + index * 310, 940, 260, 120),
                    selected = true,
                )
            },
            scrollbar = LabyrinthBattleScrollbarObservation(
                EntryPixelRect(1825, 377, 28, 349),
                null,
                false,
                false,
                0.0,
            ),
            recognitionState = LabyrinthBattleTeamRecognitionState.STABLE,
            bossTeamIndex = bossTeamIndex,
            bossTeamTabs = bossTeamTabs,
        ),
    )

    // ---- 观测 ----

    private fun pendingAction(session: Any): Any? = field(session, "pendingBattleTeamAction").get(session)

    private fun pendingSignature(session: Any): String? =
        pendingAction(session)?.let { pending -> field(pending, "signature").get(pending) as String? }

    private fun unconfirmedAttempts(session: Any): Int =
        field(session, "battleTeamActionUnconfirmedAttempts").get(session) as Int

    @Suppress("UNCHECKED_CAST")
    private fun setState(
        session: Any,
        update: (LabyrinthEntryRecognitionSessionState) -> LabyrinthEntryRecognitionSessionState,
    ) {
        val stateFlow = field(session, "_state").get(session)
            as MutableStateFlow<LabyrinthEntryRecognitionSessionState>
        stateFlow.value = update(stateFlow.value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun resetFrameTrace(session: Any) {
        val stateFlow = field(session, "_state").get(session)
            as MutableStateFlow<LabyrinthEntryRecognitionSessionState>
        stateFlow.value = stateFlow.value.copy(frameTrace = LabyrinthFrameTrace())
    }

    @Suppress("UNCHECKED_CAST")
    private fun frameActionLabel(session: Any): String? {
        val stateFlow = field(session, "_state").get(session)
            as MutableStateFlow<LabyrinthEntryRecognitionSessionState>
        return stateFlow.value.frameTrace.actionLabel
    }

    /** 放掉单动作锁（真实运行里由动作协程的 finally 释放）。 */
    private fun releaseActionLatch(session: Any) {
        setField(session, "actionInFlight", AtomicBoolean(false))
    }

    // ---- 反射脚手架 ----

    private fun field(target: Any?, name: String) =
        target!!.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private fun setField(target: Any?, name: String, value: Any?) = field(target, name).set(target, value)

    /**
     * 反射调用：按名字与参数个数找方法，并按目标形参类型适配（`AutomationSessionId` 是 value class，
     * 编译后既可能被拆成 `long`、方法名也可能被加后缀改写，两种形态都支持）。
     */
    private fun invoke(session: Any, name: String, vararg arguments: Any?): Any? {
        val all = session.javaClass.declaredMethods.filter { it.name.startsWith(name) }
        val method = all.firstOrNull {
            (it.name == name || it.name.startsWith("$name-")) && it.parameterCount == arguments.size
        }
        requireNotNull(method) {
            "no $name/${arguments.size} on ${session.javaClass.simpleName}; " +
                all.joinToString { "${it.name}(${it.parameterTypes.joinToString(",") { t -> t.simpleName }})" }
        }
        method.isAccessible = true
        val adapted = method.parameterTypes.mapIndexed { index, type ->
            val argument = arguments[index]
            when {
                !type.isPrimitive -> argument
                type.name == "long" -> (argument as? AutomationSessionId)?.value ?: (argument as Number).toLong()
                type.name == "int" -> (argument as Number).toInt()
                else -> argument
            }
        }.toTypedArray()
        return method.invoke(session, *adapted)
    }

    private companion object {
        val SESSION_ID = AutomationSessionId(7L)
    }
}
