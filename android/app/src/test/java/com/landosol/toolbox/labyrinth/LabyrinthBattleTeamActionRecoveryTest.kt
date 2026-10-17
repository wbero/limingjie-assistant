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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两条「编组页动作」缺陷的会话级回归：动作节流在**两个流程之间**共享，被吞掉的「战斗开始」必须可回收、
 * 可重试且**有界**。
 *
 * 用例按实机事故的帧序喂帧（特别装备「取消」→ 0.76 秒后的编组页「战斗开始」），并驱动
 * `processFrameResult` 用到的同一串处理器、同样的顺序：推荐 → 编组计划 → 战斗等待 → 编组页执行。
 * 断言落点是**真的有没有派发**（`frameTrace.actionLabel`），而不是「消息变了」。
 */
class LabyrinthBattleTeamActionRecoveryTest {
    // 与标定一致的几何：只用来给出「战斗开始」按钮矩形。
    private val startButtonRect = EntryPixelRect(1565, 850, 270, 110)
    private val entryRect = EntryPixelRect(1180, 858, 97, 97)
    private val mainCancelRect = EntryPixelRect(101, 922, 387, 71)
    private val equipConfirmRect = EntryPixelRect(1428, 917, 396, 81)
    private val mainAutoRect = EntryPixelRect(995, 922, 387, 71)

    private val roleIds = (1..5).map { "r$it" }

    // ---- 共享动作节流：0.76s 的「战斗开始」必须被挡住 ----

    @Test
    fun `the start tap 760ms after the equipment cancel is withheld and only fires past the shared interval`() =
        runBlocking {
            withSession { session, _ ->
                assertEquals(
                    "特别装备：点击入口「特别装备」",
                    feedFrame(session, teamFrame(specialEquipment = teamPageObservation()), 1_000L),
                )
                awaitActions(session)
                // 入口已点、弹窗1/弹窗2 已走完，停在「回到弹窗1 判装备确定两态」这一步。
                setFlowStage(session, LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG)

                // 弹窗1 上「装备确定」连续两帧不可点击 ⇒ 点左下白色「取消」：实机事故里被插队的就是这一步。
                val dialogFrame = dialogFrame()
                assertNull(feedFrame(session, dialogFrame, 2_300L))
                awaitActions(session)
                assertEquals(
                    "特别装备：点击「取消」",
                    feedFrame(session, dialogFrame, 3_600L),
                )
                awaitActions(session)
                assertEquals(3_600L, lastSessionActionAt(session))

                // 实机 0.76s 之后那一帧：编组页已经干净，开战路径就想点「战斗开始」。
                val cleanFrame = teamFrame()
                assertNull(
                    "760ms 间隔的开战点击就是被游戏吞掉的那一下，不得派发",
                    feedFrame(session, cleanFrame, 3_600L + 760L),
                )
                // 没有点击 = 会话级节流戳没有前进（编组执行步本身在这一帧已经被选中，所以只能看点击）。
                assertEquals(3_600L, lastSessionActionAt(session))
                // 弹窗关闭后的第一帧已经让给关闭动画（结算标记被消费），所以下一帧只剩节流在挡。
                assertTrue(
                    "the modal-close settle must already be consumed",
                    !modalCloseSettlePending(session),
                )

                // 距离「取消」1000ms：节流未到，仍然不得派发——这一条只可能是共享时间戳挡住的。
                assertNull(
                    "1000ms 仍不足 1200ms，必须继续等",
                    feedFrame(session, cleanFrame, 3_600L + 1_000L),
                )
                assertEquals(3_600L, lastSessionActionAt(session))

                // 超过 1200ms：放行，且派发的正是同一条「战斗开始」。
                assertEquals(
                    "编组完成，开始战斗",
                    feedFrame(session, cleanFrame, 3_600L + 1_300L),
                )
                assertEquals("start:${roleIds.joinToString(",")}", lastExecutionKey(session))
                awaitActions(session)

                // 这一下如果也被游戏吞掉（画面一直停在编组页），本次提交必须被释放并允许重发——
                // 与「特别装备关闭」的编组路径共用同一处回收。
                var now = 3_600L + 1_300L
                repeat(9) {
                    now += 600L
                    feedFrame(session, cleanFrame, now)
                }
                assertTrue(
                    "the swallowed start must be released and retried",
                    unconfirmedAttempts(session) >= 1,
                )
            }
        }

    @Test
    fun `the first frame after the equipment dialog closes never dispatches the start even past the interval`() =
        runBlocking {
            withSession { session, _ ->
                assertEquals(
                    "特别装备：点击入口「特别装备」",
                    feedFrame(session, teamFrame(specialEquipment = teamPageObservation()), 1_000L),
                )
                awaitActions(session)
                setFlowStage(session, LabyrinthSpecialEquipmentFlowStage.AWAIT_SECOND_MAIN_DIALOG)

                val dialogFrame = dialogFrame()
                assertNull(feedFrame(session, dialogFrame, 2_300L))
                awaitActions(session)
                assertEquals("特别装备：点击「取消」", feedFrame(session, dialogFrame, 3_500L))
                awaitActions(session)

                // 弹窗刚关的这一帧距离点击已经 1400ms（> 1200ms）：唯一还挡着开战的就是「关闭后的第一帧
                // 让给关闭动画」这条规则，所以这是能失败的真断言。
                val cleanFrame = teamFrame()
                assertNull(
                    "the frame right after the modal closes must not click into the close animation",
                    feedFrame(session, cleanFrame, 4_900L),
                )
                assertEquals(3_500L, lastSessionActionAt(session))
                assertTrue(!modalCloseSettlePending(session))

                // 下一帧照旧放行既有编组/开战路径。
                assertEquals("编组完成，开始战斗", feedFrame(session, cleanFrame, 5_500L))
            }
        }

    // ---- 被吞掉的开战：释放提交、重建计划、可重试且有界 ----

    @Test
    fun `a swallowed start releases its commit rebuilds the plan and is retried within bounds`() = runBlocking {
        // 关闭特别装备：走的就是既有编组/开战路径本身，本用例只钉「被吞掉的开战」这一件事。
        withSession(specialEquipment = false) { session, _ ->
            val frame = teamFrame()
            val ids = roleIds.toSet()
            val signature = labyrinthBattleTeamSignature(roleIds)

            // 计划就位 ⇒ 派发「战斗开始」，提交这一队并记下「还没被画面确认」。
            assertEquals("编组完成，开始战斗", feedFrame(session, frame, 1_000L))
            awaitActions(session)
            assertEquals(ids, committedIds(session))
            assertEquals(setOf(signature), currentSignatures(session))
            assertNotNull(pendingBattleTeamAction(session))

            // 画面一直停在编组页：可用池变空，推荐与计划都退化为不可用——开战被吞后停在编组页的现场。
            for (now in listOf(1_700L, 2_400L, 3_100L, 3_900L)) {
                assertNull(feedFrame(session, frame, now))
                assertEquals("the commit must survive the acknowledged window", ids, committedIds(session))
            }
            assertEquals(
                "没有已获得的可用角色",
                session.state.value.battleTeamRecommendationUnavailableReason,
            )
            assertNull(session.state.value.battleTeamSelectionPlan)

            // 动作确认上限（4s）之前不得释放。
            assertNull(feedFrame(session, frame, 4_700L))
            assertEquals(ids, committedIds(session))

            // 超过上限 ⇒ 释放本次提交（角色 + 队伍签名）、重置战斗等待与编组执行跟踪、丢掉推荐缓存。
            assertNull(feedFrame(session, frame, 5_300L))
            assertTrue("committed ids must be released", committedIds(session).isEmpty())
            assertTrue("team signatures must be released with them", currentSignatures(session).isEmpty())
            assertNull(pendingBattleTeamAction(session))
            assertNull(lastBattleTeamRecommendationKey(session))
            assertNull(session.state.value.battleTeamRecommendation)
            assertNull(session.state.value.battleTeamRecommendationUnavailableReason)
            assertNull(session.state.value.battleTeamSelectionPlan)
            assertNull("battle wait must be reset", battleWaitStarted(session))

            // 下一帧：计划重建成功，同一条「战斗开始」可以再次派发。
            assertEquals("编组完成，开始战斗", feedFrame(session, frame, 5_900L))
            awaitActions(session)
            assertEquals(ids, committedIds(session))

            // 重复被吞不会无限重试：每一次都重新规划后重发，超过既有上限就停机并保留诊断。
            // 帧预算 200 帧（约 120 秒）；停机申请走动作协程，所以每帧等动作落定再喂下一帧。
            var dispatches = 1
            var now = 5_900L
            var frames = 0
            while (frames < 200 && session.state.value.status != LabyrinthEntryRecognitionStatus.STOPPED) {
                frames++
                now += 600L
                if (feedFrame(session, frame, now) == "编组完成，开始战斗") dispatches++
                awaitActions(session)
            }
            assertTrue("the swallowed start must be retried, not parked: dispatches=$dispatches", dispatches >= 3)
            assertTrue("the run must stop within the fed frames: frames=$frames", frames < 200)
            assertTrue("status=${session.state.value.status}", awaitStopped(session))
            val message = session.state.value.message.orEmpty()
            assertTrue(message, message.contains("画面仍未推进"))
            assertTrue(message, message.contains("已停止并保留诊断状态"))
        }
    }

    // ---- 会话级脚手架 ----

    private suspend fun withSession(
        specialEquipment: Boolean = true,
        body: suspend (LabyrinthEntryRecognitionSession, AutomationSessionManager) -> Unit,
    ) {
        val manager = AutomationSessionManager()
        val profiles = (1..5).associate { index ->
            val id = "r$index"
            id to role(id, "角色$index", index, reliableVanguard = if (index == 1) 100.0 else 0.0)
        }
        var fakeClock = 0L
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager,
            captureActive = { true },
            processorFactory = { { error("测试不投喂截图帧") } },
            actionExecutor = SessionBoundActionExecutor(manager) { AutomationBackendResult.Completed },
            actionsAvailable = { true },
            clock = { fakeClock },
            battleTeamRecommendationPlanner = recommendationPlanner(profiles),
            battleTeamSelectionPlanner = LabyrinthBattleTeamSelectionPlanner(
                profiles,
                minimumCharacterConfidence = 0.0,
            ),
        )
        assertTrue(session.startAutomation(accountId = 1L) is LabyrinthEntryRecognitionStartResult.Started)
        try {
            val sessionId = requireNotNull(manager.current()).id
            set(session, "combatContext", LabyrinthCombatContext(LabyrinthCombatKind.NORMAL))
            set(session, "entryPhaseComplete", true)
            setState(session) { state ->
                state.copy(
                    joinedCharacters = roleIds.mapIndexed { index, id ->
                        LabyrinthJoinedCharacter(
                            characterId = id,
                            displayName = "角色${index + 1}",
                            orderIndex = index,
                            confidence = 0.95,
                            firstSeenAt = 0L,
                            lastSeenAt = 0L,
                        )
                    },
                )
            }
            // 本次战斗勾选了「自动穿戴特别装备」：闸门必须和既有编组/开战路径同帧成立。
            if (specialEquipment) {
                set(
                    session,
                    "specialEquipmentSettings",
                    LabyrinthStrategySettings(
                        specialEquipmentMode = LabyrinthSpecialEquipmentMode.AUTO_EQUIP,
                        specialEquipmentKinds = setOf(LabyrinthCombatKind.NORMAL),
                    ),
                )
            }
            frameClock = { fakeClock = it }
            sessionIdHolder = sessionId
            body(session, manager)
        } finally {
            session.stop()
        }
    }

    private var frameClock: (Long) -> Unit = {}
    private var sessionIdHolder: AutomationSessionId = SESSION_ID

    /**
     * 喂一帧，按 `processFrameResult` 的顺序驱动同一串处理器，返回**这一帧**派发的动作标签。
     *
     * `frameTrace` 在真实路径里每帧重建，所以这里也每帧清一次：「这一帧有没有派发动作」才是断言目标，
     * 不能拿上一帧的标签当结果。
     */
    private fun feedFrame(
        session: LabyrinthEntryRecognitionSession,
        result: LabyrinthEntryFrameResult,
        now: Long,
    ): String? {
        frameClock(now)
        setState(session) { it.copy(frameTrace = LabyrinthFrameTrace()) }
        invoke(session, "refreshBattleTeamRecommendation", sessionIdHolder, result.observation.state)
        invoke(
            session,
            "refreshBattleTeamSelectionPlan",
            sessionIdHolder,
            result.observation.state,
            result.battleTeamSelection,
        )
        val consumed = invoke(session, "handleBattleWaitFrame", sessionIdHolder, result, now) as Boolean
        if (!consumed) {
            invoke(session, "handleBattleTeamSelectionExecution", sessionIdHolder, result, 1920, 1080, now)
        }
        return session.state.value.frameTrace.actionLabel
    }

    private fun teamFrame(
        pageState: LabyrinthEntryPageState = LabyrinthEntryPageState.BATTLE_TEAM_SELECTION,
        specialEquipment: LabyrinthSpecialEquipmentObservation? = null,
    ): LabyrinthEntryFrameResult = LabyrinthEntryFrameResult(
        observation = LabyrinthEntryPageObservation(pageState, 1.0, emptyMap(), LabyrinthAnchorScores(emptyMap())),
        matchedFeatures = emptyList(),
        elapsedMillis = 0,
        anchorMatches = mapOf(
            EntryAnchorId.BATTLE_TEAM_START_BUTTON to EntryAnchorMatch(0.92, startButtonRect),
        ),
        battleTeamSelection = LabyrinthBattleTeamObservation(
            currentFilter = LabyrinthBattleElementFilter.ALL,
            filters = emptyList(),
            visibleCharacters = emptyList(),
            selectedCharacters = roleIds.mapIndexed { index, id ->
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
            viewportRevision = 0L,
        ),
        specialEquipment = specialEquipment,
        frameWidth = 1920,
        frameHeight = 1080,
    )

    /** 弹窗1 帧：实机上弹窗压暗底层页，页面分类会落到 UNKNOWN。 */
    private fun dialogFrame(): LabyrinthEntryFrameResult =
        teamFrame(pageState = LabyrinthEntryPageState.UNKNOWN, specialEquipment = mainDialogObservation())

    private fun teamPageObservation(): LabyrinthSpecialEquipmentObservation =
        LabyrinthSpecialEquipmentObservation(
            stage = null,
            confidence = 0.90,
            entryButtonRect = entryRect,
            entryButtonScore = 0.90,
        )

    /** 「装备确定」连续两帧判定为不可点击 ⇒ 决策走 3.1，只允许点「取消」。 */
    private fun mainDialogObservation(): LabyrinthSpecialEquipmentObservation =
        LabyrinthSpecialEquipmentObservation(
            stage = LabyrinthSpecialEquipmentStage.MAIN_DIALOG,
            confidence = 1.0,
            autoButtonRect = mainAutoRect,
            confirmButtonRect = equipConfirmRect,
            cancelButtonRect = mainCancelRect,
            equipConfirmEnabled = false,
            equipConfirmLuminance = 95.5,
        )

    // ---- 观测 ----

    private fun committedIds(session: Any): Set<String> =
        (field(session, "committedBattleCharacterIds").get(session) as Set<*>).filterIsInstance<String>().toSet()

    private fun currentSignatures(session: Any): Set<String> =
        (field(session, "currentBattleTeamSignatures").get(session) as Set<*>).filterIsInstance<String>().toSet()

    private fun pendingBattleTeamAction(session: Any): Any? = field(session, "pendingBattleTeamAction").get(session)

    private fun modalCloseSettlePending(session: Any): Boolean =
        field(session, "specialEquipmentModalCloseSettlePending").get(session) as Boolean

    private fun unconfirmedAttempts(session: Any): Int =
        field(session, "battleTeamActionUnconfirmedAttempts").get(session) as Int

    private fun lastSessionActionAt(session: Any): Long =
        field(session, "lastSessionActionAt").get(session) as Long

    private fun lastBattleTeamRecommendationKey(session: Any): String? =
        field(session, "lastBattleTeamRecommendationKey").get(session) as String?

    private fun lastExecutionKey(session: Any): String? =
        field(session, "lastBattleTeamExecutionKey").get(session) as String?

    /** `startedAt` of the battle-wait policy, or null when it was reset. */
    private fun battleWaitStarted(session: Any): Long? {
        val wait = field(session, "battleWait").get(session)
        return field(wait, "startedAt").get(wait) as Long?
    }

    private suspend fun awaitActions(session: Any) {
        val inFlight = field(session, "actionInFlight").get(session) as AtomicBoolean
        withTimeout(5_000L) { while (inFlight.get()) delay(10L) }
    }

    private suspend fun awaitStopped(
        session: LabyrinthEntryRecognitionSession,
        timeoutMillis: Long = 5_000L,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (session.state.value.status == LabyrinthEntryRecognitionStatus.STOPPED) return true
            delay(10L)
        }
        return session.state.value.status == LabyrinthEntryRecognitionStatus.STOPPED
    }

    // ---- 反射脚手架 ----

    @Suppress("UNCHECKED_CAST")
    private fun setState(
        session: Any,
        update: (LabyrinthEntryRecognitionSessionState) -> LabyrinthEntryRecognitionSessionState,
    ) {
        val flow = field(session, "_state").get(session)
            as MutableStateFlow<LabyrinthEntryRecognitionSessionState>
        flow.value = update(flow.value)
    }

    private fun field(target: Any?, name: String) =
        target!!.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private fun set(target: Any?, name: String, value: Any?) = field(target, name).set(target, value)

    private fun setFlowStage(session: Any, stage: LabyrinthSpecialEquipmentFlowStage) =
        set(session, "specialEquipmentFlowStage", stage)

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

    private fun recommendationPlanner(
        profiles: Map<String, LabyrinthRoleProfile>,
    ): LabyrinthBattleTeamRecommendationPlanner {
        val config = LabyrinthTeamScoringConfig(
            attributeDamageBonus = mapOf(1 to 0.0, 2 to 0.08, 3 to 0.16, 4 to 0.25, 5 to 0.75),
        )
        return LabyrinthBattleTeamRecommendationPlanner(
            profiles = profiles,
            teamPlanSearcher = LabyrinthTeamPlanSearcher(
                LabyrinthTeamOptimizer(LabyrinthTeamScorer(config)),
                LabyrinthTeamPlanSearchConfig(
                    oneTeamKillScore = 0.0,
                    mainTeamScore = 0.0,
                    cleanupTeamScore = 0.0,
                    mainPlusCleanupCombinedScore = 0.0,
                    stableTeamScore = 0.0,
                    fallbackTeamScore = 0.0,
                    threeTeamCombinedScore = 0.0,
                ),
            ),
        )
    }

    private fun role(
        id: String,
        name: String,
        position: Int,
        reliableVanguard: Double = 0.0,
    ) = LabyrinthRoleProfile(
        characterId = id,
        displayName = name,
        roleClass = if (reliableVanguard > 0.0) "掩护者" else "攻击者",
        attribute = LabyrinthCharacterAttribute.LIGHT,
        damageType = LabyrinthRoleDamageType.PHYSICAL,
        position = position,
        userScore = 80.0,
        userRatingCount = 5,
        physicalDamagePotential = 80.0,
        magicDamagePotential = 0.0,
        functions = LabyrinthRoleFunctions(reliableVanguard = reliableVanguard),
    )

    private companion object {
        val SESSION_ID = AutomationSessionId(7L)
    }
}
