package com.landosol.toolbox.automation.session

import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.automation.AutomationActionResult
import com.landosol.toolbox.automation.AutomationBackendResult
import com.landosol.toolbox.automation.AutomationMode
import com.landosol.toolbox.automation.AutomationSessionManager
import com.landosol.toolbox.automation.AutomationSessionStartResult
import com.landosol.toolbox.automation.ScreenPoint
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import kotlinx.coroutines.runBlocking
import com.landosol.toolbox.protocol.bilibili.GameServer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelLoginPromptsTest {
    private val aligames = GameServer.CN_ALIGAMES.packageName

    private fun node(
        text: String? = null,
        bounds: ChannelPromptBounds = ChannelPromptBounds(0, 0, 10, 10),
        clickable: Boolean = false,
        children: List<ChannelPromptNode> = emptyList(),
    ) = ChannelPromptNode(text = text, clickable = clickable, bounds = bounds, children = children)

    /** 九游「登录历史」（2026-10-05 调试包 frame/latest.jpg，1920×1080）。 */
    private fun loginHistory(recentBadges: Int = 1, pkg: String = aligames) = ChannelPromptWindow(
        pkg,
        node(
            bounds = ChannelPromptBounds(528, 283, 1392, 797),
            children = listOf(
                node("登录历史", ChannelPromptBounds(576, 320, 744, 375)),
                node("登录其他手机号", ChannelPromptBounds(1056, 320, 1344, 375), clickable = true),
                node(
                    bounds = ChannelPromptBounds(576, 446, 1344, 770),
                    clickable = true,
                    children = List(recentBadges) { index ->
                        node("最近登录", ChannelPromptBounds(1164, 690 + index, 1308, 745 + index))
                    } + node("九游玩家2314041147", ChannelPromptBounds(612, 485, 1020, 540)),
                ),
            ),
        ),
    )

    private val aligamesRules = ChannelLoginPromptRules.forServer(GameServer.CN_ALIGAMES)

    @Test
    fun `aligames login history taps the most recent account entry`() {
        val match = ChannelLoginPromptMatcher.find(listOf(loginHistory()), aligamesRules)

        assertEquals("aligames-login-history", match?.rule?.id)
        assertEquals(ChannelPromptBounds(1164, 690, 1308, 745), match?.target?.bounds)
    }

    @Test
    fun `an ambiguous target is never tapped`() {
        assertNull(ChannelLoginPromptMatcher.find(listOf(loginHistory(recentBadges = 2)), aligamesRules))
    }

    @Test
    fun `a target without its dialog title is not enough evidence`() {
        val badgeOnly = ChannelPromptWindow(aligames, node(children = listOf(node("最近登录", clickable = true))))

        assertNull(ChannelLoginPromptMatcher.find(listOf(badgeOnly), aligamesRules))
    }

    @Test
    fun `windows of other packages are ignored`() {
        assertNull(ChannelLoginPromptMatcher.find(listOf(loginHistory(pkg = "com.example.other")), aligamesRules))
    }

    @Test
    fun `channels without known prompts have no rules`() {
        // These channel SDKs use task-stack restart instead of having these dialogs tapped.
        for (server in listOf(
            GameServer.CN_4399,
            GameServer.CN_TENCENT,
            GameServer.CN_MUMU,
            GameServer.CN_XIAOMI,
            GameServer.CN_VIVO,
            GameServer.CN_BILIBILI,
        )) {
            assertTrue("$server", ChannelLoginPromptRules.forServer(server).isEmpty())
        }
        assertTrue(ChannelLoginPromptRules.forServer(null).isEmpty())
    }

    @Test
    fun `whitespace inside SDK labels does not defeat matching`() {
        val spaced = ChannelPromptWindow(
            aligames,
            node(
                children = listOf(
                    node("登录 历史"),
                    node(" 最近登录 ", ChannelPromptBounds(1164, 690, 1308, 745)),
                ),
            ),
        )

        assertEquals("aligames-login-history", ChannelLoginPromptMatcher.find(listOf(spaced), aligamesRules)?.rule?.id)
    }

    /**
     * [windowsByRead] decides what each successive read returns, so a test can change the screen
     * between the poll that finds the prompt and the re-read just before the click.
     */
    private class FakeScreen(
        var windows: List<ChannelPromptWindow>,
        var foreground: String? = GameServer.CN_ALIGAMES.packageName,
        var clickResult: ChannelPromptClick = ChannelPromptClick.CLICKED,
    ) : ChannelPromptScreen {
        val clicked = mutableListOf<ChannelPromptMatch>()
        var reads = 0
        var windowsByRead: ((Int) -> List<ChannelPromptWindow>)? = null

        override fun windows(): List<ChannelPromptWindow> {
            reads++
            return windowsByRead?.invoke(reads) ?: windows
        }

        override fun foregroundPackage(): String? = foreground

        override suspend fun click(match: ChannelPromptMatch): ChannelPromptClick {
            clicked += match
            return clickResult
        }
    }

    /** Session gate that always lets node clicks through and answers taps with [result]. */
    private class FakeGesture(
        var result: (AutomationAction) -> AutomationActionResult = { AutomationActionResult.Executed(it) },
    ) : ChannelPromptActions {
        val actions = mutableListOf<AutomationAction>()

        override suspend fun click(click: suspend () -> ChannelPromptClick): ChannelPromptGate<ChannelPromptClick> =
            ChannelPromptGate.Ran(click())

        override suspend fun tap(point: ScreenPoint): ChannelPromptGate<Unit> {
            val action = AutomationAction.Tap(point)
            actions += action
            return when (val outcome = result(action)) {
                is AutomationActionResult.Executed -> ChannelPromptGate.Ran(Unit)
                is AutomationActionResult.DryRun -> ChannelPromptGate.DryRun
                AutomationActionResult.Paused -> ChannelPromptGate.Paused
                is AutomationActionResult.Rejected -> ChannelPromptGate.Refused(outcome.reason)
                AutomationActionResult.StaleSession -> ChannelPromptGate.Refused("stale")
            }
        }
    }

    private val noGesture = FakeGesture()

    private suspend fun ChannelLoginPromptAssistant.step(now: Long, gesture: FakeGesture = noGesture) =
        step(now, gesture)

    private fun assistant(screen: FakeScreen, server: GameServer = GameServer.CN_ALIGAMES) = ChannelLoginPromptAssistant(
        screen = screen,
        rules = { ChannelLoginPromptRules.forServer(server) },
        requiredSightings = 2,
        cooldownMillis = 4_000L,
        maxActivationsPerRule = 3,
    )

    @Test
    fun `the assistant waits for a second sighting before tapping`() = runTest {
        val screen = FakeScreen(listOf(loginHistory()))
        val assistant = assistant(screen)

        assertEquals(ChannelPromptStep.Seen("九游登录历史"), assistant.step(0L))
        assertTrue(screen.clicked.isEmpty())
        assertEquals(ChannelPromptStep.Activated("九游登录历史"), assistant.step(500L))
        assertEquals(listOf("最近登录"), screen.clicked.map { it.target.text })
    }

    @Test
    fun `a dialog that disappears between polls is not tapped`() = runTest {
        val screen = FakeScreen(listOf(loginHistory()))
        val assistant = assistant(screen)

        assistant.step(0L)
        screen.windows = emptyList()
        assertEquals(ChannelPromptStep.None, assistant.step(500L))
        screen.windows = listOf(loginHistory())
        assertTrue(assistant.step(1_000L) is ChannelPromptStep.Seen)
        assertTrue(screen.clicked.isEmpty())
    }

    @Test
    fun `a dialog that stays after the budget stops the reset`() = runTest {
        val screen = FakeScreen(listOf(loginHistory()))
        val assistant = assistant(screen)
        val activatedAt = mutableListOf<Long>()
        var exhaustedAt: Long? = null

        // Poll every 500 ms as the workflow does, with the dialog never going away.
        for (now in 0L..20_000L step 500L) {
            when (assistant.step(now)) {
                is ChannelPromptStep.Activated -> activatedAt += now
                is ChannelPromptStep.Exhausted -> { exhaustedAt = now; break }
                else -> Unit
            }
        }

        // Second sighting at 500 ms, then one tap per 4 s cooldown, then a stop.
        assertEquals(listOf(500L, 4_500L, 8_500L), activatedAt)
        assertEquals(12_500L, exhaustedAt)
        assertEquals(3, screen.clicked.size)
    }

    @Test
    fun `a rejected tap stops instead of retrying blindly`() = runTest {
        // ACTION_CLICK refused, then the session-bound gesture is refused too.
        val screen = FakeScreen(listOf(loginHistory()), clickResult = ChannelPromptClick.REFUSED)
        val gesture = FakeGesture { AutomationActionResult.Rejected(it, "游戏不在前台") }
        val assistant = assistant(screen)

        assistant.step(0L, gesture)

        assertTrue(assistant.step(500L, gesture) is ChannelPromptStep.Exhausted)
        assertEquals(1, gesture.actions.size)
    }

    @Test
    fun `the recent-login label is pressed through its account entry`() {
        val match = ChannelLoginPromptMatcher.find(listOf(loginHistory()), aligamesRules)

        // The label itself is not clickable; the entry that holds it is, and holds no other button.
        assertEquals(ChannelPromptBounds(576, 446, 1344, 770), match?.clickTarget?.bounds)
        assertEquals(1, match?.clickTargetHops)
    }

    /** Stand-in for a live accessibility node: just bounds, clickability and a parent link. */
    private data class LiveNode(
        val bounds: ChannelPromptBounds,
        val clickable: Boolean,
        val parent: LiveNode? = null,
    )

    private fun resolve(start: LiveNode, hops: Int, wanted: ChannelPromptBounds) =
        resolveChannelPromptClickOwner(start, hops, wanted, LiveNode::parent, LiveNode::bounds, LiveNode::clickable)

    @Test
    fun `a label filling its clickable entry still presses the entry`() {
        // PR #17 review 2026-10-18: the non-clickable label has exactly the entry's bounds. The
        // live lookup used to stop at the label (first node with those bounds) and report GONE.
        val bounds = ChannelPromptBounds(100, 100, 300, 200)
        val snapshot = ChannelPromptWindow(
            aligames,
            node(
                children = listOf(
                    node("登录历史"),
                    node(bounds = bounds, clickable = true, children = listOf(node("最近登录", bounds))),
                ),
            ),
        )
        val match = requireNotNull(ChannelLoginPromptMatcher.find(listOf(snapshot), aligamesRules))
        assertEquals(bounds, match.clickTarget?.bounds)
        assertEquals(1, match.clickTargetHops)

        val entry = LiveNode(bounds, clickable = true)
        val label = LiveNode(bounds, clickable = false, parent = entry)

        assertEquals(entry, resolve(label, match.clickTargetHops, bounds))
    }

    @Test
    fun `a label smaller than its entry presses the entry`() {
        val bounds = ChannelPromptBounds(100, 100, 300, 200)
        val entry = LiveNode(bounds, clickable = true)
        val label = LiveNode(ChannelPromptBounds(150, 120, 250, 160), clickable = false, parent = entry)

        assertEquals(entry, resolve(label, hops = 1, wanted = bounds))
    }

    @Test
    fun `a live tree that no longer matches the snapshot is not pressed`() {
        val bounds = ChannelPromptBounds(100, 100, 300, 200)
        // The entry became unclickable, moved, or the label lost its parent since the snapshot.
        assertNull(resolve(LiveNode(bounds, false, parent = LiveNode(bounds, clickable = false)), 1, bounds))
        assertNull(resolve(LiveNode(bounds, false, parent = LiveNode(ChannelPromptBounds(0, 0, 9, 9), true)), 1, bounds))
        assertNull(resolve(LiveNode(bounds, false), 1, bounds))
        // A clickable target is its own click node.
        val target = LiveNode(bounds, clickable = true)
        assertEquals(target, resolve(target, 0, bounds))
    }

    @Test
    fun `a clickable container holding other buttons is not used in place of the target`() {
        val dialog = ChannelPromptWindow(
            aligames,
            node(
                children = listOf(
                    node(
                        bounds = ChannelPromptBounds(528, 283, 1392, 797),
                        clickable = true,
                        children = listOf(
                            node("登录历史", ChannelPromptBounds(576, 320, 744, 375)),
                            node("登录其他手机号", ChannelPromptBounds(1056, 320, 1344, 375), clickable = true),
                            node("最近登录", ChannelPromptBounds(1164, 690, 1308, 745)),
                        ),
                    ),
                ),
            ),
        )

        val match = ChannelLoginPromptMatcher.find(listOf(dialog), aligamesRules)

        assertEquals("aligames-login-history", match?.rule?.id)
        assertNull(match?.clickTarget)
    }

    @Test
    fun `a dialog that closes between recognition and the click is not pressed`() = runTest {
        val screen = FakeScreen(listOf(loginHistory()))
        val gesture = FakeGesture()
        val assistant = assistant(screen)
        assistant.step(0L, gesture)
        // Read 2 finds the dialog for the second sighting; read 3 is the re-read before acting.
        screen.windowsByRead = { read -> if (read >= 3) emptyList() else listOf(loginHistory()) }

        assertEquals(ChannelPromptStep.Seen("九游登录历史"), assistant.step(500L, gesture))
        assertTrue(screen.clicked.isEmpty())
        assertTrue(gesture.actions.isEmpty())
    }

    @Test
    fun `a node that is gone when the click is sent is not tapped at its old position`() = runTest {
        val screen = FakeScreen(listOf(loginHistory()), clickResult = ChannelPromptClick.GONE)
        val gesture = FakeGesture()
        val assistant = assistant(screen)
        assistant.step(0L, gesture)

        assertEquals(ChannelPromptStep.Seen("九游登录历史"), assistant.step(500L, gesture))
        assertTrue(gesture.actions.isEmpty())
        // The attempt did not use up the budget: once the node is back, the next press goes through.
        assistant.step(1_000L, gesture)
        screen.clickResult = ChannelPromptClick.CLICKED
        assertEquals(ChannelPromptStep.Activated("九游登录历史"), assistant.step(1_500L, gesture))
    }

    @Test
    fun `a foreground change before the click refuses it`() = runTest {
        // The user pulled down the notification shade after the dialog was recognised.
        val screen = FakeScreen(listOf(loginHistory()), foreground = "com.android.systemui")
        val gesture = FakeGesture()
        val assistant = assistant(screen)

        assistant.step(0L, gesture)
        assertEquals(ChannelPromptStep.Seen("九游登录历史"), assistant.step(500L, gesture))
        assertEquals(ChannelPromptStep.Seen("九游登录历史"), assistant.step(1_000L, gesture))
        assertTrue(screen.clicked.isEmpty())
        assertTrue(gesture.actions.isEmpty())
    }

    @Test
    fun `a target that moved before the click is not pressed`() = runTest {
        val screen = FakeScreen(listOf(loginHistory()))
        val assistant = assistant(screen)
        assistant.step(0L)
        val original = loginHistory()
        val moved = original.copy(
            root = original.root.copy(
                children = original.root.children.map { child ->
                    child.copy(
                        children = child.children.map { badge ->
                            if (badge.text == "最近登录") badge.copy(bounds = ChannelPromptBounds(1164, 600, 1308, 655)) else badge
                        },
                    )
                },
            ),
        )
        screen.windowsByRead = { read -> if (read >= 3) listOf(moved) else listOf(original) }

        assertEquals(ChannelPromptStep.Seen("九游登录历史"), assistant.step(500L))
        assertTrue(screen.clicked.isEmpty())
    }

    @Test
    fun `without a clickable entry the tap goes through the session-bound gesture`() = runTest {
        val bare = ChannelPromptWindow(
            aligames,
            node(
                children = listOf(
                    node("登录历史", ChannelPromptBounds(576, 320, 744, 375)),
                    node("最近登录", ChannelPromptBounds(1164, 690, 1308, 745)),
                ),
            ),
        )
        val screen = FakeScreen(listOf(bare))
        val gesture = FakeGesture()
        val assistant = assistant(screen)

        assistant.step(0L, gesture)

        assertEquals(ChannelPromptStep.Activated("九游登录历史"), assistant.step(500L, gesture))
        assertTrue(screen.clicked.isEmpty())
        assertEquals(listOf(AutomationAction.Tap(ScreenPoint(1236f, 717.5f))), gesture.actions)
    }

    @Test
    fun `a refused ACTION_CLICK falls back to the session-bound gesture`() = runTest {
        val screen = FakeScreen(listOf(loginHistory()), clickResult = ChannelPromptClick.REFUSED)
        val gesture = FakeGesture()
        val assistant = assistant(screen)

        assistant.step(0L, gesture)

        assertEquals(ChannelPromptStep.Activated("九游登录历史"), assistant.step(500L, gesture))
        assertEquals(1, screen.clicked.size)
        assertEquals(1, gesture.actions.size)
    }

    private fun recentLoginDialog(clickable: Boolean) = ChannelPromptWindow(
        aligames,
        node(
            children = listOf(
                node("登录历史"),
                node("最近登录", ChannelPromptBounds(120, 120, 240, 180), clickable = clickable),
            ),
        ),
    )

    /**
     * PR #17 review 2026-10-18: the task is paused while the pre-click re-read is running, after
     * the poll's own pause check. Neither the node click nor the gesture may then reach the screen.
     */
    private fun pauseDuringRecheck(clickable: Boolean) = runBlocking<Unit> {
        val manager = AutomationSessionManager()
        val session = (manager.start(AutomationMode.LABYRINTH, dryRun = false) as AutomationSessionStartResult.Started).session
        var gestureTaps = 0
        val executor = SessionBoundActionExecutor(manager) {
            gestureTaps++
            AutomationBackendResult.Completed
        }
        val screen = FakeScreen(listOf(recentLoginDialog(clickable)))
        screen.windowsByRead = { read ->
            if (read == 3) runBlocking { assertTrue(manager.setPaused(session.id, true)) }
            listOf(recentLoginDialog(clickable))
        }
        val assistant = assistant(screen)
        val actions = SessionBoundChannelPromptActions(executor, session.id)
        try {
            assistant.step(0L, actions)
            val step = assistant.step(500L, actions)

            assertEquals(ChannelPromptStep.Seen("九游登录历史"), step)
            assertEquals(0, screen.clicked.size + gestureTaps)
        } finally {
            manager.stop(session.id)
        }
    }

    @Test
    fun `a pause during the recheck stops a direct node click`() = pauseDuringRecheck(clickable = true)

    @Test
    fun `a pause during the recheck stops a gesture click`() = pauseDuringRecheck(clickable = false)

    @Test
    fun `a dry-run session never sends the node click`() = runBlocking<Unit> {
        val manager = AutomationSessionManager()
        val session = (manager.start(AutomationMode.LABYRINTH, dryRun = true) as AutomationSessionStartResult.Started).session
        val executor = SessionBoundActionExecutor(manager) { AutomationBackendResult.Completed }
        val screen = FakeScreen(listOf(recentLoginDialog(clickable = true)))
        val assistant = assistant(screen)
        val actions = SessionBoundChannelPromptActions(executor, session.id)

        assistant.step(0L, actions)

        assertEquals(ChannelPromptStep.Activated("九游登录历史"), assistant.step(500L, actions))
        assertTrue(screen.clicked.isEmpty())
        manager.stop(session.id)
    }

    @Test
    fun `node clicks for a stopped session are refused`() = runBlocking<Unit> {
        val manager = AutomationSessionManager()
        val session = (manager.start(AutomationMode.LABYRINTH, dryRun = false) as AutomationSessionStartResult.Started).session
        val executor = SessionBoundActionExecutor(manager) { AutomationBackendResult.Completed }
        val screen = FakeScreen(listOf(recentLoginDialog(clickable = true)))
        val assistant = assistant(screen)
        val actions = SessionBoundChannelPromptActions(executor, session.id)
        assistant.step(0L, actions)
        manager.stop(session.id)

        assertTrue(assistant.step(500L, actions) is ChannelPromptStep.Exhausted)
        assertTrue(screen.clicked.isEmpty())
    }

    @Test
    fun `a paused session neither taps nor spends the budget`() = runTest {
        val bare = ChannelPromptWindow(
            aligames,
            node(children = listOf(node("登录历史"), node("最近登录", ChannelPromptBounds(1164, 690, 1308, 745)))),
        )
        val screen = FakeScreen(listOf(bare))
        val gesture = FakeGesture { AutomationActionResult.Paused }
        val assistant = assistant(screen)

        assistant.step(0L, gesture)
        assertEquals(ChannelPromptStep.Seen("九游登录历史"), assistant.step(500L, gesture))
        gesture.result = { AutomationActionResult.Executed(it) }
        assistant.step(1_000L, gesture)
        assertEquals(ChannelPromptStep.Activated("九游登录历史"), assistant.step(1_500L, gesture))
    }

    @Test
    fun `peek sees a prompt without counting a sighting or tapping`() = runTest {
        val screen = FakeScreen(listOf(loginHistory()))
        val assistant = assistant(screen)

        assertEquals("aligames-login-history", assistant.peek()?.rule?.id)
        assertEquals("aligames-login-history", assistant.peek()?.rule?.id)
        // Two peeks are not two sightings: the first real step is still only the first sighting.
        assertTrue(assistant.step(0L) is ChannelPromptStep.Seen)
        assertTrue(screen.clicked.isEmpty())
    }

    @Test
    fun `an absence between polls restarts the consecutive count`() = runTest {
        val screen = FakeScreen(listOf(loginHistory()))
        val assistant = assistant(screen)

        assistant.step(0L)
        assistant.markAbsent()

        assertTrue(assistant.step(500L) is ChannelPromptStep.Seen)
        assertTrue(screen.clicked.isEmpty())
    }

    @Test
    fun `transient rotation is tolerated only where it was observed`() {
        assertEquals(
            listOf(GameServer.CN_VIVO, GameServer.CN_ALIGAMES, GameServer.CN_4399),
            GameServer.entries.filter(ChannelSdkTraits::toleratesTransientRotation),
        )
        assertTrue(!ChannelSdkTraits.toleratesTransientRotation(null))
    }

    @Test
    fun `4399, 应用宝, MuMu and 九游 use task reset before a reroll`() {
        assertEquals(
            listOf(GameServer.CN_ALIGAMES, GameServer.CN_4399, GameServer.CN_MUMU, GameServer.CN_TENCENT),
            GameServer.entries.filter(ChannelSdkTraits::requiresTaskResetBeforeReroll),
        )
        assertTrue(!ChannelSdkTraits.requiresTaskResetBeforeReroll(null))
    }

    @Test
    fun `rules follow the selected server`() = runTest {
        val screen = FakeScreen(listOf(loginHistory()))
        val assistant = assistant(screen, server = GameServer.CN_XIAOMI)

        assertEquals(ChannelPromptStep.None, assistant.step(0L))
        assertEquals(ChannelPromptStep.None, assistant.step(500L))
        assertTrue(screen.clicked.isEmpty())
    }
}
