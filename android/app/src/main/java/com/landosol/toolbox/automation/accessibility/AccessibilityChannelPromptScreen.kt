package com.landosol.toolbox.automation.accessibility

import android.graphics.Rect
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.automation.AutomationBackendResult
import com.landosol.toolbox.automation.ScreenPoint
import com.landosol.toolbox.automation.session.ChannelPromptBounds
import com.landosol.toolbox.automation.session.ChannelPromptMatch
import com.landosol.toolbox.automation.session.ChannelPromptNode
import com.landosol.toolbox.automation.session.ChannelPromptScreen
import com.landosol.toolbox.automation.session.ChannelPromptWindow
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 用无障碍窗口树实现 [ChannelPromptScreen]。
 *
 * 读取：遍历所有交互窗口（服务已声明 flagRetrieveInteractiveWindows），每个窗口拍成只读快照，
 * 节点数和深度都有上限，避免游戏或网页窗口的大树拖慢轮询。
 * 点击：目标节点本身可点击就发 ACTION_CLICK；否则（例如九游「最近登录」只是条目里的标签）
 * 在节点中心发一次手势点击，由它所在的条目处理。
 */
class AccessibilityChannelPromptScreen : ChannelPromptScreen {
    override fun windows(): List<ChannelPromptWindow> {
        val service = LandosolAccessibilityService.current() ?: return emptyList()
        val roots = runCatching { service.windows.mapNotNull { it.root } }.getOrDefault(emptyList())
            .ifEmpty { listOfNotNull(runCatching { service.rootInActiveWindow }.getOrNull()) }
        return roots.mapNotNull { root ->
            val packageName = root.packageName?.toString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val budget = intArrayOf(MAX_NODES_PER_WINDOW)
            ChannelPromptWindow(packageName, snapshot(root, depth = 0, budget = budget))
        }
    }

    override suspend fun activate(match: ChannelPromptMatch): Boolean {
        val service = LandosolAccessibilityService.current() ?: return false
        val bounds = match.target.bounds
        if (match.target.clickable) {
            val node = findLiveNode(service, match)
            if (node != null) {
                val clicked = onMain { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                if (clicked) return true
            }
        }
        val tap = AutomationAction.Tap(ScreenPoint(bounds.centerX, bounds.centerY))
        return service.perform(tap, Display.DEFAULT_DISPLAY) == AutomationBackendResult.Completed
    }

    /** 快照里不保留 AccessibilityNodeInfo，点击前按包名、文字和位置重新找一次活节点。 */
    private fun findLiveNode(service: LandosolAccessibilityService, match: ChannelPromptMatch): AccessibilityNodeInfo? {
        val roots = runCatching { service.windows.mapNotNull { it.root } }.getOrDefault(emptyList())
            .ifEmpty { listOfNotNull(runCatching { service.rootInActiveWindow }.getOrNull()) }
        val wanted = match.target.bounds
        return roots
            .filter { it.packageName?.toString() == match.packageName }
            .flatMap { root ->
                runCatching { root.findAccessibilityNodeInfosByText(match.rule.target) }.getOrDefault(emptyList())
            }
            .firstOrNull { node ->
                val rect = Rect().also(node::getBoundsInScreen)
                rect.left == wanted.left && rect.top == wanted.top &&
                    rect.right == wanted.right && rect.bottom == wanted.bottom
            }
    }

    private fun snapshot(node: AccessibilityNodeInfo, depth: Int, budget: IntArray): ChannelPromptNode {
        budget[0]--
        val rect = Rect().also(node::getBoundsInScreen)
        val children = if (depth >= MAX_DEPTH) {
            emptyList()
        } else {
            (0 until node.childCount).mapNotNull { index ->
                if (budget[0] <= 0) return@mapNotNull null
                runCatching { node.getChild(index) }.getOrNull()?.let { child -> snapshot(child, depth + 1, budget) }
            }
        }
        return ChannelPromptNode(
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            clickable = node.isClickable,
            bounds = ChannelPromptBounds(rect.left, rect.top, rect.right, rect.bottom),
            children = children,
        )
    }

    private suspend fun onMain(action: () -> Boolean): Boolean =
        suspendCancellableCoroutine { continuation ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (continuation.isActive) continuation.resume(runCatching(action).getOrDefault(false))
            }
        }

    private companion object {
        const val MAX_NODES_PER_WINDOW = 600
        const val MAX_DEPTH = 40
    }
}
