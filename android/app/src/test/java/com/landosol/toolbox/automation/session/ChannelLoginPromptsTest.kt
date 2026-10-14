package com.landosol.toolbox.automation.session

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
        // 4399 / 应用宝 / MuMu are hard-closed before a reroll instead of having dialogs tapped.
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

    private class FakeScreen(var windows: List<ChannelPromptWindow>, var accept: Boolean = true) : ChannelPromptScreen {
        val activated = mutableListOf<ChannelPromptMatch>()

        override fun windows(): List<ChannelPromptWindow> = windows

        override suspend fun activate(match: ChannelPromptMatch): Boolean {
            activated += match
            return accept
        }
    }

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
        assertTrue(screen.activated.isEmpty())
        assertEquals(ChannelPromptStep.Activated("九游登录历史"), assistant.step(500L))
        assertEquals(listOf("最近登录"), screen.activated.map { it.target.text })
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
        assertTrue(screen.activated.isEmpty())
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
        assertEquals(3, screen.activated.size)
    }

    @Test
    fun `a rejected tap stops instead of retrying blindly`() = runTest {
        val screen = FakeScreen(listOf(loginHistory()), accept = false)
        val assistant = assistant(screen)

        assistant.step(0L)

        assertTrue(assistant.step(500L) is ChannelPromptStep.Exhausted)
    }

    @Test
    fun `peek sees a prompt without counting a sighting or tapping`() = runTest {
        val screen = FakeScreen(listOf(loginHistory()))
        val assistant = assistant(screen)

        assertEquals("aligames-login-history", assistant.peek()?.rule?.id)
        assertEquals("aligames-login-history", assistant.peek()?.rule?.id)
        // Two peeks are not two sightings: the first real step is still only the first sighting.
        assertTrue(assistant.step(0L) is ChannelPromptStep.Seen)
        assertTrue(screen.activated.isEmpty())
    }

    @Test
    fun `an absence between polls restarts the consecutive count`() = runTest {
        val screen = FakeScreen(listOf(loginHistory()))
        val assistant = assistant(screen)

        assistant.step(0L)
        assistant.markAbsent()

        assertTrue(assistant.step(500L) is ChannelPromptStep.Seen)
        assertTrue(screen.activated.isEmpty())
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
    fun `only 4399, 应用宝 and MuMu are hard-closed before a reroll`() {
        assertEquals(
            listOf(GameServer.CN_4399, GameServer.CN_MUMU, GameServer.CN_TENCENT),
            GameServer.entries.filter(ChannelSdkTraits::closesClientBeforeReroll),
        )
        assertTrue(!ChannelSdkTraits.closesClientBeforeReroll(null))
    }

    @Test
    fun `rules follow the selected server`() = runTest {
        val screen = FakeScreen(listOf(loginHistory()))
        val assistant = assistant(screen, server = GameServer.CN_XIAOMI)

        assertEquals(ChannelPromptStep.None, assistant.step(0L))
        assertEquals(ChannelPromptStep.None, assistant.step(500L))
        assertTrue(screen.activated.isEmpty())
    }
}
