package com.landosol.toolbox.automation.session

import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.automation.AutomationActionResult
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.automation.SessionExecutionResult
import com.landosol.toolbox.automation.ScreenPoint
import com.landosol.toolbox.protocol.bilibili.GameServer

/**
 * 渠道 SDK 登录界面的无障碍识别与推进。
 *
 * 渠道服客户端回到标题页后，会先由渠道 SDK 自己弹出登录相关界面，再把画面交还给游戏：
 *
 * - 九游：标题页上弹出「登录历史」对话框，列出最近登录的游戏号，点账号条目才继续登录。
 * - vivo：竖屏 UnionActivity 网页自动登录，约 4 秒后自己回到游戏，不需要点击。
 * - 4399 / 应用宝 / MuMu / 九游：不在这里点，改为刷开局后重建任务栈
 *   ([ChannelSdkTraits.requiresTaskResetBeforeReroll])，让入口流程从 SplashActivity 开始。
 *
 * 这些都是 SDK 画的安卓控件，不是 Unity 画面，游戏 UI 模板认不出来；但无障碍节点树能直接读到
 * 文字。所以这里只按文字认：同一窗口里必须同时出现标题文字和目标按钮文字，窗口包名必须属于
 * 当前账号的渠道；目标按钮文字必须完全相等且唯一，绝不点「拒绝」这类按钮。
 */
data class ChannelPromptBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val isEmpty: Boolean get() = right <= left || bottom <= top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun contains(other: ChannelPromptBounds): Boolean =
        left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom
}

/** 无障碍节点的只读快照，测试可直接构造。 */
data class ChannelPromptNode(
    val text: String? = null,
    val contentDescription: String? = null,
    val clickable: Boolean = false,
    val bounds: ChannelPromptBounds = ChannelPromptBounds(0, 0, 0, 0),
    val children: List<ChannelPromptNode> = emptyList(),
) {
    internal val labels: List<String>
        get() = listOfNotNull(text, contentDescription).map(::normalizePromptText).filter(String::isNotEmpty)

    internal fun walk(): Sequence<ChannelPromptNode> = sequence {
        yield(this@ChannelPromptNode)
        children.forEach { yieldAll(it.walk()) }
    }

    /** 每个节点连同它的祖先链（窗口根在前、父节点在后）。 */
    internal fun walkWithAncestors(
        ancestors: List<ChannelPromptNode> = emptyList(),
    ): Sequence<Pair<ChannelPromptNode, List<ChannelPromptNode>>> = sequence {
        yield(this@ChannelPromptNode to ancestors)
        val next = ancestors + this@ChannelPromptNode
        children.forEach { yieldAll(it.walkWithAncestors(next)) }
    }
}

data class ChannelPromptWindow(
    val packageName: String,
    val root: ChannelPromptNode,
)

/**
 * 一条允许助手推进的渠道登录界面规则。
 *
 * @param packages 只认这些包的窗口（游戏包本身，或 SDK 跳转到的渠道应用）。
 * @param requiredTexts 同一窗口里都要出现的文字（包含即可），证明确实是这个界面。
 * @param target 要点的节点文字，规范化后必须完全相等，而且整窗只能有一个。
 */
data class ChannelLoginPromptRule(
    val id: String,
    val label: String,
    val packages: Set<String>,
    val requiredTexts: List<String>,
    val target: String,
) {
    init {
        require(packages.isNotEmpty())
        require(requiredTexts.isNotEmpty())
        require(target.isNotBlank())
    }
}

data class ChannelPromptMatch(
    val rule: ChannelLoginPromptRule,
    val packageName: String,
    val target: ChannelPromptNode,
    /**
     * 发 ACTION_CLICK 的节点：目标本身可点就是它，否则是包住它的最近可点条目（九游「最近登录」
     * 是账号条目里的标签）；找不到合适的条目为 null，只能走手势。
     */
    val clickTarget: ChannelPromptNode? = target.takeIf(ChannelPromptNode::clickable),
    /**
     * [clickTarget] 在目标上面第几层（0 是目标自己）。点击时按这个层数在活节点树上找回同一个
     * 节点，不靠「第一个位置相同的节点」——同框的不可点文字会挡住它可点的条目。
     */
    val clickTargetHops: Int = 0,
)

/**
 * 从活节点 [start]（目标）往上走 [hops] 层，返回那一个节点；它必须仍在 [wanted] 位置而且可点，
 * 否则返回 null（界面变了，不点）。和平台无关，测试可直接用假节点。
 */
internal fun <T : Any> resolveChannelPromptClickOwner(
    start: T,
    hops: Int,
    wanted: ChannelPromptBounds,
    parent: (T) -> T?,
    bounds: (T) -> ChannelPromptBounds,
    clickable: (T) -> Boolean,
): T? {
    var node: T = start
    repeat(hops) { node = parent(node) ?: return null }
    return node.takeIf { bounds(it) == wanted && clickable(it) }
}

object ChannelLoginPromptRules {
    fun forServer(server: GameServer?): List<ChannelLoginPromptRule> = when (server) {
        // 2026-10-05 九游调试包：返回标题后停在「登录历史」，助手把它当成公告前画面，
        // 一直点对话框外面的 (950,930)。「最近登录」标在上次登录的账号条目里，点它就是点那个条目。
        GameServer.CN_ALIGAMES -> listOf(
            ChannelLoginPromptRule(
                id = "aligames-login-history",
                label = "九游登录历史",
                packages = setOf(server.packageName),
                requiredTexts = listOf("登录历史"),
                target = "最近登录",
            ),
        )
        else -> emptyList()
    }
}

/**
 * Behaviour that only some channel SDKs need. Each switch is keyed to the channels whose login
 * flow was actually observed to need it; every other server keeps upstream's behaviour.
 */
object ChannelSdkTraits {
    /**
     * The SDK login page turns the display portrait for a few seconds (vivo UnionActivity, 4399
     * 账号授权). 九游 is included because its testers ran with this tolerance on.
     */
    fun toleratesTransientRotation(server: GameServer?): Boolean =
        server == GameServer.CN_VIVO || server == GameServer.CN_4399 || server == GameServer.CN_ALIGAMES

    /**
     * These channels restart the game task after assistant-side login, avoiding the normal
     * session-expiry-to-title flow that was observed to log their SDK out. CLEAR_TASK | NEW_TASK
     * resets the Activity task but does not guarantee process termination.
     */
    fun requiresTaskResetBeforeReroll(server: GameServer?): Boolean =
        server == GameServer.CN_4399 ||
            server == GameServer.CN_TENCENT ||
            server == GameServer.CN_MUMU ||
            server == GameServer.CN_ALIGAMES
}

object ChannelLoginPromptMatcher {
    /** 返回第一个满足规则的界面；目标不唯一或没有可点区域时不返回，宁可不点。 */
    fun find(
        windows: List<ChannelPromptWindow>,
        rules: List<ChannelLoginPromptRule>,
    ): ChannelPromptMatch? {
        for (rule in rules) {
            val target = normalizePromptText(rule.target)
            val required = rule.requiredTexts.map(::normalizePromptText)
            for (window in windows) {
                if (window.packageName !in rule.packages) continue
                val nodes = window.root.walkWithAncestors().toList()
                val labels = nodes.flatMap { (node, _) -> node.labels }
                if (!required.all { text -> labels.any { it.contains(text) } }) continue
                val targets = nodes.filter { (node, _) -> node.labels.any { it == target } && !node.bounds.isEmpty }
                if (targets.size != 1) continue
                val (node, ancestors) = targets.single()
                val owner = clickTarget(node, ancestors)
                return ChannelPromptMatch(
                    rule = rule,
                    packageName = window.packageName,
                    target = node,
                    clickTarget = owner?.second,
                    clickTargetHops = owner?.first ?: 0,
                )
            }
        }
        return null
    }

    /**
     * 点击前用新读到的窗口复核 [match]；仍是同一界面返回 null，否则返回拒绝点击的原因。
     *
     * 识别与点击之间弹窗可能关闭、页面可能切换，用户也可能拉下通知栏或切走应用。所以要求：
     * 活动窗口仍属于该包；同一规则仍能在该包窗口里认出界面（标题文字仍在、目标仍唯一）；
     * 目标和点击区域的位置都没变。
     */
    fun recheck(
        match: ChannelPromptMatch,
        windows: List<ChannelPromptWindow>,
        foregroundPackage: String?,
    ): String? {
        if (foregroundPackage != match.packageName) return "前台已不是 ${match.packageName}（当前 $foregroundPackage）"
        val fresh = find(windows.filter { it.packageName == match.packageName }, listOf(match.rule))
            ?: return "界面已消失或目标不再唯一"
        if (fresh.target.bounds != match.target.bounds) return "目标位置已变化"
        if (fresh.clickTarget?.bounds != match.clickTarget?.bounds || fresh.clickTargetHops != match.clickTargetHops) {
            return "可点击区域已变化"
        }
        return null
    }

    /**
     * 目标不可点时，往上最多找 [MAX_CLICK_ANCESTOR_HOPS] 层可点的祖先代替它。这个祖先必须包住
     * 目标、不是窗口根，而且自己里面没有别的可点节点——那样它才是「这一个条目」，而不是包着
     * 好几个按钮的整个对话框。返回（层数, 节点）。
     */
    private fun clickTarget(
        node: ChannelPromptNode,
        ancestors: List<ChannelPromptNode>,
    ): Pair<Int, ChannelPromptNode>? {
        if (node.clickable) return 0 to node
        val candidates = ancestors.drop(1).asReversed().take(MAX_CLICK_ANCESTOR_HOPS)
        val index = candidates.indexOfFirst(ChannelPromptNode::clickable).takeIf { it >= 0 } ?: return null
        val owner = candidates[index]
        if (!owner.bounds.contains(node.bounds)) return null
        if (owner.walk().drop(1).any(ChannelPromptNode::clickable)) return null
        return index + 1 to owner
    }

    private const val MAX_CLICK_ANCESTOR_HOPS = 3
}

enum class ChannelPromptClick {
    /** 系统接受了 ACTION_CLICK。 */
    CLICKED,

    /** 节点在，但 ACTION_CLICK 被拒绝。 */
    REFUSED,

    /** 活节点已找不到，或已不在原位置。 */
    GONE,
}

/** 读取当前屏幕窗口、点击目标节点的平台接口。 */
interface ChannelPromptScreen {
    fun windows(): List<ChannelPromptWindow>

    /** 活动窗口的包名；读不到为 null。 */
    fun foregroundPackage(): String?

    /**
     * 对 [ChannelPromptMatch.clickTarget] 对应的活节点发 ACTION_CLICK。不发手势，也不做会话校验：
     * 只能经由 [ChannelPromptActions.click] 调用。
     */
    suspend fun click(match: ChannelPromptMatch): ChannelPromptClick
}

/** 一次受任务会话约束的动作的结果。 */
sealed interface ChannelPromptGate<out T> {
    data class Ran<T>(val value: T) : ChannelPromptGate<T>

    /** 演练会话：动作没有真正执行。 */
    data object DryRun : ChannelPromptGate<Nothing>

    data object Paused : ChannelPromptGate<Nothing>

    data class Refused(val reason: String) : ChannelPromptGate<Nothing>
}

/**
 * 点击渠道登录界面的两种方式，都必须经过任务自己的会话校验（会话仍有效、未暂停、非演练）。
 * 节点点击和手势共用同一道闸门，暂停对两者一样生效。
 */
interface ChannelPromptActions {
    /** 会话校验通过才执行 [click]（一次 ACTION_CLICK）。 */
    suspend fun click(click: suspend () -> ChannelPromptClick): ChannelPromptGate<ChannelPromptClick>

    suspend fun tap(point: ScreenPoint): ChannelPromptGate<Unit>
}

/** 用任务的 [SessionBoundActionExecutor] 实现 [ChannelPromptActions]；没有执行器时一律拒绝。 */
class SessionBoundChannelPromptActions(
    private val executor: SessionBoundActionExecutor?,
    private val sessionId: AutomationSessionId,
) : ChannelPromptActions {
    override suspend fun click(click: suspend () -> ChannelPromptClick): ChannelPromptGate<ChannelPromptClick> {
        val executor = executor ?: return ChannelPromptGate.Refused(NO_EXECUTOR)
        return when (val gated = executor.executeDirect(sessionId, click)) {
            is SessionExecutionResult.Accepted -> gated.value?.let { ChannelPromptGate.Ran(it) } ?: ChannelPromptGate.DryRun
            SessionExecutionResult.Paused -> ChannelPromptGate.Paused
            SessionExecutionResult.StaleSession -> ChannelPromptGate.Refused(STALE)
        }
    }

    override suspend fun tap(point: ScreenPoint): ChannelPromptGate<Unit> {
        val executor = executor ?: return ChannelPromptGate.Refused(NO_EXECUTOR)
        return when (val result = executor.execute(sessionId, AutomationAction.Tap(point))) {
            is AutomationActionResult.Executed -> ChannelPromptGate.Ran(Unit)
            is AutomationActionResult.DryRun -> ChannelPromptGate.DryRun
            AutomationActionResult.Paused -> ChannelPromptGate.Paused
            is AutomationActionResult.Rejected -> ChannelPromptGate.Refused(result.reason)
            AutomationActionResult.StaleSession -> ChannelPromptGate.Refused(STALE)
        }
    }

    private companion object {
        const val NO_EXECUTOR = "入口动作执行器未配置"
        const val STALE = "任务会话已结束"
    }
}

sealed interface ChannelPromptStep {
    /** 没有任何渠道登录界面。 */
    data object None : ChannelPromptStep

    /** 认到界面，但还在等连续确认或点击冷却。 */
    data class Seen(val label: String) : ChannelPromptStep

    data class Activated(val label: String) : ChannelPromptStep

    /** 点击预算用完界面仍在，或系统拒绝点击，需要停下交给人工。 */
    data class Exhausted(val reason: String) : ChannelPromptStep
}

/**
 * 有状态的渠道登录界面推进器，每次 [step] 是一次轮询。
 *
 * 同一界面要连续 [requiredSightings] 次轮询都认到才点；同一规则两次点击至少间隔 [cooldownMillis]；
 * 每条规则最多点 [maxActivationsPerRule] 次，用完界面还在就停机，而不是无限连点。
 */
class ChannelLoginPromptAssistant(
    private val screen: ChannelPromptScreen,
    private val rules: () -> List<ChannelLoginPromptRule>,
    private val requiredSightings: Int = 2,
    private val cooldownMillis: Long = 4_000L,
    private val maxActivationsPerRule: Int = 3,
    private val trace: (String) -> Unit = {},
) {
    private var lastRuleId: String? = null
    private var sightings = 0
    private val activations = mutableMapOf<String, Int>()
    private val lastActivatedAt = mutableMapOf<String, Long>()

    init {
        require(requiredSightings > 0)
        require(cooldownMillis >= 0)
        require(maxActivationsPerRule > 0)
    }

    fun reset() {
        lastRuleId = null
        sightings = 0
        activations.clear()
        lastActivatedAt.clear()
    }

    /** The prompt on screen right now, without counting a sighting or tapping. */
    fun peek(): ChannelPromptMatch? {
        val activeRules = rules()
        if (activeRules.isEmpty()) return null
        return ChannelLoginPromptMatcher.find(
            windows = runCatching { screen.windows() }.getOrDefault(emptyList()),
            rules = activeRules,
        )
    }

    /** A poll saw no prompt; the next sighting starts a fresh consecutive count. */
    fun markAbsent() {
        lastRuleId = null
        sightings = 0
    }

    /**
     * @param actions 调用方任务的会话闸门（通常是 [SessionBoundChannelPromptActions]）。节点点击和
     *   手势都经过它，任务会话、暂停与（手势的）前台包校验都在动作真正执行时核对。
     */
    suspend fun step(
        nowMillis: Long,
        actions: ChannelPromptActions,
    ): ChannelPromptStep {
        val activeRules = rules()
        if (activeRules.isEmpty()) return ChannelPromptStep.None
        val match = ChannelLoginPromptMatcher.find(
            windows = readWindows(),
            rules = activeRules,
        )
        if (match == null) {
            markAbsent()
            return ChannelPromptStep.None
        }
        val rule = match.rule
        sightings = if (lastRuleId == rule.id) sightings + 1 else 1
        lastRuleId = rule.id
        if (sightings < requiredSightings) return ChannelPromptStep.Seen(rule.label)
        val previous = lastActivatedAt[rule.id]
        if (previous != null && nowMillis - previous < cooldownMillis) return ChannelPromptStep.Seen(rule.label)
        val used = activations[rule.id] ?: 0
        if (used >= maxActivationsPerRule) {
            return ChannelPromptStep.Exhausted("${rule.label}：已点「${rule.target}」${used} 次仍未消失，停止避免误操作")
        }
        // 点击前重新读一次活动窗口：界面已经变了就不点，重新从第一次看到开始确认。
        val changed = ChannelLoginPromptMatcher.recheck(
            match = match,
            windows = readWindows(),
            foregroundPackage = runCatching { screen.foregroundPackage() }.getOrNull(),
        )
        if (changed != null) {
            trace("channel-prompt skip rule=${rule.id} reason=$changed")
            markAbsent()
            return ChannelPromptStep.Seen(rule.label)
        }
        val bounds = match.target.bounds
        trace("channel-prompt tap rule=${rule.id} target=${rule.target} pkg=${match.packageName} at=${bounds.centerX.toInt()},${bounds.centerY.toInt()} attempt=${used + 1}")
        val pressed = press(match, actions)
        trace("channel-prompt tap rule=${rule.id} result=$pressed")
        sightings = 0
        when (pressed) {
            is Press.Done -> Unit
            Press.Gone -> {
                markAbsent()
                return ChannelPromptStep.Seen(rule.label)
            }
            Press.Paused -> return ChannelPromptStep.Seen(rule.label)
            is Press.Refused -> {
                activations[rule.id] = used + 1
                lastActivatedAt[rule.id] = nowMillis
                return ChannelPromptStep.Exhausted("${rule.label}：点击「${rule.target}」被拒绝（${pressed.reason}）")
            }
        }
        activations[rule.id] = used + 1
        lastActivatedAt[rule.id] = nowMillis
        return ChannelPromptStep.Activated(rule.label)
    }

    private fun readWindows(): List<ChannelPromptWindow> = runCatching { screen.windows() }.getOrDefault(emptyList())

    private suspend fun press(match: ChannelPromptMatch, actions: ChannelPromptActions): Press {
        if (match.clickTarget != null) {
            val clicked = actions.click {
                runCatching { screen.click(match) }.getOrDefault(ChannelPromptClick.GONE)
            }
            when (clicked) {
                is ChannelPromptGate.Ran -> when (clicked.value) {
                    ChannelPromptClick.CLICKED -> return Press.Done("click")
                    ChannelPromptClick.GONE -> return Press.Gone
                    ChannelPromptClick.REFUSED -> Unit
                }
                ChannelPromptGate.DryRun -> return Press.Done("click-dry-run")
                ChannelPromptGate.Paused -> return Press.Paused
                is ChannelPromptGate.Refused -> return Press.Refused(clicked.reason)
            }
        }
        val bounds = match.target.bounds
        return when (val tapped = actions.tap(ScreenPoint(bounds.centerX, bounds.centerY))) {
            is ChannelPromptGate.Ran -> Press.Done("gesture")
            ChannelPromptGate.DryRun -> Press.Done("gesture-dry-run")
            ChannelPromptGate.Paused -> Press.Paused
            is ChannelPromptGate.Refused -> Press.Refused(tapped.reason)
        }
    }

    private sealed interface Press {
        data class Done(val via: String) : Press
        data object Gone : Press
        data object Paused : Press
        data class Refused(val reason: String) : Press
    }
}

internal fun normalizePromptText(value: String): String = value.filterNot(Char::isWhitespace)
