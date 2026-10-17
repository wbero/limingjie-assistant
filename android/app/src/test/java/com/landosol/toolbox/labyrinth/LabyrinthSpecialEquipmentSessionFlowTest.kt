package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.AutomationBackendResult
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.automation.AutomationSessionManager
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.automation.session.SessionBlockKind
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
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicDetailObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthSpecialEquipmentObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthSpecialEquipmentStage
import com.landosol.toolbox.labyrinth.vision.labyrinthRelicDetailHoldsFrame
import com.landosol.toolbox.labyrinth.vision.labyrinthSpecialEquipmentOwnsFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「特别装备」编组页执行编排：纯状态机（判定）＋ 会话接线（武装/离开、闸门、按关卡类型）。
 *
 * 纯状态机用「观测 → 决策」直接驱动，断言到**具体按钮身份与该按钮的标定矩形**；会话层用反射调用
 * 私有的 [LabyrinthEntryRecognitionSession.handleSpecialEquipmentFlowFrame] 与
 * `handleBattleTeamSelectionExecution`，断言「这一帧被谁接管、最终有没有派发」。
 *
 * 闸门那两条对照用例（同一帧、只有本轮 stage 不同）刻意做成**非空断言**：没有闸门时同一帧会产出
 * Boss 单队的 START_BATTLE 执行步，所以「没有产出」是能失败的真断言，而不是因为缺前置条件而真空通过。
 */
class LabyrinthSpecialEquipmentSessionFlowTest {
    // 与标定一致的几何（弹窗1/弹窗2/入口），这里只用来断言「点到了哪个矩形」。
    private val entryRect = EntryPixelRect(1180, 858, 97, 97)
    private val mainAutoRect = EntryPixelRect(995, 922, 387, 71)
    private val equipConfirmRect = EntryPixelRect(1428, 917, 396, 81)
    private val mainCancelRect = EntryPixelRect(101, 922, 387, 71)
    private val autoConfirmRect = EntryPixelRect(982, 917, 396, 91)
    private val startButtonRect = EntryPixelRect(1565, 850, 270, 110)

    /** 一个「推荐阵容已经就位」的五人队伍：推荐、编组计划与画面上的当前队伍都用它。 */
    private val teamIds = listOf("r0", "r1", "r2", "r3", "r4")

    /** 另一支五人队伍：用来当「上一场战斗」的编组记忆。 */
    private val previousTeamIds = listOf("a0", "a1", "a2", "a3", "a4")

    /** Boss 编组页的三个队伍页签矩形：既有 NEXT_BOSS_TEAM 步从这里取点击位置。 */
    private val bossTeamTabs = listOf(
        EntryPixelRect(60, 120, 150, 90),
        EntryPixelRect(240, 120, 150, 90),
        EntryPixelRect(420, 120, 150, 90),
    )

    /** 「编组未变则跳过」的一组保守条件。 */
    private data class SkipCase(
        val name: String,
        val previousSignature: String?,
        val currentTeam: List<String?>,
    )

    // ---- 观测构造（与 vision 层的字段语义一一对应）----

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

    // ---- 纯状态机：三条分支与点击目标 ----

    @Test
    fun `the whole 3-2 walk targets exactly the four calibrated buttons in order`() {
        val taps = mutableListOf<LabyrinthSpecialEquipmentTap>()
        var stage = LabyrinthSpecialEquipmentFlowStage.AWAIT_ENTRY

        fun step(observation: LabyrinthSpecialEquipmentObservation?): LabyrinthSpecialEquipmentDecision {
            val decision = labyrinthSpecialEquipmentDecision(
                stage = stage,
                observation = observation,
                stepAttempts = 0,
                stepElapsedMillis = 0,
                // 这一层只测决策本身：传入的是**已经跨帧稳定**的结论（会话层由证据计数给出）。
                equipConfirmEnabled = observation?.equipConfirmEnabled,
            )
            stage = decision.stage
            decision.tap?.let { tap ->
                taps += tap
                // 每一步都必须能解析出一个**具体矩形**，否则上层不会点击。
                assertTrue(
                    "no rect for $tap on ${observation?.stage}",
                    observation != null && labyrinthSpecialEquipmentTapRect(tap, observation) != null,
                )
            }
            return decision
        }

        step(teamPageObservation()) // 编组页：点入口
        assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_MAIN_DIALOG, stage)
        step(mainDialogObservation(enabled = false)) // 弹窗1：点「自动装备」
        assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_AUTO_DIALOG, stage)
        step(autoDialogObservation()) // 弹窗2：点「确认」
        assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG, stage)
        val final = step(mainDialogObservation(enabled = true)) // 回到弹窗1：点「装备确定」
        assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_ACK_EQUIP, stage)
        assertFalse(final.finished)

        val done = step(teamPageObservation()) // 回到编组页：本轮结束
        assertTrue(done.finished)
        assertEquals(LabyrinthSpecialEquipmentFlowStage.DONE, stage)
        assertNull(done.tap)
        assertEquals(
            listOf(
                LabyrinthSpecialEquipmentTap.ENTRY,
                LabyrinthSpecialEquipmentTap.AUTO_BUTTON,
                LabyrinthSpecialEquipmentTap.AUTO_CONFIRM,
                LabyrinthSpecialEquipmentTap.EQUIP_CONFIRM,
            ),
            taps,
        )

        val observation = teamPageObservation()
        assertEquals(entryRect, labyrinthSpecialEquipmentTapRect(LabyrinthSpecialEquipmentTap.ENTRY, observation))
        assertEquals(
            mainAutoRect,
            labyrinthSpecialEquipmentTapRect(LabyrinthSpecialEquipmentTap.AUTO_BUTTON, mainDialogObservation(false)),
        )
        assertEquals(
            equipConfirmRect,
            labyrinthSpecialEquipmentTapRect(
                LabyrinthSpecialEquipmentTap.EQUIP_CONFIRM,
                mainDialogObservation(enabled = true),
            ),
        )
        assertEquals(
            mainCancelRect,
            labyrinthSpecialEquipmentTapRect(LabyrinthSpecialEquipmentTap.CANCEL, mainDialogObservation(false)),
        )
        assertEquals(
            autoConfirmRect,
            labyrinthSpecialEquipmentTapRect(LabyrinthSpecialEquipmentTap.AUTO_CONFIRM, autoDialogObservation()),
        )
    }

    @Test
    fun `the 3-1 branch cancels for stable false evidence and never targets equip confirm`() {
        // 稳定后的结论：原始 `false` 连续两帧 → false；原始 `null`（两信号矛盾）连续两帧 → 也按
        // 不可点击（保守分支）。两者都由会话层的证据计数交给决策，这里直接喂稳定结论。
        for (raw in listOf(false, null)) {
            val observation = mainDialogObservation(raw)
            val decision = labyrinthSpecialEquipmentDecision(
                stage = LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG,
                observation = observation,
                stepAttempts = 0,
                stepElapsedMillis = 0,
                equipConfirmEnabled = false,
            )
            assertEquals("raw=$raw", LabyrinthSpecialEquipmentTap.CANCEL, decision.tap)
            assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_ACK_CANCEL, decision.stage)
            assertEquals(mainCancelRect, labyrinthSpecialEquipmentTapRect(decision.tap!!, observation))
            // 第二道锁：即便有人把决策改成 EQUIP_CONFIRM，这一层也必须拒绝给出矩形。
            assertNull(labyrinthSpecialEquipmentTapRect(LabyrinthSpecialEquipmentTap.EQUIP_CONFIRM, observation))
            // 决策文案要说明原因，且「未变更」是预期结果。
            assertTrue(decision.message.contains("取消"))
            assertTrue(decision.message.contains("连续两帧"))
        }

        // 关键：**证据还没跨帧稳定**（首帧/过渡帧）时，既不点确定也不点取消，只等下一帧。
        val notYetStable = labyrinthSpecialEquipmentDecision(
            stage = LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG,
            observation = mainDialogObservation(enabled = false),
            stepAttempts = 0,
            stepElapsedMillis = 0,
            equipConfirmEnabled = null,
        )
        assertNull("a transition frame must not decide anything", notYetStable.tap)
        assertFalse(notYetStable.finished)
        assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG, notYetStable.stage)
        assertTrue(notYetStable.message.contains("跨帧稳定"))

        // 取消矩形不可信时只等，不点任何候选位置，更不会退化成点「装备确定」。
        val noCancel = mainDialogObservation(enabled = false, cancel = null)
        val waiting = labyrinthSpecialEquipmentDecision(
            stage = LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG,
            observation = noCancel,
            stepAttempts = 0,
            stepElapsedMillis = 0,
            equipConfirmEnabled = false,
        )
        assertNull(waiting.tap)
        assertFalse(waiting.finished)
        assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG, waiting.stage)
    }

    @Test
    fun `the equip evidence streak only confirms after two identical frames`() {
        // 单帧（含过渡帧）一律不下结论。
        var streak = labyrinthSpecialEquipmentEquipEvidenceStreak(
            observation = mainDialogObservation(enabled = false),
        )
        assertEquals(1, streak.frames)
        assertNull(streak.confirmedEnabled)

        // 连续第二帧同类 ⇒ 才给出「不可点击」。
        streak = labyrinthSpecialEquipmentEquipEvidenceStreak(streak, mainDialogObservation(enabled = false))
        assertEquals(2, streak.frames)
        assertEquals(false, streak.confirmedEnabled)

        // 弹窗2 / 无弹窗：证据清零（回到弹窗1 之后必须重判）。
        streak = labyrinthSpecialEquipmentEquipEvidenceStreak(streak, autoDialogObservation())
        assertEquals(0, streak.frames)
        assertNull(streak.confirmedEnabled)
        streak = labyrinthSpecialEquipmentEquipEvidenceStreak(streak, null)
        assertEquals(0, streak.frames)

        // 证据类别变化（过渡帧 → 可用）同样重新计数。
        streak = labyrinthSpecialEquipmentEquipEvidenceStreak(streak, mainDialogObservation(enabled = true))
        assertEquals(1, streak.frames)
        assertNull(streak.confirmedEnabled)
        streak = labyrinthSpecialEquipmentEquipEvidenceStreak(streak, mainDialogObservation(enabled = true))
        assertEquals(true, streak.confirmedEnabled)

        // 连续两帧两信号矛盾 ⇒ 按不可点击，而不是永远不下结论。
        var contradictory = labyrinthSpecialEquipmentEquipEvidenceStreak(
            observation = mainDialogObservation(enabled = null),
        )
        contradictory = labyrinthSpecialEquipmentEquipEvidenceStreak(
            contradictory,
            mainDialogObservation(enabled = null),
        )
        assertEquals(false, contradictory.confirmedEnabled)
    }

    @Test
    fun `装备确定 rect stays unreachable while the enabled flag is not exactly true`() {
        // 结构性保证：AUTO_CONFIRM 只在弹窗2 成立，EQUIP_CONFIRM 只在弹窗1 且 enabled==true 成立。
        val main = mainDialogObservation(enabled = null)
        assertNull(labyrinthSpecialEquipmentTapRect(LabyrinthSpecialEquipmentTap.AUTO_CONFIRM, main))
        assertNull(labyrinthSpecialEquipmentTapRect(LabyrinthSpecialEquipmentTap.EQUIP_CONFIRM, main))
        val auto = autoDialogObservation()
        assertNull(labyrinthSpecialEquipmentTapRect(LabyrinthSpecialEquipmentTap.EQUIP_CONFIRM, auto))
        // 弹窗1 上「确认按钮矩形」就是「装备确定」；AUTO_CONFIRM 不得把它当确认用掉。
        assertNull(labyrinthSpecialEquipmentTapRect(LabyrinthSpecialEquipmentTap.AUTO_CONFIRM, main))
        assertEquals(
            equipConfirmRect,
            labyrinthSpecialEquipmentTapRect(
                LabyrinthSpecialEquipmentTap.EQUIP_CONFIRM,
                mainDialogObservation(enabled = true),
            ),
        )
    }

    @Test
    fun `missing stage only waits and missing entry skips the round with the reason code`() {
        // 弹窗还没出现：只等，不动作。
        val waiting = labyrinthSpecialEquipmentDecision(
            stage = LabyrinthSpecialEquipmentFlowStage.AWAIT_MAIN_DIALOG,
            observation = teamPageObservation(),
            stepAttempts = 0,
            stepElapsedMillis = 0,
            equipConfirmEnabled = null,
        )
        assertNull(waiting.tap)
        assertFalse(waiting.finished)
        assertTrue(waiting.message.contains("等待"))

        // 入口找不到：零点击、本轮结束、消息带原因码 ⇒ 照常允许开战。
        val skip = labyrinthSpecialEquipmentDecision(
            stage = LabyrinthSpecialEquipmentFlowStage.AWAIT_ENTRY,
            observation = teamPageObservation(entry = null),
            stepAttempts = 0,
            stepElapsedMillis = 0,
            equipConfirmEnabled = null,
        )
        assertNull(skip.tap)
        assertTrue(skip.finished)
        assertEquals(LabyrinthSpecialEquipmentFlowStage.DONE, skip.stage)
        assertTrue(skip.message.contains(MISS_REASON))
        assertFalse(labyrinthSpecialEquipmentBlocksBattleStart(skip.stage))
    }

    @Test
    fun `every step has an attempt and a time limit with a diagnosable message`() {
        for (stage in listOf(
            LabyrinthSpecialEquipmentFlowStage.AWAIT_ENTRY,
            LabyrinthSpecialEquipmentFlowStage.AWAIT_MAIN_DIALOG,
            LabyrinthSpecialEquipmentFlowStage.AWAIT_AUTO_DIALOG,
            LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG,
            LabyrinthSpecialEquipmentFlowStage.AWAIT_ACK_EQUIP,
            LabyrinthSpecialEquipmentFlowStage.AWAIT_ACK_CANCEL,
        )) {
            val attemptsExpired = labyrinthSpecialEquipmentDecision(
                stage = stage,
                observation = mainDialogObservation(enabled = false),
                stepAttempts = MAX_SPECIAL_EQUIPMENT_STEP_ATTEMPTS,
                stepElapsedMillis = 0,
                equipConfirmEnabled = false,
            )
            assertTrue("$stage attempts", attemptsExpired.finished)
            assertEquals(LabyrinthSpecialEquipmentFlowStage.DONE, attemptsExpired.stage)
            assertTrue("$stage 次数", attemptsExpired.message.contains("超限"))
            assertTrue("$stage 步名", attemptsExpired.message.contains(stage.label))
            assertTrue("$stage 观测", attemptsExpired.message.contains("MAIN_DIALOG"))
            // 弹窗1 还开着就点「取消」退回编组页，而且**永远不是**「装备确定」。
            assertEquals(LabyrinthSpecialEquipmentTap.CANCEL, attemptsExpired.tap)

            val timedOut = labyrinthSpecialEquipmentDecision(
                stage = stage,
                observation = mainDialogObservation(enabled = true),
                stepAttempts = 0,
                stepElapsedMillis = SPECIAL_EQUIPMENT_STEP_TIMEOUT_MILLIS,
                equipConfirmEnabled = true,
            )
            assertTrue("$stage 时间", timedOut.finished)
            // 即使「装备确定」此刻是可点击的，超限路径也只允许点「取消」。
            assertEquals(LabyrinthSpecialEquipmentTap.CANCEL, timedOut.tap)
            assertFalse(labyrinthSpecialEquipmentBlocksBattleStart(timedOut.stage))
        }

        // 弹窗也点不到、退不出去时：只给消息，不点任何东西，但本轮照样结束（不能卡死）。
        val noEscape = labyrinthSpecialEquipmentDecision(
            stage = LabyrinthSpecialEquipmentFlowStage.AWAIT_ACK_CANCEL,
            observation = autoDialogObservation(),
            stepAttempts = MAX_SPECIAL_EQUIPMENT_STEP_ATTEMPTS,
            stepElapsedMillis = 0,
            equipConfirmEnabled = null,
        )
        assertTrue(noEscape.finished)
        assertNull(noEscape.tap)
    }

    @Test
    fun `the battle-start gate opens only when the round is inactive or done`() {
        assertFalse(labyrinthSpecialEquipmentBlocksBattleStart(LabyrinthSpecialEquipmentFlowStage.INACTIVE))
        assertFalse(labyrinthSpecialEquipmentBlocksBattleStart(LabyrinthSpecialEquipmentFlowStage.DONE))
        for (stage in LabyrinthSpecialEquipmentFlowStage.entries) {
            if (stage == LabyrinthSpecialEquipmentFlowStage.INACTIVE ||
                stage == LabyrinthSpecialEquipmentFlowStage.DONE
            ) {
                continue
            }
            assertTrue("$stage must block START_BATTLE", labyrinthSpecialEquipmentBlocksBattleStart(stage))
        }
    }

    // ---- 会话层：武装/离开、闸门、按关卡类型 ----

    @Test
    fun `the round arms on the team page and the popups plus UNKNOWN never count as leaving`() {
        val session = session(kind = LabyrinthCombatKind.BOSS, settings = settingsFor(LabyrinthCombatKind.BOSS))

        // 编组页稳定观测 ⇒ 武装并立刻决定点入口。
        assertTrue(flowFrame(session, teamPageFrame(teamPageObservation()), now = 1_000L))
        assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_MAIN_DIALOG, flowStage(session))
        assertEquals("特别装备：点击入口「特别装备」", session.state.value.frameTrace.actionLabel)
        assertEquals(1, stepAttempts(session))

        // 单动作锁在真实运行里由动作协程释放；测试里显式放锁，让下一次派发可确定复现。
        releaseActionLatch(session)

        // 弹窗开着、页面分类变成 UNKNOWN：不算离开，继续走（点「自动装备」）。
        assertTrue(
            flowFrame(
                session,
                teamPageFrame(mainDialogObservation(enabled = false), pageState = LabyrinthEntryPageState.UNKNOWN),
                now = 3_000L,
            ),
        )
        assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_AUTO_DIALOG, flowStage(session))
        assertEquals("特别装备：点击「自动装备」", session.state.value.frameTrace.actionLabel)

        // 真正落到别的页面：**第一帧只算疑似**（弹窗关合动画、Boss 切队、结算过渡都会插进来一两帧
        // 误分类），本轮标记不动——而且这一帧同样要**交还**（本流程在离开判定上不吃帧，
        // 否则又会绕过既有 120s 停滞看门狗）。
        assertFalse(
            flowFrame(
                session,
                teamPageFrame(null, pageState = LabyrinthEntryPageState.NODE_SELECTION),
                now = 5_000L,
            ),
        )
        assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_AUTO_DIALOG, flowStage(session))

        // 连续第二帧仍是别的页面 ⇒ 才算稳定离开：清标记（帧本来就已经交还）。
        assertFalse(
            flowFrame(
                session,
                teamPageFrame(null, pageState = LabyrinthEntryPageState.NODE_SELECTION),
                now = 5_200L,
            ),
        )
        assertEquals(LabyrinthSpecialEquipmentFlowStage.INACTIVE, flowStage(session))
    }

    @Test
    fun `a UNKNOWN frame without a dialog never leaves the round or re-runs it`() {
        val session = session(kind = LabyrinthCombatKind.BOSS, settings = settingsFor(LabyrinthCombatKind.BOSS))
        // 武装并推进到「等弹窗1」。
        assertTrue(flowFrame(session, teamPageFrame(teamPageObservation()), 1_000L))
        assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_MAIN_DIALOG, flowStage(session))
        releaseActionLatch(session)

        // 关键：UNKNOWN **且弹窗不打开**——旧的钉法用 UNKNOWN+弹窗帧，dialogOpen 会把这一支提前短路，
        // 所以钉不住「单帧误判离开」。修复前这里会把本轮标记清成 INACTIVE。
        //
        // 这些帧必须**交还**（false）：吃掉它们会同时绕过本流程的 8s/3 次
        // 上限与既有 120s 停滞看门狗，又变回无界等待。
        repeat(3) { index ->
            assertFalse(
                "UNKNOWN frame $index must be handed back",
                flowFrame(
                    session,
                    teamPageFrame(null, pageState = LabyrinthEntryPageState.UNKNOWN),
                    now = 2_000L + index * 100L,
                ),
            )
        }
        assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_MAIN_DIALOG, flowStage(session))

        // 回到编组页：仍是**同一轮**——不得重新武装、也不得再点一次入口。
        assertTrue(flowFrame(session, teamPageFrame(teamPageObservation()), 3_000L))
        assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_MAIN_DIALOG, flowStage(session))
        val message = session.state.value.message.orEmpty()
        assertTrue("message=$message", message.contains("等待"))
        assertFalse("message=$message", message.contains("点击编组页白色圆形入口"))
    }

    @Test
    fun `UNKNOWN frames are handed back so the wait stays bounded by the existing watchdog`() {
        val session = session(kind = LabyrinthCombatKind.BOSS, settings = settingsFor(LabyrinthCombatKind.BOSS))
        assertTrue(flowFrame(session, teamPageFrame(teamPageObservation()), 1_000L))
        releaseActionLatch(session)

        // 喂 >N 帧 UNKNOWN（200 帧 / 约 200 秒），每帧按调度器的顺序处理：本流程先看一眼，返回 false
        // 就交给既有 post-entry 处理器（停滞看门狗唯一推进的地方）。
        // 修复前这 200 帧会被本流程全部吃掉（held=200）⇒ 两个上限体系都看不到它们，运行永久停在编组页。
        var held = 0
        var stoppedAtFrame = -1
        repeat(200) { index ->
            val now = 2_000L + index * 1_000L
            val frame = teamPageFrame(null, pageState = LabyrinthEntryPageState.UNKNOWN)
            if (flowFrame(session, frame, now)) {
                held++
            } else {
                postEntryFrame(session, frame, now)
            }
            if (index == 0) {
                // UNKNOWN 不算离开：第一帧之后本轮标记必须原样保留（帧的归属权不在本流程）。
                assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_MAIN_DIALOG, flowStage(session))
            }
            if (stoppedAtFrame < 0 && awaitStopped(session, timeoutMillis = 100L)) {
                // 停机是走动作协程的（`finishFromPlanner` → `launchGuardedAction`），STOPPED 状态要等
                // 协程在别的线程上落定。这里每帧最多等 100ms（已停机时立即返回），量到的才是「这些帧把
                // 运行停住」而不是线程调度抖动。
                stoppedAtFrame = index
            }
        }
        assertEquals("the flow must not hold UNKNOWN frames", 0, held)
        // 有界：喂帧过程中就已经停机（不是被本流程无限占住），而且停机点是有限的。
        assertTrue("the run must stop within the fed frames", stoppedAtFrame in 0 until 200)
        assertTrue("status=${session.state.value.status}", awaitStopped(session))
        assertTrue(session.state.value.message.orEmpty().isNotBlank())
        // 停机是会话边界：stop() 走 `resetNodeExecutionState()`，本轮标记与普通编组记忆一起被清掉。
        // 「UNKNOWN 不算离开」是本流程内部的判定，边界重置不属于它。
        assertEquals(LabyrinthSpecialEquipmentFlowStage.INACTIVE, flowStage(session))
    }

    @Test
    fun `an unarmed dialog frame never starts the flow`() {
        val session = session(kind = LabyrinthCombatKind.BOSS, settings = settingsFor(LabyrinthCombatKind.BOSS))
        // 从来没有在编组页武装过：即使弹窗开着也不启动（只留诊断消息，返回 false 让既有路径决定）。
        assertFalse(
            flowFrame(
                session,
                teamPageFrame(mainDialogObservation(enabled = true), pageState = LabyrinthEntryPageState.UNKNOWN),
                now = 1_000L,
            ),
        )
        assertEquals(LabyrinthSpecialEquipmentFlowStage.INACTIVE, flowStage(session))
        assertNull(session.state.value.frameTrace.actionLabel)
        assertTrue(session.state.value.message.orEmpty().contains("尚未在编组页武装"))
    }

    @Test
    fun `an unselected combat kind never enters the flow and never gates the battle start`() {
        // 设置只勾选了「普通」，本次是 Boss：流程完全不介入，既有开战路径照常产出 START_BATTLE。
        val session = bossSingleTeamSession(settingsFor(LabyrinthCombatKind.NORMAL))
        runTeamSelection(session, teamPageFrame(teamPageObservation(), bossTeamIndex = 3, withStartButton = true), 1_000L)

        assertEquals(LabyrinthSpecialEquipmentFlowStage.INACTIVE, flowStage(session))
        assertEquals("boss-single:3:1001", lastExecutionKey(session))
        assertEquals("第一队已确认，开始Boss单队首战", session.state.value.frameTrace.actionLabel)
    }

    @Test
    fun `an unfinished round consumes the frame so no START_BATTLE step is ever produced`() {
        val session = bossSingleTeamSession(settingsFor(LabyrinthCombatKind.BOSS))
        // 本轮已经武装、正在等弹窗1 出现：这一帧由特别装备接管，编组执行步（含 START_BATTLE）不得产出。
        setFlowStage(session, LabyrinthSpecialEquipmentFlowStage.AWAIT_MAIN_DIALOG)
        val frame = teamPageFrame(teamPageObservation(), bossTeamIndex = 3, withStartButton = true)

        runTeamSelection(session, frame, 1_000L)

        assertNull("no battle-team step may be selected", lastExecutionKey(session))
        assertNull(session.state.value.frameTrace.actionLabel)
        assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_MAIN_DIALOG, flowStage(session))
        assertTrue(session.state.value.message.orEmpty().contains("等待"))
    }

    @Test
    fun `a finished round releases the frame and the same input then produces START_BATTLE`() {
        // 与上一条**同一帧、同一设置**，只把本轮标成已结束：闸门必须立刻放行。
        val session = bossSingleTeamSession(settingsFor(LabyrinthCombatKind.BOSS))
        setFlowStage(session, LabyrinthSpecialEquipmentFlowStage.DONE)
        val frame = teamPageFrame(teamPageObservation(), bossTeamIndex = 3, withStartButton = true)

        runTeamSelection(session, frame, 1_000L)

        assertEquals("boss-single:3:1001", lastExecutionKey(session))
        assertEquals("第一队已确认，开始Boss单队首战", session.state.value.frameTrace.actionLabel)
    }

    @Test
    fun `the boss partial-multi-team path obeys the same gate`() {
        // 闸门未放行：同一帧不得产出「其余留空，开始挑战」。
        val gated = bossPartialMultiTeamSession(settingsFor(LabyrinthCombatKind.BOSS))
        setFlowStage(gated, LabyrinthSpecialEquipmentFlowStage.AWAIT_AUTO_DIALOG)
        runTeamSelection(gated, teamPageFrame(teamPageObservation(), bossTeamIndex = 3, withStartButton = true), 1_000L)

        assertNull(lastExecutionKey(gated))
        assertNull(gated.state.value.frameTrace.actionLabel)

        // 本轮结束 ⇒ 放行，产出的正是同一条 START_BATTLE 执行步。
        val released = bossPartialMultiTeamSession(settingsFor(LabyrinthCombatKind.BOSS))
        setFlowStage(released, LabyrinthSpecialEquipmentFlowStage.DONE)
        runTeamSelection(released, teamPageFrame(teamPageObservation(), bossTeamIndex = 3, withStartButton = true), 1_000L)

        assertTrue(
            "key=${lastExecutionKey(released)}",
            lastExecutionKey(released)?.startsWith("boss-partial:1:3:") == true,
        )
        assertEquals("Boss仅编组1队，其余留空，开始挑战", released.state.value.frameTrace.actionLabel)
    }

    @Test
    fun `a missing entry button means zero special-equipment clicks and a diagnosable message`() {
        val session = session(kind = LabyrinthCombatKind.BOSS, settings = settingsFor(LabyrinthCombatKind.BOSS))
        // 直接驱动流程帧：编组页稳定但入口不可信 ⇒ 零点击、本轮结束、消息转述原因码。
        assertFalse(flowFrame(session, teamPageFrame(teamPageObservation(entry = null)), now = 1_000L))
        assertEquals(0, stepAttempts(session))
        assertEquals(LabyrinthSpecialEquipmentFlowStage.DONE, flowStage(session))
        assertNull(session.state.value.frameTrace.actionLabel)
        assertTrue(session.state.value.message.orEmpty().contains(MISS_REASON))
        assertTrue(session.state.value.message.orEmpty().contains("跳过本次穿戴"))
    }

    @Test
    fun `a missing entry button still lets the battle start`() {
        val session = bossSingleTeamSession(settingsFor(LabyrinthCombatKind.BOSS))
        val frame = teamPageFrame(
            teamPageObservation(entry = null),
            bossTeamIndex = 3,
            withStartButton = true,
        )

        runTeamSelection(session, frame, 1_000L)

        // 零点击（本轮连一次动作都没派发），但闸门放行：宁可不穿，也不能卡死整局。
        assertEquals(0, stepAttempts(session))
        assertEquals(LabyrinthSpecialEquipmentFlowStage.DONE, flowStage(session))
        assertEquals("boss-single:3:1001", lastExecutionKey(session))
        assertEquals("第一队已确认，开始Boss单队首战", session.state.value.frameTrace.actionLabel)
    }

    @Test
    fun `the session only ever dispatches cancel in the 3-1 branch`() {
        // 判态需要连续两帧同类证据：首帧只等，第二帧才分流。
        val unverifiable = session(kind = LabyrinthCombatKind.BOSS, settings = settingsFor(LabyrinthCombatKind.BOSS))
        setFlowStage(unverifiable, LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG)
        assertTrue(flowFrame(unverifiable, teamPageFrame(mainDialogObservation(enabled = null)), 1_000L))
        assertNull("the first frame must not decide", unverifiable.state.value.frameTrace.actionLabel)
        assertTrue(flowFrame(unverifiable, teamPageFrame(mainDialogObservation(enabled = null)), 1_200L))
        assertEquals("特别装备：点击「取消」", unverifiable.state.value.frameTrace.actionLabel)
        assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_ACK_CANCEL, flowStage(unverifiable))

        val clickable = session(kind = LabyrinthCombatKind.BOSS, settings = settingsFor(LabyrinthCombatKind.BOSS))
        setFlowStage(clickable, LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG)
        assertTrue(flowFrame(clickable, teamPageFrame(mainDialogObservation(enabled = true)), 1_000L))
        assertNull("the first frame must not decide", clickable.state.value.frameTrace.actionLabel)
        assertTrue(flowFrame(clickable, teamPageFrame(mainDialogObservation(enabled = true)), 1_200L))
        assertEquals("特别装备：点击「装备确定」", clickable.state.value.frameTrace.actionLabel)
        assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_ACK_EQUIP, flowStage(clickable))
    }

    @Test
    fun `the 3-2 branch ends the round on the team page and releases the gate`() {
        val session = bossSingleTeamSession(settingsFor(LabyrinthCombatKind.BOSS))
        setFlowStage(session, LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG)
        // 弹窗1 上「装备确定」连续两帧可点击 ⇒ 第二帧点它。
        assertTrue(flowFrame(session, teamPageFrame(mainDialogObservation(enabled = true)), 1_000L))
        assertTrue(flowFrame(session, teamPageFrame(mainDialogObservation(enabled = true)), 1_200L))
        assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_ACK_EQUIP, flowStage(session))
        // 回到编组页 ⇒ 本轮结束，且这一帧交还给既有路径（返回 false）。
        assertFalse(
            flowFrame(session, teamPageFrame(teamPageObservation()), 4_000L),
        )
        assertEquals(LabyrinthSpecialEquipmentFlowStage.DONE, flowStage(session))
        assertTrue(session.state.value.message.orEmpty().contains("继续开始战斗"))
    }

    // ---- 系列详情面板不得抢走特别装备弹窗的帧；反向断言保证真面板仍被接管 ----

    @Test
    fun `a series panel observation never takes the frame away from a special equipment dialog`() {
        // 「系列效果一览」面板与两个弹窗共用白色面板、蓝色标题带与白色页脚，只凭面板外观会互相认错；
        // 帧被系列详情认领后本轮特别装备就再也拿不到判断机会，只能等停机。
        val panelOnly = teamPageFrame(teamPageObservation()).copy(
            relicDetailObservation = LabyrinthRelicDetailObservation(titleConfirmed = false),
        )
        // 真面板（没有弹窗观测）仍由系列详情接管，既有行为逐字不变。
        assertTrue(labyrinthRelicDetailHoldsFrame(panelOnly))
        assertFalse(labyrinthSpecialEquipmentOwnsFrame(panelOnly))

        for (dialog in listOf(
            mainDialogObservation(enabled = true),
            mainDialogObservation(enabled = null),
            autoDialogObservation(),
        )) {
            val both = teamPageFrame(dialog).copy(
                relicDetailObservation = LabyrinthRelicDetailObservation(titleConfirmed = false),
            )
            assertTrue(labyrinthSpecialEquipmentOwnsFrame(both))
            assertFalse(
                "stage=${dialog.stage} must keep the frame",
                labyrinthRelicDetailHoldsFrame(both),
            )
        }

        // 没有系列详情观测时两个谓词都不成立：不引入任何新的接管关系。
        assertFalse(labyrinthRelicDetailHoldsFrame(teamPageFrame(mainDialogObservation(enabled = true))))
        assertFalse(labyrinthRelicDetailHoldsFrame(teamPageFrame(teamPageObservation())))
    }

    // ---- 两个弹窗不得被会话失效判定抢走；反向断言保证真实过期弹窗仍被捕获 ----

    @Test
    fun `a special-equipment dialog is never an expired session while a real expiry still is`() {
        val returnRect = EntryPixelRect(756, 692, 410, 94)
        // 弹窗帧：观测给出 stage，同时锚点分数与真过期弹窗完全一样 —— 这样「没有被判成会话失效」
        // 只可能是窄排除起了作用，而不是因为帧上没有证据。
        val dialog = expiryLikeFrame(mainDialogObservation(enabled = false), returnRect)
        val dialogBlock = labyrinthSessionBlockObservation(dialog)
        assertEquals(SessionBlockKind.NONE, dialogBlock.kind)
        assertNull(dialogBlock.returnTitleRect)

        // 反向断言（必须有）：同一条帧、同一组锚点分数，只是**没有**特别装备弹窗的 stage
        // ⇒ 必须照旧被判成会话失效并给出「返回标题」矩形，不能被这次改动一起压成 NONE。
        for (noDialog in listOf(null, teamPageObservation(entry = null))) {
            val expiry = expiryLikeFrame(noDialog, returnRect)
            assertEquals(SessionBlockKind.RECONNECT_PROMPTED, labyrinthSessionBlockObservation(expiry).kind)
            assertEquals(returnRect, labyrinthSessionBlockObservation(expiry).returnTitleRect)
        }
    }

    @Test
    fun `a special-equipment dialog is never a generic confirm dialog while the real one still is`() {
        val confirmRect = EntryPixelRect(750, 690, 415, 102)
 // 实机分数：错误提示标题 0.64、共享确认按钮 0.96、无「返回标题」、页面 NODE_SELECTION。
        assertNull(labyrinthGenericConfirmDialogRect(genericConfirmLikeFrame(mainDialogObservation(enabled = true), confirmRect)))

        // 反向断言：没有弹窗观测时，同一条帧仍然是通用确认弹窗，且仍然不是会话失效。
        val realConfirm = genericConfirmLikeFrame(null, confirmRect)
        assertEquals(confirmRect, labyrinthGenericConfirmDialogRect(realConfirm))
        assertEquals(SessionBlockKind.NONE, labyrinthSessionBlockObservation(realConfirm).kind)
    }

    /** 一帧「像真过期弹窗」的输入（锚点分数取自实机帧），可选带上特别装备观测。 */
    private fun expiryLikeFrame(
        specialEquipment: LabyrinthSpecialEquipmentObservation?,
        returnRect: EntryPixelRect,
    ): LabyrinthEntryFrameResult = LabyrinthEntryFrameResult(
        observation = LabyrinthEntryPageObservation(
            LabyrinthEntryPageState.UNKNOWN,
            0.0,
            emptyMap(),
            LabyrinthAnchorScores(
                mapOf(
                    EntryAnchorId.SESSION_ERROR_TITLE to 0.91,
                    EntryAnchorId.SESSION_RETURN_TITLE to 0.88,
                ),
            ),
        ),
        matchedFeatures = emptyList(),
        elapsedMillis = 1,
        anchorMatches = mapOf(EntryAnchorId.SESSION_RETURN_TITLE to EntryAnchorMatch(0.88, returnRect)),
        specialEquipment = specialEquipment,
        frameWidth = 1920,
        frameHeight = 1080,
    )

    /** 一帧「像真单按钮确认弹窗」的输入（分数取自实机帧），可选带上特别装备观测。 */
    private fun genericConfirmLikeFrame(
        specialEquipment: LabyrinthSpecialEquipmentObservation?,
        confirmRect: EntryPixelRect,
    ): LabyrinthEntryFrameResult = LabyrinthEntryFrameResult(
        observation = LabyrinthEntryPageObservation(
            LabyrinthEntryPageState.NODE_SELECTION,
            0.90,
            mapOf(LabyrinthEntryPageState.NODE_SELECTION to 0.90),
            LabyrinthAnchorScores(
                mapOf(
                    EntryAnchorId.SESSION_ERROR_TITLE to 0.64,
                    EntryAnchorId.SESSION_RETURN_TITLE to 0.0,
                    EntryAnchorId.SESSION_DATE_CHANGE_TITLE to 0.47,
                    EntryAnchorId.SESSION_DATE_CHANGE_CONFIRM to 0.96,
                ),
            ),
        ),
        matchedFeatures = emptyList(),
        elapsedMillis = 1,
        anchorMatches = mapOf(EntryAnchorId.SESSION_DATE_CHANGE_CONFIRM to EntryAnchorMatch(0.96, confirmRect)),
        specialEquipment = specialEquipment,
        frameWidth = 1920,
        frameHeight = 1080,
    )

    // ---- 超限逃生必须真的点出去；结束后的弹窗不得把运行永久停住 ----

    @Test
    fun `the over-limit escape really dispatches the cancel click`() {
        val session = session(kind = LabyrinthCombatKind.BOSS, settings = settingsFor(LabyrinthCombatKind.BOSS))
        // 停在「回到弹窗1 做装备确定判定」这一步并把本步尝试次数耗尽 ⇒ 决策给出 finished=true 的
        // 逃生点击（点弹窗1 左下白色「取消」退回编组页）。
        setFlowStage(session, LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG)
        setField(session, "specialEquipmentStepAttempts", MAX_SPECIAL_EQUIPMENT_STEP_ATTEMPTS)

        assertTrue(flowFrame(session, teamPageFrame(mainDialogObservation(enabled = true)), 1_000L))

        // 修复前：`if (decision.finished) { publish; return dialogOpen }` 排在派发块之前，这一帧只发消息
        // ⇒ actionLabel 为 null（用例失败，且「取消」从未被点击）。
        assertEquals("特别装备：点击「取消」", session.state.value.frameTrace.actionLabel)
        assertEquals(LabyrinthSpecialEquipmentFlowStage.DONE, flowStage(session))
        assertTrue(session.state.value.message.orEmpty().contains("超限"))
    }

    @Test
    fun `a finished round with a stuck dialog stops instead of parking for ever`() {
        // 第一段：超限时连「取消」都识别不到（弹窗2 的取消不在本流程动作集里）⇒ 必须停机并保留诊断，
        // 而不是每帧重复「本轮已结束，继续编组与开战」把运行永久停在编组页。
        val stuck = session(kind = LabyrinthCombatKind.BOSS, settings = settingsFor(LabyrinthCombatKind.BOSS))
        setFlowStage(stuck, LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG)
        setField(stuck, "specialEquipmentStepAttempts", MAX_SPECIAL_EQUIPMENT_STEP_ATTEMPTS)
        assertTrue(flowFrame(stuck, teamPageFrame(autoDialogObservation()), 1_000L))

        assertTrue("status=${stuck.state.value.status}", awaitStopped(stuck))
        val stuckMessage = stuck.state.value.message.orEmpty()
        assertTrue(stuckMessage, stuckMessage.contains("已停止并保留诊断状态"))
        assertTrue(stuckMessage, stuckMessage.contains("超限"))
        assertFalse(stuckMessage, stuckMessage.contains("继续编组与开战"))

        // 第二段：逃生点击派发出去、但弹窗一直不关 ⇒ 关闭窗口内只等且消息保持超限诊断（不得被
        // 「本轮已结束，继续编组与开战」覆盖），窗口（8s）过后才停机。
        val waited = session(kind = LabyrinthCombatKind.BOSS, settings = settingsFor(LabyrinthCombatKind.BOSS))
        setFlowStage(waited, LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG)
        setField(waited, "specialEquipmentStepAttempts", MAX_SPECIAL_EQUIPMENT_STEP_ATTEMPTS)
        assertTrue(flowFrame(waited, teamPageFrame(mainDialogObservation(enabled = false)), 1_000L))
        assertEquals("特别装备：点击「取消」", waited.state.value.frameTrace.actionLabel)
        // 这一帧真的派发过点击：放掉单动作锁，20_000 那一帧的停机申请才不会被它瞬时挡下
        // （`finishFromPlanner` 在锁被占时静默返回，而这一段的用例只喂三帧）。
        releaseActionLatch(waited)

        val noCancel = mainDialogObservation(enabled = false, cancel = null)
        assertTrue(flowFrame(waited, teamPageFrame(noCancel), 2_000L))
        assertEquals(LabyrinthEntryRecognitionStatus.IDLE, waited.state.value.status)
        val waitingMessage = waited.state.value.message.orEmpty()
        assertTrue(waitingMessage, waitingMessage.contains("超限"))
        assertFalse(waitingMessage, waitingMessage.contains("继续编组与开战"))

        assertTrue(flowFrame(waited, teamPageFrame(noCancel), 20_000L))
        assertTrue("status=${waited.state.value.status}", awaitStopped(waited))
    }

    // ---- 逃生点击的生命周期与停机文案 ----

    @Test
    fun `the escape click is dispatched exactly once and leaves nothing behind`() {
        val session = session(kind = LabyrinthCombatKind.BOSS, settings = settingsFor(LabyrinthCombatKind.BOSS))
        setFlowStage(session, LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG)
        setField(session, "specialEquipmentStepAttempts", MAX_SPECIAL_EQUIPMENT_STEP_ATTEMPTS)

        // 超限帧：逃生「取消」必须真的点出去。
        assertTrue(flowFrame(session, teamPageFrame(mainDialogObservation(enabled = true)), 1_000L))
        assertEquals("特别装备：点击「取消」", session.state.value.frameTrace.actionLabel)
        assertEquals(1_000L, lastSpecialEquipmentActionAt(session))

        // 1.5s 之后（动作间隔已过、弹窗仍开着）不得再点一次「取消」。
        assertTrue(flowFrame(session, teamPageFrame(mainDialogObservation(enabled = true)), 2_500L))
        assertEquals("the escape must not be repeated", 1_000L, lastSpecialEquipmentActionAt(session))

        // 弹窗关掉、帧被交还：逃生窗口必须一并收掉，否则残留会跨轮。
        assertFalse(flowFrame(session, teamPageFrame(teamPageObservation()), 3_000L))
        assertNull(field(session, "specialEquipmentPendingEscape").get(session))
        assertEquals(Long.MIN_VALUE, field(session, "specialEquipmentEscapeDeadline").get(session))
        // 「本轮是放弃收尾」这个事实要留在本轮里：它决定停机文案，由离开编组页时清掉。
        assertEquals(true, field(session, "specialEquipmentAbandoned").get(session) as Boolean)

        // 8s 窗口之内弹窗又出现：残留的逃生待办不得再打一次「取消」。
        assertTrue(flowFrame(session, teamPageFrame(mainDialogObservation(enabled = true)), 4_000L))
        assertEquals("a stale escape must not fire", 1_000L, lastSpecialEquipmentActionAt(session))
    }

    @Test
    fun `the stop wording distinguishes an abandoned round from a finished one`() {
        // 本轮**正常结束**（不是超限、也不是入口缺失）但弹窗又打开：文案必须说「本轮已结束」。
        val finished = session(kind = LabyrinthCombatKind.BOSS, settings = settingsFor(LabyrinthCombatKind.BOSS))
        setFlowStage(finished, LabyrinthSpecialEquipmentFlowStage.DONE)
        assertTrue(flowFrame(finished, teamPageFrame(autoDialogObservation()), 1_000L))
        assertTrue("status=${finished.state.value.status}", awaitStopped(finished))
        val finishedMessage = finished.state.value.message.orEmpty()
        assertTrue(finishedMessage, finishedMessage.contains("本轮已结束但弹窗仍开着"))
        assertFalse(finishedMessage, finishedMessage.contains("已放弃本轮"))

        // 对照：同一形状的输入，但本轮是超限放弃 ⇒ 必须说「已放弃本轮」并保留超限诊断。
        val abandoned = session(kind = LabyrinthCombatKind.BOSS, settings = settingsFor(LabyrinthCombatKind.BOSS))
        setFlowStage(abandoned, LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG)
        setField(abandoned, "specialEquipmentStepAttempts", MAX_SPECIAL_EQUIPMENT_STEP_ATTEMPTS)
        assertTrue(flowFrame(abandoned, teamPageFrame(autoDialogObservation()), 1_000L))
        assertTrue("status=${abandoned.state.value.status}", awaitStopped(abandoned))
        val abandonedMessage = abandoned.state.value.message.orEmpty()
        assertTrue(abandonedMessage, abandonedMessage.contains("已放弃本轮"))
        assertTrue(abandonedMessage, abandonedMessage.contains("超限"))
    }

    @Test
    fun `装备确定 is dispatched once and never re-dispatched while the popup stays open`() {
        val session = session(kind = LabyrinthCombatKind.BOSS, settings = settingsFor(LabyrinthCombatKind.BOSS))
        setFlowStage(session, LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG)
        // 连续两帧可点击，第二帧才派发。
        assertTrue(flowFrame(session, teamPageFrame(mainDialogObservation(enabled = true)), 1_000L))
        assertTrue(flowFrame(session, teamPageFrame(mainDialogObservation(enabled = true)), 1_200L))
        assertEquals("特别装备：点击「装备确定」", session.state.value.frameTrace.actionLabel)
        assertEquals(1_200L, lastSpecialEquipmentActionAt(session))
        assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_ACK_EQUIP, flowStage(session))

        // 点过之后弹窗仍开着也只等待，不重复派发（这里用「最后一次派发时间没变」来钉）。
        assertTrue(flowFrame(session, teamPageFrame(mainDialogObservation(enabled = true)), 2_500L))
        assertEquals(1_200L, lastSpecialEquipmentActionAt(session))
        assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_ACK_EQUIP, flowStage(session))
    }

    // ---- 入口点击时机与两态判定跨帧稳定 ----

    @Test
    fun `the entry click waits until the recommended roster is in place`() {
        val session = session(kind = LabyrinthCombatKind.BOSS, settings = settingsFor(LabyrinthCombatKind.BOSS))
        // 实机 f38 的形状：保持0/取消0/选择3 ⇒ teamReady=false（还需要加入 3 名角色，总战力 0）。
        setBattleTeamPlan(session, teamPlan(alreadyCorrect = 0, needSelect = 3, needDeselect = 0, teamReady = false))

        // 连续多帧稳定编组页：本流程一帧都不接管、更不派发入口点击（交还既有编组路径去选角色）。
        repeat(5) { index ->
            assertFalse(
                "frame $index must be handed back until the roster is ready",
                flowFrame(session, teamPageFrame(teamPageObservation()), 1_000L + index * 200L),
            )
        }
        assertEquals(LabyrinthSpecialEquipmentFlowStage.INACTIVE, flowStage(session))
        assertNull("no entry click before the roster is ready", session.state.value.frameTrace.actionLabel)
        assertEquals(0, stepAttempts(session))

        // 反例：队伍编好之后（teamReady）**第一帧**就派发入口点击。
        releaseActionLatch(session)
        setBattleTeamPlan(session, teamPlan(alreadyCorrect = 3, needSelect = 0, teamReady = true))
        assertTrue(flowFrame(session, teamPageFrame(teamPageObservation()), 3_000L))
        assertEquals("特别装备：点击入口「特别装备」", session.state.value.frameTrace.actionLabel)
        assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_MAIN_DIALOG, flowStage(session))
    }

    @Test
    fun `a popup transition frame never decides the equip-confirm state`() {
        val session = session(kind = LabyrinthCombatKind.BOSS, settings = settingsFor(LabyrinthCombatKind.BOSS))
        setFlowStage(session, LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG)

        // f147（实机）：弹窗2 刚关、弹窗1 正在显现的过渡帧，原始证据落在「不可点击」一侧
        // （lum=112.4 / B−R=81.8）。这一帧**不得**被定为不可点击、更不得点「取消」。
        assertTrue(flowFrame(session, teamPageFrame(mainDialogObservation(enabled = false)), 1_000L))
        assertNull("a transition frame must not decide anything", session.state.value.frameTrace.actionLabel)
        assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG, flowStage(session))

        // f148（实机）：真正的可用证据（lum=162.5 / B−R=110.4）。类别变了 ⇒ 计数从 1 重新开始，仍不下结论。
        assertTrue(flowFrame(session, teamPageFrame(mainDialogObservation(enabled = true)), 1_200L))
        assertNull("a single enabled frame is not enough", session.state.value.frameTrace.actionLabel)

        // 连续第二帧可用 ⇒ 才点「装备确定」（3.2 分支）。
        assertTrue(flowFrame(session, teamPageFrame(mainDialogObservation(enabled = true)), 2_600L))
        assertEquals("特别装备：点击「装备确定」", session.state.value.frameTrace.actionLabel)
        assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_ACK_EQUIP, flowStage(session))
    }

    @Test
    fun `stable disabled evidence still takes the 3-1 cancel branch after two frames`() {
        val session = session(kind = LabyrinthCombatKind.BOSS, settings = settingsFor(LabyrinthCombatKind.BOSS))
        setFlowStage(session, LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG)

        // 首帧不下结论……
        assertTrue(flowFrame(session, teamPageFrame(mainDialogObservation(enabled = false)), 1_000L))
        assertNull(session.state.value.frameTrace.actionLabel)

        // ……连续第二帧同类证据 ⇒ 3.1 分支照旧点「取消」，没有被改坏。
        assertTrue(flowFrame(session, teamPageFrame(mainDialogObservation(enabled = false)), 1_200L))
        assertEquals("特别装备：点击「取消」", session.state.value.frameTrace.actionLabel)
        assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_ACK_CANCEL, flowStage(session))
        assertEquals(mainCancelRect, labyrinthSpecialEquipmentTapRect(
            LabyrinthSpecialEquipmentTap.CANCEL,
            mainDialogObservation(enabled = false),
        ))
    }

    // ---- Boss 逐队跑：每个页签各自一轮 ----

    @Test
    fun `a boss multi-team run gives every team tab its own full special-equipment round`() {
        val session = readySession(
            kind = LabyrinthCombatKind.BOSS,
            settings = settingsFor(LabyrinthCombatKind.BOSS),
            bossTeamMode = LabyrinthBossTeamMode.MULTI_TEAM,
        )
        var now = 1_000L
        val dispatched = mutableListOf<String?>()

        for (tab in 1..3) {
            val frame: (LabyrinthSpecialEquipmentObservation?) -> LabyrinthEntryFrameResult = { observation ->
                teamPageFrame(
                    observation,
                    bossTeamIndex = tab,
                    withStartButton = true,
                    selectedCharacterIds = teamIds,
                    bossTeamTabs = bossTeamTabs,
                )
            }

            // 入口：每个页签都要自己武装一次，而且只点一次入口。
            dispatched += feedFrame(session, frame(teamPageObservation()), now)
            assertEquals(
                "tab $tab must arm its own round",
                LabyrinthSpecialEquipmentFlowStage.AWAIT_MAIN_DIALOG,
                flowStage(session),
            )
            assertEquals("tab $tab must click the entry exactly once", 1, stepAttempts(session))
            assertEquals(tab, roundBossTeamIndex(session))
            now += 2_000L

            // 弹窗1 → 自动装备；弹窗2 → 确认；回到弹窗1 → 首帧只推进证据、第二帧点「装备确定」。
            dispatched += feedFrame(session, frame(mainDialogObservation(enabled = false)), now)
            assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_AUTO_DIALOG, flowStage(session))
            now += 2_000L
            dispatched += feedFrame(session, frame(autoDialogObservation()), now)
            assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG, flowStage(session))
            now += 2_000L
            dispatched += feedFrame(session, frame(mainDialogObservation(enabled = true)), now)
            now += 1_500L
            dispatched += feedFrame(session, frame(mainDialogObservation(enabled = true)), now)
            assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_ACK_EQUIP, flowStage(session))
            now += 2_000L

            // 回到编组页：本轮结束（这一帧让给弹窗关闭动画），下一帧交还既有编组路径。
            dispatched += feedFrame(session, frame(teamPageObservation()), now)
            assertEquals(LabyrinthSpecialEquipmentFlowStage.DONE, flowStage(session))
            now += 1_200L
            dispatched += feedFrame(session, frame(teamPageObservation()), now)
            assertEquals(
                if (tab < 3) {
                    "boss-next:$tab:${teamIds.joinToString(",")}"
                } else {
                    "start:${teamIds.joinToString(",")}"
                },
                lastExecutionKey(session),
            )
            now += 2_000L
        }

        // 顺序与次数一起钉住：每队恰好一次完整轮次（入口→自动装备→弹窗2确认→装备确定），
        // 轮次之间是既有编组推进步，最后一队之后是既有开战步。
        assertEquals(
            (1..3).flatMap { tab ->
                listOf(
                    "特别装备：点击入口「特别装备」",
                    "特别装备：点击「自动装备」",
                    "特别装备：点击弹窗2「确认」",
                    null,
                    "特别装备：点击「装备确定」",
                    null,
                    if (tab < 3) "Boss第${tab}队编组完成，切换队伍${tab + 1}" else "编组完成，开始战斗",
                )
            },
            dispatched,
        )
    }

    @Test
    fun `a boss multi-team round only leaves the team page after two frames elsewhere`() {
        val session = readySession(
            kind = LabyrinthCombatKind.BOSS,
            settings = settingsFor(LabyrinthCombatKind.BOSS),
            bossTeamMode = LabyrinthBossTeamMode.MULTI_TEAM,
        )

        fun frame(observation: LabyrinthSpecialEquipmentObservation?, pageState: LabyrinthEntryPageState) =
            teamPageFrame(
                observation,
                pageState = pageState,
                bossTeamIndex = 1,
                selectedCharacterIds = teamIds,
                bossTeamTabs = bossTeamTabs,
            )

        assertEquals(
            "特别装备：点击入口「特别装备」",
            feedFrame(session, frame(teamPageObservation(), LabyrinthEntryPageState.BATTLE_TEAM_SELECTION), 1_000L),
        )
        assertEquals(1, roundBossTeamIndex(session))

        // 单帧落在别的页面：只算疑似（弹窗关合动画 / 切队过渡都会插进来），本轮标记不动。
        assertFalse(flowFrame(session, frame(null, LabyrinthEntryPageState.NODE_SELECTION), 3_000L))
        assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_MAIN_DIALOG, flowStage(session))
        assertEquals(1, roundBossTeamIndex(session))

        // 连续第二帧仍是别的页面 ⇒ 才稳定离开并重置，页签身份一并清掉。
        assertFalse(flowFrame(session, frame(null, LabyrinthEntryPageState.NODE_SELECTION), 3_200L))
        assertEquals(LabyrinthSpecialEquipmentFlowStage.INACTIVE, flowStage(session))
        assertNull(roundBossTeamIndex(session))
    }

    @Test
    fun `a boss multi-team tab without members is never armed and the tab advance still happens`() {
        val session = readySession(
            kind = LabyrinthCombatKind.BOSS,
            settings = settingsFor(LabyrinthCombatKind.BOSS),
            bossTeamMode = LabyrinthBossTeamMode.MULTI_TEAM,
            bossMultiTeamTargetCount = 2,
        )
        // 计划只用 2 队，第 3 队留空：一个 characterId 都没有。
        assertEquals(
            "Boss仅编组2队，其余留空，开始挑战",
            feedFrame(
                session,
                teamPageFrame(
                    teamPageObservation(),
                    bossTeamIndex = 3,
                    withStartButton = true,
                    bossTeamTabs = bossTeamTabs,
                ),
                1_000L,
            ),
        )

        assertEquals(LabyrinthSpecialEquipmentFlowStage.INACTIVE, flowStage(session))
        assertEquals(0, stepAttempts(session))
        assertNull(roundBossTeamIndex(session))
        // 本会话到此为止唯一的一次点击就是这一帧的既有开战步：先前的每一帧都没有派发过任何点击。
        assertEquals(1_000L, lastSpecialEquipmentActionAt(session))
        assertTrue("key=${lastExecutionKey(session)}", lastExecutionKey(session)?.startsWith("boss-partial:2:3:") == true)
    }

    @Test
    fun `a boss single-team run still arms once and still starts the battle`() {
        val session = readySession(
            kind = LabyrinthCombatKind.BOSS,
            settings = settingsFor(LabyrinthCombatKind.BOSS),
            bossTeamMode = LabyrinthBossTeamMode.SINGLE_TEAM,
        ).also { setField(it, "preparedBossFirstTeamIds", listOf("r0")) }
        var now = 1_000L
        val dispatched = mutableListOf<String?>()

        val firstTeam: (LabyrinthSpecialEquipmentObservation?) -> LabyrinthEntryFrameResult = { observation ->
            teamPageFrame(observation, bossTeamIndex = 1)
        }

        dispatched += feedFrame(session, firstTeam(teamPageObservation()), now)
        assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_MAIN_DIALOG, flowStage(session))
        assertEquals(1, stepAttempts(session))
        assertNull("单队模式：页签不是轮次身份", roundBossTeamIndex(session))
        now += 2_000L
        dispatched += feedFrame(session, firstTeam(mainDialogObservation(enabled = false)), now)
        now += 2_000L
        dispatched += feedFrame(session, firstTeam(autoDialogObservation()), now)
        now += 2_000L
        dispatched += feedFrame(session, firstTeam(mainDialogObservation(enabled = true)), now)
        now += 1_500L
        dispatched += feedFrame(session, firstTeam(mainDialogObservation(enabled = true)), now)
        now += 2_000L
        dispatched += feedFrame(session, firstTeam(teamPageObservation()), now)
        assertEquals(LabyrinthSpecialEquipmentFlowStage.DONE, flowStage(session))
        now += 1_200L

        // 空队页签上的既有「第一队已确认」开战步照常产出，而且不因为换页签再跑一轮。
        dispatched += feedFrame(
            session,
            teamPageFrame(teamPageObservation(), bossTeamIndex = 3, withStartButton = true),
            now,
        )

        assertEquals(LabyrinthSpecialEquipmentFlowStage.DONE, flowStage(session))
        assertEquals("boss-single:3:r0", lastExecutionKey(session))
        assertEquals(
            listOf(
                "特别装备：点击入口「特别装备」",
                "特别装备：点击「自动装备」",
                "特别装备：点击弹窗2「确认」",
                null,
                "特别装备：点击「装备确定」",
                null,
                "第一队已确认，开始Boss单队首战",
            ),
            dispatched,
        )
    }

    @Test
    fun `a normal and an EX battle each arm their own round once`() {
        for (kind in listOf(LabyrinthCombatKind.NORMAL, LabyrinthCombatKind.EX)) {
            val session = readySession(kind = kind, settings = settingsFor(kind))
            assertEquals(
                "$kind first battle must run the flow",
                "特别装备：点击入口「特别装备」",
                feedFrame(session, teamPageFrame(teamPageObservation(), selectedCharacterIds = teamIds), 1_000L),
            )
            assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_MAIN_DIALOG, flowStage(session))
            assertEquals(1, stepAttempts(session))
        }
    }

    // ---- 普通关卡：编组与上一场完全相同则跳过 ----

    @Test
    fun `the normal-battle team signature is remembered at battle start and survives a reset`() {
        val session = readySession(
            kind = LabyrinthCombatKind.NORMAL,
            settings = settingsFor(LabyrinthCombatKind.NORMAL),
        )
        // 入口不可信 ⇒ 本轮零点击结束，同一帧放行开战并记下本场的编组。
        assertEquals(
            "编组完成，开始战斗",
            feedFrame(
                session,
                teamPageFrame(
                    teamPageObservation(entry = null),
                    selectedCharacterIds = teamIds,
                    withStartButton = true,
                ),
                1_000L,
            ),
        )
        assertEquals("start:${teamIds.joinToString(",")}", lastExecutionKey(session))
        assertEquals(labyrinthBattleTeamSignature(teamIds), normalBattleTeamSignature(session))

        // 记忆跨战斗保留：编组执行跟踪的重置不得清掉它。
        invoke(session, "resetBattleTeamExecutionTracking")
        assertEquals(labyrinthBattleTeamSignature(teamIds), normalBattleTeamSignature(session))
    }

    @Test
    fun `a normal battle whose team is unchanged from the last one is skipped and still starts`() {
        val session = readySession(
            kind = LabyrinthCombatKind.NORMAL,
            settings = settingsFor(LabyrinthCombatKind.NORMAL),
        )
        // 第一场：入口不可信 ⇒ 零点击结束本轮，同一帧开战并记下这一场的编组。
        assertEquals(
            "编组完成，开始战斗",
            feedFrame(
                session,
                teamPageFrame(
                    teamPageObservation(entry = null),
                    selectedCharacterIds = teamIds,
                    withStartButton = true,
                ),
                1_000L,
            ),
        )
        val signature = normalBattleTeamSignature(session)
        assertEquals(labyrinthBattleTeamSignature(teamIds), signature)

        // 战斗开始的帧序：编组页 → 战斗/结算页（连续两帧落在别的页面 ⇒ 上一轮标记清零）→ 下一场编组页。
        assertFalse(flowFrame(session, teamPageFrame(null, pageState = LabyrinthEntryPageState.NODE_SELECTION), 2_000L))
        assertFalse(flowFrame(session, teamPageFrame(null, pageState = LabyrinthEntryPageState.NODE_SELECTION), 2_200L))
        assertEquals(LabyrinthSpecialEquipmentFlowStage.INACTIVE, flowStage(session))

        // 第二场第一帧：编组完全相同 ⇒ 不武装、零点击（这一帧没有可派发的开战按钮，
        // 所以任何点击都只可能来自特别装备流程）。
        val actionsBefore = lastSpecialEquipmentActionAt(session)
        assertNull(
            feedFrame(session, teamPageFrame(teamPageObservation(), selectedCharacterIds = teamIds), 3_000L),
        )
        assertEquals(LabyrinthSpecialEquipmentFlowStage.INACTIVE, flowStage(session))
        assertEquals(0, stepAttempts(session))
        assertEquals(actionsBefore, lastSpecialEquipmentActionAt(session))
        assertTrue(
            "message=${session.state.value.message}",
            session.state.value.message.orEmpty().contains("跳过本次穿戴"),
        )

        // 同一场战斗的下一帧：既有开战步照常产出，记忆不变。
        assertEquals(
            "编组完成，开始战斗",
            feedFrame(
                session,
                teamPageFrame(
                    teamPageObservation(),
                    selectedCharacterIds = teamIds,
                    withStartButton = true,
                ),
                4_500L,
            ),
        )
        assertEquals("start:${teamIds.joinToString(",")}", lastExecutionKey(session))
        assertEquals(signature, normalBattleTeamSignature(session))
    }

    @Test
    fun `an EX and a boss battle with the remembered team still run the equipment flow`() {
        for (kind in listOf(LabyrinthCombatKind.EX, LabyrinthCombatKind.BOSS)) {
            val session = readySession(
                kind = kind,
                settings = settingsFor(kind),
                bossTeamMode = LabyrinthBossTeamMode.MULTI_TEAM,
            )
            // 与记忆里的编组完全相同：只有普通关卡才跳过。
            setNormalBattleTeamSignature(session, labyrinthBattleTeamSignature(teamIds))
            assertEquals(
                "$kind must not skip",
                "特别装备：点击入口「特别装备」",
                feedFrame(
                    session,
                    teamPageFrame(
                        teamPageObservation(),
                        bossTeamIndex = if (kind == LabyrinthCombatKind.BOSS) 1 else null,
                        selectedCharacterIds = teamIds,
                        bossTeamTabs = bossTeamTabs,
                    ),
                    1_000L,
                ),
            )
            assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_MAIN_DIALOG, flowStage(session))
            assertEquals(1, stepAttempts(session))
        }
    }

    @Test
    fun `the skip needs a remembered team identified slots and the same count`() {
        val cases = listOf(
            SkipCase("首场没有记忆签名", null, teamIds),
            SkipCase("记忆为空串", "", teamIds),
            SkipCase("有未识别的成员", labyrinthBattleTeamSignature(teamIds), listOf("r0", "r1", "r2", "r3", null)),
            SkipCase("成员数量不同", labyrinthBattleTeamSignature(teamIds), teamIds.dropLast(1)),
            SkipCase("数量相同但集合不同", labyrinthBattleTeamSignature(teamIds), listOf("r0", "r1", "r2", "r3", "r9")),
            SkipCase("当前一个成员都没有", labyrinthBattleTeamSignature(teamIds), emptyList()),
        )
        for (case in cases) {
            val session = readySession(
                kind = LabyrinthCombatKind.NORMAL,
                settings = settingsFor(LabyrinthCombatKind.NORMAL),
            )
            setNormalBattleTeamSignature(session, case.previousSignature)
            assertEquals(
                "${case.name}：必须照常跑流程",
                "特别装备：点击入口「特别装备」",
                feedFrame(
                    session,
                    teamPageFrame(teamPageObservation(), selectedCharacterIds = case.currentTeam),
                    1_000L,
                ),
            )
            assertEquals(LabyrinthSpecialEquipmentFlowStage.AWAIT_MAIN_DIALOG, flowStage(session))
            assertEquals(1, stepAttempts(session))
        }
    }

    @Test
    fun `the unchanged-team predicate needs the same set the same count and identified slots`() {
        val signature = labyrinthBattleTeamSignature(listOf("b", "a"))
        assertEquals("a,b", signature)

        assertTrue(labyrinthSpecialEquipmentTeamIsUnchanged(listOf("a", "b"), signature))
        assertTrue(labyrinthSpecialEquipmentTeamIsUnchanged(listOf("b", "a"), signature))
        assertFalse(labyrinthSpecialEquipmentTeamIsUnchanged(listOf("a", "b"), "a,b,c"))
        assertFalse(labyrinthSpecialEquipmentTeamIsUnchanged(listOf("a", "b"), null))
        assertFalse(labyrinthSpecialEquipmentTeamIsUnchanged(listOf("a", "b"), ""))
        assertFalse(labyrinthSpecialEquipmentTeamIsUnchanged(listOf("a", null), signature))
        assertFalse(labyrinthSpecialEquipmentTeamIsUnchanged(listOf("a"), signature))
        assertFalse(labyrinthSpecialEquipmentTeamIsUnchanged(listOf("a", "c"), signature))
        assertFalse(labyrinthSpecialEquipmentTeamIsUnchanged(listOf("a", "a"), signature))
        assertFalse(labyrinthSpecialEquipmentTeamIsUnchanged(emptyList(), signature))

        assertTrue(labyrinthSpecialEquipmentTeamIsEmpty(emptyList()))
        assertTrue(labyrinthSpecialEquipmentTeamIsEmpty(listOf(null, null)))
        assertFalse(labyrinthSpecialEquipmentTeamIsEmpty(listOf("a", null)))
        assertFalse(labyrinthSpecialEquipmentTeamIsEmpty(listOf("a")))
    }

    @Test
    fun `the remembered normal-battle team is restored when the start click is swallowed`() {
        val session = readySession(
            kind = LabyrinthCombatKind.NORMAL,
            settings = settingsFor(LabyrinthCombatKind.NORMAL),
        )
        // 上一场记忆是另一支队伍 A，本场是 B。
        val previousSignature = labyrinthBattleTeamSignature(previousTeamIds)
        setNormalBattleTeamSignature(session, previousSignature)

        feedSwallowedNormalStart(session)

        // 开战那一帧就记下了本场编组；整个动作确认窗口内都不许回收。
        assertEquals(
            "本场开战即记下本场编组",
            labyrinthBattleTeamSignature(teamIds),
            normalBattleTeamSignature(session),
        )

        // 超过动作确认上限画面仍未推进 ⇒ 回收本次提交：这场战斗并没有开起来，记忆回到派发**之前**的值。
        feedFrameWithBattleWait(session, normalStartFrame(), 5_000L)
        assertEquals(previousSignature, normalBattleTeamSignature(session))
    }

    @Test
    fun `a recovered normal-battle start still records the team when it is dispatched again`() {
        val session = readySession(
            kind = LabyrinthCombatKind.NORMAL,
            settings = settingsFor(LabyrinthCombatKind.NORMAL),
        )
        val previousSignature = labyrinthBattleTeamSignature(previousTeamIds)
        setNormalBattleTeamSignature(session, previousSignature)

        feedSwallowedNormalStart(session)
        feedFrameWithBattleWait(session, normalStartFrame(), 5_000L)
        assertEquals("回收后记忆回到上一场", previousSignature, normalBattleTeamSignature(session))

        // 重建计划与推荐（真实运行里由规划器在下一帧给出可用阵容）后重发开战：记忆必须更新为本场编组。
        setBattleTeamPlan(session, readyPlan())
        setBattleTeamRecommendation(session, readyRecommendation())
        assertEquals("编组完成，开始战斗", feedFrameWithBattleWait(session, normalStartFrame(), 6_500L))
        assertEquals(
            "重发成功即记下本场编组",
            labyrinthBattleTeamSignature(teamIds),
            normalBattleTeamSignature(session),
        )
    }

    // ---- 反射脚手架 ----
    private fun session(
        kind: LabyrinthCombatKind?,
        settings: LabyrinthStrategySettings?,
    ): LabyrinthEntryRecognitionSession {
        val manager = AutomationSessionManager()
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager,
            processorFactory = { { error("测试不投喂帧") } },
            actionExecutor = SessionBoundActionExecutor(manager) { AutomationBackendResult.Completed },
            actionsAvailable = { true },
        )
        setField(session, "activeSessionId", SESSION_ID)
        setField(session, "specialEquipmentSettings", settings)
        if (kind != null) setField(session, "combatContext", LabyrinthCombatContext(kind))
        // 「武装」要求推荐阵容已就位；默认给一个已就位的计划，未就绪的场景由用例显式覆盖。
        setBattleTeamPlan(session, teamPlan(alreadyCorrect = 5, teamReady = true))
        return session
    }

    /**
     * 手工构造编组计划，形状与 `refreshBattleTeamSelectionPlan` 的语义一致：
     * `teamReady == needSelectIds.isEmpty() && needDeselectIds.isEmpty()`。
     * 实机 f38 的「保持0/取消0/选择3」= `alreadyCorrect = 0, needSelect = 3, teamReady = false`。
     */
    private fun teamPlan(
        alreadyCorrect: Int = 5,
        needSelect: Int = 0,
        needDeselect: Int = 0,
        teamReady: Boolean = true,
        holdCurrentTeam: Boolean = false,
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
        holdCurrentTeam = holdCurrentTeam,
    )

    /** 把编组计划写进会话状态（`_state` 是私有的 MutableStateFlow）。 */
    @Suppress("UNCHECKED_CAST")
    private fun setBattleTeamPlan(session: Any, plan: LabyrinthBattleTeamSelectionPlan?) {
        val stateFlow = field(session, "_state").get(session)
            as kotlinx.coroutines.flow.MutableStateFlow<LabyrinthEntryRecognitionSessionState>
        stateFlow.value = stateFlow.value.copy(battleTeamSelectionPlan = plan)
    }

    @Suppress("UNCHECKED_CAST")
    private fun updateState(
        session: Any,
        update: (LabyrinthEntryRecognitionSessionState) -> LabyrinthEntryRecognitionSessionState,
    ) {
        val stateFlow = field(session, "_state").get(session)
            as kotlinx.coroutines.flow.MutableStateFlow<LabyrinthEntryRecognitionSessionState>
        stateFlow.value = update(stateFlow.value)
    }

    private fun setBattleTeamRecommendation(session: Any, recommendation: LabyrinthBattleTeamRecommendation?) {
        updateState(session) { it.copy(battleTeamRecommendation = recommendation) }
    }

    /**
     * 当前队伍与推荐阵容**完全一致**的编组计划：`isStillValid` 因此成立，于是既有编组/开战路径
     * 会真的产出 NEXT_BOSS_TEAM / START_BATTLE 执行步，可以拿来断言「逐队期间推进步照常产出」。
     */
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

    /**
     * 「推荐阵容、编组计划、当前队伍三者一致」的会话现场；Boss 场景把预处理标成已完成，
     * 让编组页执行直接落在编组/开战规划上。
     */
    private fun readySession(
        kind: LabyrinthCombatKind,
        settings: LabyrinthStrategySettings?,
        ids: List<String> = teamIds,
        bossTeamMode: LabyrinthBossTeamMode? = null,
        bossMultiTeamTargetCount: Int? = null,
    ): LabyrinthEntryRecognitionSession = session(kind = kind, settings = settings).also { created ->
        setBattleTeamPlan(created, readyPlan(ids))
        setBattleTeamRecommendation(created, readyRecommendation(ids))
        if (kind == LabyrinthCombatKind.BOSS) {
            setField(created, "bossEditorPreparationStage", LabyrinthBossEditorPreparationStage.COMPLETE)
        }
        bossTeamMode?.let { setField(created, "bossTeamMode", it) }
        bossMultiTeamTargetCount?.let { setField(created, "bossMultiTeamTargetCount", it) }
    }

    /**
     * 按真实顺序喂一帧编组页：先过特别装备闸门，被交还时再走既有编组/开战路径，
     * 返回**这一帧**派发的动作标签（null ＝ 这一帧没有派发任何点击）。
     */
    private fun feedFrame(session: Any, result: LabyrinthEntryFrameResult, now: Long): String? {
        updateState(session) { it.copy(frameTrace = LabyrinthFrameTrace()) }
        // 真实运行里单动作锁由动作协程释放；测试里显式放锁，让每一次派发都可确定复现。
        releaseActionLatch(session)
        if (flowFrame(session, result, now)) return frameActionLabel(session)
        runTeamSelection(session, result, now)
        return frameActionLabel(session)
    }

    /** 这一帧派发的动作标签（`frameTrace` 是私有的 StateFlow 字段）。 */
    @Suppress("UNCHECKED_CAST")
    private fun frameActionLabel(session: Any): String? {
        val stateFlow = field(session, "_state").get(session)
            as kotlinx.coroutines.flow.MutableStateFlow<LabyrinthEntryRecognitionSessionState>
        return stateFlow.value.frameTrace.actionLabel
    }

    /**
     * 按真实帧序喂一帧编组页：先走战斗等待与「派发后画面没推进」的回收，再走特别装备闸门，
     * 最后才落到既有编组/开战路径（`processFrameResult` 的同一顺序）。
     */
    private fun feedFrameWithBattleWait(session: Any, result: LabyrinthEntryFrameResult, now: Long): String? {
        updateState(session) { it.copy(frameTrace = LabyrinthFrameTrace()) }
        releaseActionLatch(session)
        if (invoke(session, "handleBattleWaitFrame", SESSION_ID, result, now) as Boolean) return frameActionLabel(session)
        if (flowFrame(session, result, now)) return frameActionLabel(session)
        runTeamSelection(session, result, now)
        return frameActionLabel(session)
    }

    /** 普通关卡开战那一帧：入口不可信 ⇒ 本轮零点击结束，同一帧放行开战。 */
    private fun normalStartFrame(): LabyrinthEntryFrameResult = teamPageFrame(
        teamPageObservation(entry = null),
        selectedCharacterIds = teamIds,
        withStartButton = true,
    )

    /**
     * 喂到「开战点击被画面吞掉」的现场：开战那一帧记下本场编组，之后画面一直停在编组页，
     * 计划与推荐都已退化（真实运行里由规划器给不出可用阵容），但还没到动作确认上限。
     */
    private fun feedSwallowedNormalStart(session: Any) {
        assertEquals(
            "编组完成，开始战斗",
            feedFrameWithBattleWait(session, normalStartFrame(), 1_000L),
        )
        setBattleTeamPlan(session, null)
        setBattleTeamRecommendation(session, null)
        for (now in listOf(1_600L, 2_200L, 2_800L, 3_400L, 4_000L)) {
            feedFrameWithBattleWait(session, normalStartFrame(), now)
        }
    }

    /** 上一场普通战斗的编组记忆（`null` ＝ 还没有可比对的上一场）。 */
    private fun normalBattleTeamSignature(session: Any): String? =
        field(session, "lastNormalBattleTeamSignature").get(session) as String?

    private fun setNormalBattleTeamSignature(session: Any, signature: String?) {
        setField(session, "lastNormalBattleTeamSignature", signature)
    }

    /** 本轮武装时所在的 Boss 页签（非 Boss 页为 null）。 */
    private fun roundBossTeamIndex(session: Any): Int? =
        field(session, "specialEquipmentRoundBossTeamIndex").get(session) as Int?

    /** Boss 单队首战：同一帧在闸门放行时会产出 `boss-single` 的 START_BATTLE 执行步。 */
    private fun bossSingleTeamSession(settings: LabyrinthStrategySettings?): LabyrinthEntryRecognitionSession =
        session(kind = LabyrinthCombatKind.BOSS, settings = settings).also { session ->
            setField(session, "bossTeamMode", LabyrinthBossTeamMode.SINGLE_TEAM)
            setField(session, "bossEditorPreparationStage", LabyrinthBossEditorPreparationStage.COMPLETE)
            setField(session, "preparedBossFirstTeamIds", listOf("1001"))
        }

    /** Boss 部分多队：同一帧在闸门放行时会产出 `boss-partial` 的 START_BATTLE 执行步。 */
    private fun bossPartialMultiTeamSession(settings: LabyrinthStrategySettings?): LabyrinthEntryRecognitionSession =
        session(kind = LabyrinthCombatKind.BOSS, settings = settings).also { session ->
            setField(session, "bossTeamMode", LabyrinthBossTeamMode.MULTI_TEAM)
            setField(session, "bossEditorPreparationStage", LabyrinthBossEditorPreparationStage.COMPLETE)
            setField(session, "bossMultiTeamTargetCount", 1)
        }

    private fun settingsFor(vararg kinds: LabyrinthCombatKind): LabyrinthStrategySettings =
        LabyrinthStrategySettings(
            specialEquipmentMode = LabyrinthSpecialEquipmentMode.AUTO_EQUIP,
            specialEquipmentKinds = kinds.toSet(),
        )

    private fun teamPageFrame(
        specialEquipment: LabyrinthSpecialEquipmentObservation?,
        pageState: LabyrinthEntryPageState = LabyrinthEntryPageState.BATTLE_TEAM_SELECTION,
        bossTeamIndex: Int? = null,
        withStartButton: Boolean = false,
        selectedCharacterIds: List<String?> = emptyList(),
        bossTeamTabs: List<EntryPixelRect> = emptyList(),
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
            bossTeamIndex = bossTeamIndex,
            bossTeamTabs = bossTeamTabs,
        ),
        specialEquipment = specialEquipment,
    )

    private fun field(session: Any, name: String) =
        session.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private fun setField(session: Any, name: String, value: Any?) {
        field(session, name).set(session, value)
    }

    private fun flowStage(session: Any): LabyrinthSpecialEquipmentFlowStage =
        field(session, "specialEquipmentFlowStage").get(session) as LabyrinthSpecialEquipmentFlowStage

    private fun setFlowStage(session: Any, stage: LabyrinthSpecialEquipmentFlowStage) {
        setField(session, "specialEquipmentFlowStage", stage)
    }

    private fun stepAttempts(session: Any): Int = field(session, "specialEquipmentStepAttempts").get(session) as Int

    /**
     * 放掉单动作锁（`actionInFlight`）。
     *
     * 真实运行时由动作协程的 finally 释放；单元测试里动作只会在无会话的后端上得到 `StaleSession`，
     * 释放时机不确定，所以需要连续两次派发的用例显式放锁，断言才是确定性的。
     */
    private fun releaseActionLatch(session: Any) {
        setField(session, "actionInFlight", java.util.concurrent.atomic.AtomicBoolean(false))
    }

    /**
     * 等异步停机落定：`finishFromPlanner` 走动作协程 → `stopFromRunLogic` → `stop()`，状态更新发生在
     * 别的线程上，所以这里轮询它（真实等待是毫秒级，上限 5 s）。
     */
    private fun awaitStopped(
        session: LabyrinthEntryRecognitionSession,
        timeoutMillis: Long = 5_000L,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (session.state.value.status == LabyrinthEntryRecognitionStatus.STOPPED) return true
            Thread.sleep(10L)
        }
        return session.state.value.status == LabyrinthEntryRecognitionStatus.STOPPED
    }

    private fun lastExecutionKey(session: Any): String? =
        field(session, "lastBattleTeamExecutionKey").get(session) as String?

    /** 调私有的 `handleSpecialEquipmentFlowFrame`，返回它是否接管了这一帧。 */
    private fun flowFrame(
        session: Any,
        result: LabyrinthEntryFrameResult,
        now: Long,
    ): Boolean = invoke(session, "handleSpecialEquipmentFlowFrame", SESSION_ID, result, now) as Boolean

    /** 调私有的 `handleBattleTeamSelectionExecution`（三条开战路径的入口）。 */
    private fun runTeamSelection(session: Any, result: LabyrinthEntryFrameResult, now: Long) {
        invoke(session, "handleBattleTeamSelectionExecution", SESSION_ID, result, 1920, 1080, now)
    }

    /** 帧被本流程交还之后，调度器的下一站：既有 post-entry 处理器（停滞看门狗在这里推进）。 */
    private fun postEntryFrame(session: Any, result: LabyrinthEntryFrameResult, now: Long) {
        invoke(session, "handlePostEntryPage", SESSION_ID, result, 1920, 1080, now)
    }

    /** 最后一次点击的会话级节流时间戳（特别装备与编组/开战路径共用）：用来断言「没有再点一次」。 */
    private fun lastSpecialEquipmentActionAt(session: Any): Long =
        field(session, "lastSessionActionAt").get(session) as Long

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
        const val SESSION_ID_VALUE = 7L
        const val MISS_REASON = "special-equipment-entry-button-not-found"
        val SESSION_ID = AutomationSessionId(SESSION_ID_VALUE)
    }
}
