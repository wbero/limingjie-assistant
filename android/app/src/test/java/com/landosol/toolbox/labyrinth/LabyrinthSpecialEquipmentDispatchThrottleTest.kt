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
import com.landosol.toolbox.labyrinth.vision.LabyrinthSpecialEquipmentObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthSpecialEquipmentStage
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「特别装备」点击与会话节流/单动作锁的关系：**被挡下的点击不得算作已点击**。
 *
 * 不变量：决策里带 `tap` 只表示「这一刻该点哪个按钮」，`specialEquipmentFlowStage` 的推进表示
 * 「这一步的动作已经派发过」。会话动作间隔（`BATTLE_TEAM_ACTION_INTERVAL_MILLIS = 1200`）与单动作锁
 * 都会把派发挡在 `dispatchSpecialEquipmentAction` 之前，此时若提交 stage，下一帧的决策就会从下一个
 * 状态继续等一个永远不会出现的画面（入口、「自动装备」、弹窗2「确认」、「装备确定」都是这样丢的）。
 * 所以 stage 只在「本帧没有待派发的点击」或「待派发的点击确实派发成功」之后才前进；被挡下的帧保持
 * 原 stage 与步进起点，间隔到期后同一决策重新算出来补发**一次**。超限逃生（点「取消」）是唯一的
 * 结束型带点击决策：结束标记必须先落下，点击本身记成待办等窗口内补发。
 *
 * 用例只驱动真实会话的处理器（`handleSpecialEquipmentFlowFrame` 与既有的编组页执行入口），断言落在
 * 「这一帧到底有没有派发动作」（`frameTrace.actionLabel`）与 stage 上。
 */
class LabyrinthSpecialEquipmentDispatchThrottleTest {
    // 与标定一致的几何，只用来断言「点到了哪个矩形」与给出开战按钮。
    private val entryRect = EntryPixelRect(1180, 858, 97, 97)
    private val mainAutoRect = EntryPixelRect(995, 922, 387, 71)
    private val equipConfirmRect = EntryPixelRect(1428, 917, 396, 81)
    private val mainCancelRect = EntryPixelRect(101, 922, 387, 71)
    private val autoConfirmRect = EntryPixelRect(982, 917, 396, 91)
    private val startButtonRect = EntryPixelRect(1565, 850, 270, 110)

    /** 推荐阵容、编组计划与画面上当前队伍三者一致的五人队伍。 */
    private val teamIds = listOf("r0", "r1", "r2", "r3", "r4")

    /** 一个「决策要求点击、但动作间隔还没到」的现场。 */
    private data class ThrottledTapCase(
        val stage: LabyrinthSpecialEquipmentFlowStage,
        val observation: LabyrinthSpecialEquipmentObservation,
        val label: String,
        val nextStage: LabyrinthSpecialEquipmentFlowStage,
    )

    // ---- 入口：被间隔挡下的那一帧不得宣布「已点过」 ----

    @Test
    fun `an entry tap blocked by the session interval is not counted as clicked and is dispatched once past it`() {
        val session = session(LabyrinthCombatKind.NORMAL, settingsFor(LabyrinthCombatKind.NORMAL))
        // 上一次会话点击在 1000：编组页这一帧只差 600ms，入口点击会被会话节流挡下。
        setLastSessionActionAt(session, 1_000L)

        // t=1600：决策要求点入口，但动作间隔未到 ⇒ 不派发，而且 stage 必须停在 AWAIT_ENTRY。
        assertTrue(flowFrame(session, teamFrame(teamPageObservation()), 1_600L))
        assertNull("a throttled tap must not be dispatched", frameActionLabel(session))
        assertEquals(
            "entering AWAIT_MAIN_DIALOG without ever clicking is the defect this pins",
            LabyrinthSpecialEquipmentFlowStage.AWAIT_ENTRY,
            flowStage(session),
        )
        assertEquals(0, stepAttempts(session))
        assertEquals("no click ⇒ the shared throttle stamp must not move", 1_000L, lastSessionActionAt(session))
        assertTrue(
            "message=${session.state.value.message}",
            session.state.value.message.orEmpty().contains("等待后补发"),
        )

        // t=2300：间隔到期（1300ms ≥ 1200）⇒ 同一条决策补发，动作正是入口点击。
        assertTrue(flowFrame(session, teamFrame(teamPageObservation()), 2_300L))
        assertEquals("特别装备：点击入口「特别装备」", frameActionLabel(session))
        assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_MAIN_DIALOG, flowStage(session))
        assertEquals(1, stepAttempts(session))
        assertEquals(2_300L, lastSessionActionAt(session))

        // 补发**只补一次**：下一帧不得再点同一个入口（stage 已经前进了）。
        releaseActionLatch(session)
        assertTrue(flowFrame(session, teamFrame(teamPageObservation()), 2_400L))
        assertNull("the entry must not be re-clicked after it was dispatched", frameActionLabel(session))
        assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_MAIN_DIALOG, flowStage(session))
        assertEquals(1, stepAttempts(session))
        assertEquals(2_300L, lastSessionActionAt(session))
    }

    @Test
    fun `an entry tap blocked by the action latch is not counted as clicked and is dispatched once the latch frees`() {
        val session = session(LabyrinthCombatKind.NORMAL, settingsFor(LabyrinthCombatKind.NORMAL))
        // 单动作锁被占：派发会走不到执行器，这一下同样不算点击。
        holdActionLatch(session)

        assertTrue(flowFrame(session, teamFrame(teamPageObservation()), 1_000L))
        assertNull("a latch-blocked tap must not be dispatched", frameActionLabel(session))
        assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_ENTRY, flowStage(session))
        assertEquals(0, stepAttempts(session))
        assertEquals(Long.MIN_VALUE, lastSessionActionAt(session))

        // 锁释放后的下一帧：入口点击照常派发，而且只派发一次。
        releaseActionLatch(session)
        assertTrue(flowFrame(session, teamFrame(teamPageObservation()), 2_200L))
        assertEquals("特别装备：点击入口「特别装备」", frameActionLabel(session))
        assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_MAIN_DIALOG, flowStage(session))
        assertEquals(1, stepAttempts(session))
        assertEquals(2_200L, lastSessionActionAt(session))
    }

    // ---- 弹窗里的两个按钮：同一条提交规则 ----

    @Test
    fun `the auto-equip and popup-confirm taps are withheld until the session interval elapses`() {
        // 「自动装备」与弹窗2「确认」走同一条「派发成功才提交 stage」的规则，这里逐个按钮钉一遍。
        val cases = listOf(
            ThrottledTapCase(
                stage = LabyrinthSpecialEquipmentFlowStage.AWAIT_MAIN_DIALOG,
                observation = mainDialogObservation(enabled = false),
                label = "特别装备：点击「自动装备」",
                nextStage = LabyrinthSpecialEquipmentFlowStage.AWAIT_AUTO_DIALOG,
            ),
            ThrottledTapCase(
                stage = LabyrinthSpecialEquipmentFlowStage.AWAIT_AUTO_DIALOG,
                observation = autoDialogObservation(),
                label = "特别装备：点击弹窗2「确认」",
                nextStage = LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG,
            ),
        )
        for (case in cases) {
            val session = session(LabyrinthCombatKind.NORMAL, settingsFor(LabyrinthCombatKind.NORMAL))
            setFlowStage(session, case.stage)
            setField(session, "specialEquipmentStepStartedAt", 1_000L)
            setLastSessionActionAt(session, 1_000L)

            // t=1600：决策要求点击，但动作间隔未到 ⇒ 不派发、stage 不动。
            assertTrue(flowFrame(session, teamFrame(case.observation), 1_600L))
            assertNull("${case.stage} must not dispatch while throttled", frameActionLabel(session))
            assertEquals("${case.stage} must not advance without a click", case.stage, flowStage(session))
            assertEquals(0, stepAttempts(session))
            assertTrue(
                "message=${session.state.value.message}",
                session.state.value.message.orEmpty().contains("等待后补发"),
            )

            // t=2300：间隔到期 ⇒ 补发同一个按钮的动作，只一次。
            assertTrue(flowFrame(session, teamFrame(case.observation), 2_300L))
            assertEquals(case.label, frameActionLabel(session))
            assertEquals(case.nextStage, flowStage(session))
            assertEquals(1, stepAttempts(session))
            assertEquals(2_300L, lastSessionActionAt(session))
        }
    }

    // ---- 「装备确定」：两帧稳定的第二帧被挡下时也不得宣布「已点过」 ----

    @Test
    fun `an equip-confirm tap blocked by the session interval keeps the stage and is dispatched once past it`() {
        val session = session(LabyrinthCombatKind.NORMAL, settingsFor(LabyrinthCombatKind.NORMAL))
        setFlowStage(session, LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG)
        setField(session, "specialEquipmentStepStartedAt", 1_000L)
        setLastSessionActionAt(session, 1_000L)
        val dialog = mainDialogObservation(enabled = true)

        // t=1500：第一帧只推进「连续两帧」证据，本来就不下结论。
        assertTrue(flowFrame(session, teamFrame(dialog), 1_500L))
        assertNull(frameActionLabel(session))
        assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG, flowStage(session))

        // t=2000：第二帧证据已经稳定（要求点「装备确定」），但只差 200ms 到 1200ms 间隔 ⇒ 不派发。
        assertTrue(flowFrame(session, teamFrame(dialog), 2_000L))
        assertNull("the stable second frame is still throttled", frameActionLabel(session))
        assertEquals(
            "AWAIT_ACK_EQUIP without ever clicking 装备确定 is the defect this pins",
            LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG,
            flowStage(session),
        )
        assertEquals(0, stepAttempts(session))
        assertTrue(
            "message=${session.state.value.message}",
            session.state.value.message.orEmpty().contains("等待后补发"),
        )

        // t=2500：间隔到期 ⇒ 补发「装备确定」，只一次。
        assertTrue(flowFrame(session, teamFrame(dialog), 2_500L))
        assertEquals("特别装备：点击「装备确定」", frameActionLabel(session))
        assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_ACK_EQUIP, flowStage(session))
        assertEquals(1, stepAttempts(session))
        assertEquals(2_500L, lastSessionActionAt(session))

        releaseActionLatch(session)
        assertTrue(flowFrame(session, teamFrame(dialog), 3_800L))
        assertNull("装备确定 must not be re-dispatched while the popup stays open", frameActionLabel(session))
        assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_ACK_EQUIP, flowStage(session))
        assertEquals(2_500L, lastSessionActionAt(session))
    }

    // ---- 超限逃生（点「取消」）：结束标记照落，点击记待办补发一次 ----

    @Test
    fun `an over-limit escape tap blocked by the session interval is dispatched exactly once past it`() {
        val session = session(LabyrinthCombatKind.NORMAL, settingsFor(LabyrinthCombatKind.NORMAL))
        setFlowStage(session, LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG)
        setField(session, "specialEquipmentStepAttempts", MAX_SPECIAL_EQUIPMENT_STEP_ATTEMPTS)
        setLastSessionActionAt(session, 1_000L)
        val dialog = mainDialogObservation(enabled = true)

        // t=1600：超限决策要求点「取消」，但动作间隔未到 ⇒ 不派发；结束判定仍要落下，
        // 点击本身记成待办（否则下一帧的 DONE 决策不会再带上它）。
        assertTrue(flowFrame(session, teamFrame(dialog), 1_600L))
        assertNull("the escape tap must wait for the interval", frameActionLabel(session))
        assertEquals(LabyrinthSpecialEquipmentFlowStage.DONE, flowStage(session))
        assertEquals(LabyrinthSpecialEquipmentTap.CANCEL, pendingEscape(session))
        assertTrue(
            "the over-limit diagnostic must be kept: ${diagnosticOf(session)}",
            diagnosticOf(session).orEmpty().contains("超限"),
        )

        // t=2300：间隔到期 ⇒ 待办里的「取消」真的点出去，只点这一次。
        assertTrue(flowFrame(session, teamFrame(dialog), 2_300L))
        assertEquals("特别装备：点击「取消」", frameActionLabel(session))
        assertEquals(LabyrinthSpecialEquipmentFlowStage.DONE, flowStage(session))
        assertNull("the escape must be cleared once dispatched", pendingEscape(session))
        assertEquals(2_300L, lastSessionActionAt(session))

        // 同一弹窗仍在、间隔未到：不得重复点「取消」。
        assertTrue(flowFrame(session, teamFrame(dialog), 2_600L))
        assertNull("the escape must not be repeated", frameActionLabel(session))
        assertEquals(2_300L, lastSessionActionAt(session))
    }

    // ---- 被挡期间仍然有界：3 次 / 8s 两个既有上限照常收尾 ----

    @Test
    fun `a tap that can never be dispatched still ends the round at the existing step timeout`() {
        val session = session(LabyrinthCombatKind.NORMAL, settingsFor(LabyrinthCombatKind.NORMAL))
        setFlowStage(session, LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG)
        // 这一步从 1000 开始计时；单动作锁一直被占 ⇒ 每一次派发都会被挡在派发之前。
        setField(session, "specialEquipmentStepStartedAt", 1_000L)
        holdActionLatch(session)
        val dialog = mainDialogObservation(enabled = true)

        // 证据稳定的帧也点不出去：stage 不前进、次数只在真的派发时增加（不会把被拦算成尝试）。
        for (now in listOf(2_000L, 3_000L)) {
            assertTrue(flowFrame(session, teamFrame(dialog), now))
            assertNull(frameActionLabel(session))
            assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG, flowStage(session))
            assertEquals(0, stepAttempts(session))
        }

        // t=9000：原有 8000ms 步进上限到点 ⇒ 本轮照样结束（不会因为「等补发」而无限停在同一步）。
        assertTrue(flowFrame(session, teamFrame(dialog), 9_000L))
        assertEquals(LabyrinthSpecialEquipmentFlowStage.DONE, flowStage(session))
        assertNull("the escape tap is still blocked by the held latch", frameActionLabel(session))
        assertEquals(LabyrinthSpecialEquipmentTap.CANCEL, pendingEscape(session))

        // 锁释放 ⇒ 窗口内把逃生「取消」补发出去，只一次。
        releaseActionLatch(session)
        assertTrue(flowFrame(session, teamFrame(dialog), 9_600L))
        assertEquals("特别装备：点击「取消」", frameActionLabel(session))
        assertNull(pendingEscape(session))
        releaseActionLatch(session)
        assertTrue(flowFrame(session, teamFrame(dialog), 10_800L))
        assertNull("the escape must not be repeated", frameActionLabel(session))
        assertEquals(9_600L, lastSessionActionAt(session))
    }

    // ---- 正常时序：0–3.2 的点击序列与顺序不变 ----

    @Test
    fun `the whole 0-3-2 walk keeps its clicks and their order when nothing is throttled`() {
        val session = readySession(LabyrinthCombatKind.NORMAL, settingsFor(LabyrinthCombatKind.NORMAL))
        val dispatched = mutableListOf<String?>()
        var now = 1_000L

        dispatched += feedFrame(session, teamFrame(teamPageObservation()), now)
        assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_MAIN_DIALOG, flowStage(session))
        assertEquals(1, stepAttempts(session))
        now += 2_000L

        dispatched += feedFrame(session, teamFrame(mainDialogObservation(enabled = false)), now)
        assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_AUTO_DIALOG, flowStage(session))
        now += 2_000L

        dispatched += feedFrame(session, teamFrame(autoDialogObservation()), now)
        assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG, flowStage(session))
        now += 2_000L

        // 回到弹窗1：首帧只推进证据，第二帧才点「装备确定」。
        dispatched += feedFrame(session, teamFrame(mainDialogObservation(enabled = true)), now)
        now += 1_500L
        dispatched += feedFrame(session, teamFrame(mainDialogObservation(enabled = true)), now)
        assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_ACK_EQUIP, flowStage(session))
        now += 2_000L

        dispatched += feedFrame(session, teamFrame(teamPageObservation()), now)
        assertEquals(LabyrinthSpecialEquipmentFlowStage.DONE, flowStage(session))

        assertEquals(
            listOf(
                "特别装备：点击入口「特别装备」",
                "特别装备：点击「自动装备」",
                "特别装备：点击弹窗2「确认」",
                null,
                "特别装备：点击「装备确定」",
                null,
            ),
            dispatched,
        )
    }

    // ---- 反向用例：同一任务内两场同编组的普通战斗仍然触发跳过 ----

    @Test
    fun `two normal battles with the same team still skip the equipment round inside one run`() {
        val session = readySession(LabyrinthCombatKind.NORMAL, settingsFor(LabyrinthCombatKind.NORMAL))
        // 第一场：入口不可信 ⇒ 零点击结束本轮，同一帧放行开战并记下本场编组（生产路径写入）。
        assertEquals(
            "编组完成，开始战斗",
            feedFrame(
                session,
                teamFrame(
                    teamPageObservation(entry = null),
                    selectedCharacterIds = teamIds,
                    withStartButton = true,
                ),
                1_000L,
            ),
        )
        val signature = normalTeamSignature(session)
        assertEquals(labyrinthBattleTeamSignature(teamIds), signature)

        // 这一场打完：连续两帧落在别的页面才算稳定离开，本轮标记清零。
        assertFalse(flowFrame(session, teamFrame(null, pageState = LabyrinthEntryPageState.NODE_SELECTION), 2_000L))
        assertFalse(flowFrame(session, teamFrame(null, pageState = LabyrinthEntryPageState.NODE_SELECTION), 2_200L))
        assertEquals(LabyrinthSpecialEquipmentFlowStage.INACTIVE, flowStage(session))

        // 第二场同编组：仍然跳过（跨普通战斗的记忆不得被会话/账号边界重置提前清掉）。
        val actionsBefore = lastSessionActionAt(session)
        assertNull(
            feedFrame(session, teamFrame(teamPageObservation(), selectedCharacterIds = teamIds), 3_000L),
        )
        assertEquals(LabyrinthSpecialEquipmentFlowStage.INACTIVE, flowStage(session))
        assertEquals(0, stepAttempts(session))
        assertEquals(actionsBefore, lastSessionActionAt(session))
        assertTrue(
            "message=${session.state.value.message}",
            session.state.value.message.orEmpty().contains("跳过本次穿戴"),
        )
        assertEquals(signature, normalTeamSignature(session))
    }

    // ---- 会话级脚手架 ----

    private fun session(
        kind: LabyrinthCombatKind?,
        settings: LabyrinthStrategySettings?,
    ): LabyrinthEntryRecognitionSession {
        val manager = AutomationSessionManager()
        val created = LabyrinthEntryRecognitionSession(
            sessionManager = manager,
            processorFactory = { { error("测试不投喂帧") } },
            actionExecutor = SessionBoundActionExecutor(manager) { AutomationBackendResult.Completed },
            actionsAvailable = { true },
        )
        setField(created, "activeSessionId", SESSION_ID)
        setField(created, "specialEquipmentSettings", settings)
        if (kind != null) setField(created, "combatContext", LabyrinthCombatContext(kind))
        // 「武装」要求推荐阵容已就位；默认给一个已就位的计划。
        setBattleTeamPlan(created, teamPlan(alreadyCorrect = 5, teamReady = true))
        return created
    }

    /** 推荐、计划与当前队伍一致：既有编组/开战路径会真的产出执行步。 */
    private fun readySession(
        kind: LabyrinthCombatKind,
        settings: LabyrinthStrategySettings?,
        ids: List<String> = teamIds,
    ): LabyrinthEntryRecognitionSession = session(kind, settings).also { created ->
        setBattleTeamPlan(created, readyPlan(ids))
        setBattleTeamRecommendation(created, readyRecommendation(ids))
    }

    private fun teamPlan(
        alreadyCorrect: Int = 5,
        needSelect: Int = 0,
        needDeselect: Int = 0,
        teamReady: Boolean = true,
    ): LabyrinthBattleTeamSelectionPlan = LabyrinthBattleTeamSelectionPlan(
        sessionId = SESSION_ID,
        recommendedIds = List(alreadyCorrect + needSelect) { "r$it" },
        recommendedNames = emptyMap(),
        currentlySelectedIds = List(alreadyCorrect + needDeselect) { "c$it" },
        currentlySelectedNames = emptyMap(),
        alreadyCorrectIds = List(alreadyCorrect) { "r$it" },
        needSelectIds = List(needSelect) { "r${alreadyCorrect + it}" },
        needDeselectIds = List(needDeselect) { "c${alreadyCorrect + it}" },
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
        readyToExecute = teamReady,
        blockedReason = null,
        teamReady = teamReady,
        minimumCharacterConfidence = 0.0,
    )

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

    /** 按真实顺序喂一帧编组页：先过特别装备闸门，被交还时再走既有编组/开战路径。 */
    private fun feedFrame(session: Any, result: LabyrinthEntryFrameResult, now: Long): String? {
        // 真实运行里单动作锁由动作协程释放；测试里显式放锁，让每一次派发都可确定复现。
        releaseActionLatch(session)
        if (flowFrame(session, result, now)) return frameActionLabel(session)
        invoke(session, "handleBattleTeamSelectionExecution", SESSION_ID, result, 1920, 1080, now)
        return frameActionLabel(session)
    }

    private fun settingsFor(vararg kinds: LabyrinthCombatKind): LabyrinthStrategySettings =
        LabyrinthStrategySettings(
            specialEquipmentMode = LabyrinthSpecialEquipmentMode.AUTO_EQUIP,
            specialEquipmentKinds = kinds.toSet(),
        )

    private fun teamPageObservation(entry: EntryPixelRect? = entryRect): LabyrinthSpecialEquipmentObservation =
        LabyrinthSpecialEquipmentObservation(
            stage = null,
            confidence = if (entry != null) 0.90 else 0.0,
            entryButtonRect = entry,
            entryButtonScore = if (entry != null) 0.90 else 0.31,
            entryButtonMissReason = if (entry == null) MISS_REASON else null,
        )

    private fun mainDialogObservation(
        enabled: Boolean?,
        auto: EntryPixelRect? = mainAutoRect,
        cancel: EntryPixelRect? = mainCancelRect,
    ): LabyrinthSpecialEquipmentObservation = LabyrinthSpecialEquipmentObservation(
        stage = LabyrinthSpecialEquipmentStage.MAIN_DIALOG,
        confidence = 1.0,
        autoButtonRect = auto,
        confirmButtonRect = equipConfirmRect,
        cancelButtonRect = cancel,
        equipConfirmEnabled = enabled,
        equipConfirmLuminance = when (enabled) {
            true -> 155.2
            false -> 95.5
            null -> null
        },
    )

    private fun autoDialogObservation(): LabyrinthSpecialEquipmentObservation =
        LabyrinthSpecialEquipmentObservation(
            stage = LabyrinthSpecialEquipmentStage.AUTO_DIALOG,
            confidence = 1.0,
            confirmButtonRect = autoConfirmRect,
            cancelButtonRect = EntryPixelRect(548, 934, 387, 67),
        )

    private fun teamFrame(
        specialEquipment: LabyrinthSpecialEquipmentObservation?,
        pageState: LabyrinthEntryPageState = LabyrinthEntryPageState.BATTLE_TEAM_SELECTION,
        withStartButton: Boolean = false,
        selectedCharacterIds: List<String?> = emptyList(),
    ): LabyrinthEntryFrameResult = LabyrinthEntryFrameResult(
        observation = LabyrinthEntryPageObservation(
            pageState,
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
            selectedCharacters = selectedCharacterIds.mapIndexed { index, characterId ->
                LabyrinthBattleCharacterMatch(
                    slotId = "selected_$index",
                    characterId = characterId,
                    displayName = characterId?.let { "角色${index + 1}" },
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
            bossTeamIndex = null,
            bossTeamTabs = emptyList(),
        ),
        specialEquipment = specialEquipment,
    )

    // ---- 观测 ----

    private fun flowStage(session: Any): LabyrinthSpecialEquipmentFlowStage =
        field(session, "specialEquipmentFlowStage").get(session) as LabyrinthSpecialEquipmentFlowStage

    private fun setFlowStage(session: Any, stage: LabyrinthSpecialEquipmentFlowStage) {
        setField(session, "specialEquipmentFlowStage", stage)
    }

    private fun stepAttempts(session: Any): Int =
        field(session, "specialEquipmentStepAttempts").get(session) as Int

    private fun pendingEscape(session: Any): LabyrinthSpecialEquipmentTap? =
        field(session, "specialEquipmentPendingEscape").get(session) as LabyrinthSpecialEquipmentTap?

    private fun lastSessionActionAt(session: Any): Long =
        field(session, "lastSessionActionAt").get(session) as Long

    private fun setLastSessionActionAt(session: Any, value: Long) = setField(session, "lastSessionActionAt", value)

    private fun normalTeamSignature(session: Any): String? =
        field(session, "lastNormalBattleTeamSignature").get(session) as String?

    private fun diagnosticOf(session: Any): String? =
        field(session, "specialEquipmentDiagnostic").get(session) as String?

    @Suppress("UNCHECKED_CAST")
    private fun frameActionLabel(session: Any): String? {
        val stateFlow = field(session, "_state").get(session)
            as MutableStateFlow<LabyrinthEntryRecognitionSessionState>
        return stateFlow.value.frameTrace.actionLabel
    }

    /** `frameTrace` 在真实路径里每帧重建，这里也每帧清一次（断言只看本帧）。 */
    @Suppress("UNCHECKED_CAST")
    private fun resetFrameTrace(session: Any) {
        val stateFlow = field(session, "_state").get(session)
            as MutableStateFlow<LabyrinthEntryRecognitionSessionState>
        stateFlow.value = stateFlow.value.copy(frameTrace = LabyrinthFrameTrace())
    }

    @Suppress("UNCHECKED_CAST")
    private fun setBattleTeamPlan(session: Any, plan: LabyrinthBattleTeamSelectionPlan?) {
        val stateFlow = field(session, "_state").get(session)
            as MutableStateFlow<LabyrinthEntryRecognitionSessionState>
        stateFlow.value = stateFlow.value.copy(battleTeamSelectionPlan = plan)
    }

    @Suppress("UNCHECKED_CAST")
    private fun setBattleTeamRecommendation(session: Any, recommendation: LabyrinthBattleTeamRecommendation?) {
        val stateFlow = field(session, "_state").get(session)
            as MutableStateFlow<LabyrinthEntryRecognitionSessionState>
        stateFlow.value = stateFlow.value.copy(battleTeamRecommendation = recommendation)
    }

    /**
     * 放掉单动作锁（`actionInFlight`）。
     *
     * 真实运行时由动作协程的 finally 释放；单元测试里动作只会得到 `StaleSession`，释放时机不确定，
     * 所以需要连续两次派发的用例显式放锁，断言才是确定性的。
     */
    private fun releaseActionLatch(session: Any) {
        setField(session, "actionInFlight", AtomicBoolean(false))
    }

    /** 模拟「单动作锁一直被占」：派发会走不到执行器，因此不算一次点击。 */
    private fun holdActionLatch(session: Any) {
        setField(session, "actionInFlight", AtomicBoolean(true))
    }

    // ---- 反射脚手架 ----

    private fun field(target: Any?, name: String) =
        target!!.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private fun setField(target: Any?, name: String, value: Any?) = field(target, name).set(target, value)

    /**
     * 调私有的 `handleSpecialEquipmentFlowFrame`，返回它是否接管了这一帧。
     *
     * 每帧先清 `frameTrace`：真实路径里它每帧重建，断言只允许看**本帧**有没有派发动作。
     */
    private fun flowFrame(session: Any, result: LabyrinthEntryFrameResult, now: Long): Boolean {
        resetFrameTrace(session)
        return invoke(session, "handleSpecialEquipmentFlowFrame", SESSION_ID, result, now) as Boolean
    }

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
        const val MISS_REASON = "special-equipment-entry-button-not-found"
        val SESSION_ID = AutomationSessionId(7L)
    }
}
