package com.landosol.toolbox.ui.permissions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.landosol.toolbox.permissions.PermissionCenterState
import com.landosol.toolbox.ui.CompactTopBar
import com.landosol.toolbox.ui.shouldUseNavigationRail

private data class PermissionItem(
    val title: String,
    val enabled: Boolean,
    val statusText: String = if (enabled) "已授权" else "未授权",
    val description: String,
    val actionLabel: String,
    val onAction: () -> Unit,
)

@Composable
fun PermissionCenterScreen(
    state: PermissionCenterState,
    onBack: (() -> Unit)? = null,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestNotifications: () -> Unit,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val wideLandscape = shouldUseNavigationRail(
        configuration.screenWidthDp,
        configuration.screenHeightDp,
    )
    Scaffold(
        topBar = { CompactTopBar(title = "设置与权限", onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(if (wideLandscape) 10.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (wideLandscape) 8.dp else 12.dp),
        ) {
            Text(
                "完成无障碍、任务通知和截图授权后即可运行；任务控制入口位于通知栏。",
                style = MaterialTheme.typography.bodySmall,
            )
            val permissionItems = listOf(
                PermissionItem(
                    title = "无障碍服务",
                    enabled = state.accessibilityEnabled && state.accessibilityConnected,
                    statusText = when {
                        !state.accessibilityEnabled -> "未授权"
                        state.accessibilityConnected -> "已授权且已连接"
                        else -> "已授权，服务未连接"
                    },
                    description = if (state.accessibilityEnabled && !state.accessibilityConnected) {
                        "系统可能保留了开关但没有重新绑定服务，请进入系统设置关闭后再开启。"
                    } else {
                        "用于执行用户启动的点击、滑动和返回动作。"
                    },
                    actionLabel = if (state.accessibilityEnabled && !state.accessibilityConnected) {
                        "前往重新连接"
                    } else {
                        "打开系统设置"
                    },
                    onAction = onOpenAccessibilitySettings,
                ),
                PermissionItem(
                    title = "通知",
                    enabled = state.notificationsEnabled,
                    description = "显示任务状态；展开通知可暂停、继续或停止。",
                    actionLabel = "开启或设置通知",
                    onAction = onRequestNotifications,
                ),
                PermissionItem(
                    title = "屏幕捕获",
                    enabled = state.captureSessionActive,
                    description = "由 Android 系统授权并限流读取屏幕帧；停止后立即释放资源。",
                    actionLabel = if (state.captureSessionActive) "停止屏幕捕获" else "请求截图授权",
                    onAction = if (state.captureSessionActive) onStopCapture else onStartCapture,
                ),
            )
            if (wideLandscape) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    permissionItems.forEach { item ->
                        PermissionCard(
                            modifier = Modifier.weight(1f),
                            item = item,
                            compact = true,
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    permissionItems.forEach { item ->
                        PermissionCard(
                            modifier = Modifier.fillMaxWidth(),
                            item = item,
                            compact = false,
                        )
                    }
                }
            }
            Text(
                when {
                    state.canStartVisualAutomation -> "已就绪，可以启动视觉自动化。"
                    state.accessibilityEnabled && !state.accessibilityConnected ->
                        "暂不可用：无障碍开关已开启，但服务尚未连接。"
                    else -> "暂不可用：请先完成上面的权限和截图授权。"
                },
                style = MaterialTheme.typography.titleSmall,
                color = if (state.canStartVisualAutomation) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun PermissionCard(
    modifier: Modifier,
    item: PermissionItem,
    compact: Boolean,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(if (compact) 10.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(item.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(
                    item.statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Text(item.description, style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = item.onAction,
                modifier = Modifier.defaultMinSize(minHeight = 36.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(item.actionLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
