package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.AutomationBackendResult
import com.landosol.toolbox.automation.AutomationSessionManager
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话/账号边界必须把「本次运行」的状态清干净。
 *
 * 应用复用同一个会话对象跑下一个账号：特别装备轮次（`specialEquipmentFlowStage`，可能正停在 `DONE`，
 * 于是新账号的第一场直接跳过穿戴）、该轮次的步进起点，以及「上一场普通战斗的编组记忆」
 * （`lastNormalBattleTeamSignature`）都属于上一次运行，不能跨 `start()` / `stop()` 沿用。
 *
 * 两个入口共用同一个重置点 `resetNodeExecutionState()`；反向用例（同一任务内两场同编组普通战斗仍跳过）
 * 落在 `LabyrinthSpecialEquipmentDispatchThrottleTest`，这里只钉边界本身。
 */
class LabyrinthSessionBoundaryResetTest {
    @Test
    fun `a new run starts with no special-equipment round and no remembered normal team`() = runTest {
        val manager = AutomationSessionManager()
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager,
            captureActive = { true },
            processorFactory = { { error("测试不投喂帧") } },
            actionExecutor = SessionBoundActionExecutor(manager) { AutomationBackendResult.Completed },
            actionsAvailable = { true },
            gameLauncher = { true },
        )

        assertTrue(
            session.startAutomation(accountId = 1L) is LabyrinthEntryRecognitionStartResult.Started,
        )
        try {
            // 上一局走到本轮结束并记下了普通编组：这些是同一会话对象上「上一次运行」的残留。
            setField(session, "specialEquipmentFlowStage", LabyrinthSpecialEquipmentFlowStage.DONE)
            setField(session, "specialEquipmentStepStartedAt", 42_000L)
            setField(session, "lastNormalBattleTeamSignature", "a0,a1,a2,a3,a4")

            // stop 与 start 走同一个重置点：停下来的那一刻就已经清干净。
            assertTrue(session.stop("换账号", releaseCapture = false))
            assertEquals(LabyrinthSpecialEquipmentFlowStage.INACTIVE, flowStage(session))
            assertNull(normalTeamSignature(session))

            // 下一个账号：新会话必须是 [INACTIVE, null]，不得沿用上一局的轮次与编组记忆。
            assertTrue(
                session.startAutomation(accountId = 2L) is LabyrinthEntryRecognitionStartResult.Started,
            )
            assertEquals(LabyrinthSpecialEquipmentFlowStage.INACTIVE, flowStage(session))
            assertNull("the last normal team must not cross the account boundary", normalTeamSignature(session))

            // start() 自己也是靠这个重置点清的：运行中再置一次残留，重置点必须同样清掉两样东西。
            setField(session, "specialEquipmentFlowStage", LabyrinthSpecialEquipmentFlowStage.DONE)
            setField(session, "lastNormalBattleTeamSignature", "b0,b1")
            invoke(session, "resetNodeExecutionState")
            assertEquals(LabyrinthSpecialEquipmentFlowStage.INACTIVE, flowStage(session))
            assertNull(normalTeamSignature(session))
        } finally {
            session.stop()
        }
    }

    private fun flowStage(session: Any): LabyrinthSpecialEquipmentFlowStage =
        field(session, "specialEquipmentFlowStage").get(session) as LabyrinthSpecialEquipmentFlowStage

    private fun normalTeamSignature(session: Any): String? =
        field(session, "lastNormalBattleTeamSignature").get(session) as String?

    private fun field(target: Any?, name: String) =
        target!!.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private fun setField(target: Any?, name: String, value: Any?) = field(target, name).set(target, value)

    private fun invoke(target: Any, name: String): Any? {
        val method = requireNotNull(target.javaClass.declaredMethods.firstOrNull { it.name == name }) {
            "no $name on ${target.javaClass.simpleName}"
        }
        method.isAccessible = true
        return method.invoke(target)
    }
}
