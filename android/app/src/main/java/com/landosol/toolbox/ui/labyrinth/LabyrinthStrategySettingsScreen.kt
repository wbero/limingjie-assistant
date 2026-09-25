package com.landosol.toolbox.ui.labyrinth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.landosol.toolbox.labyrinth.LabyrinthBossTeamMode
import com.landosol.toolbox.labyrinth.LabyrinthRelicMark
import com.landosol.toolbox.labyrinth.LabyrinthStrategySettings
import com.landosol.toolbox.labyrinth.LabyrinthStrategySettingsCodec
import com.landosol.toolbox.labyrinth.LabyrinthRoleRatingItem
import com.landosol.toolbox.ui.CompactTopBar
import kotlin.math.roundToInt

private val StrategySaver = Saver<LabyrinthStrategySettings, String>(
    save = { LabyrinthStrategySettingsCodec.encodeDraft(it) },
    restore = LabyrinthStrategySettingsCodec::decodeDraft,
)

/** Full-screen phone editor: one expanded category, thumb-size controls, explicit durable save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabyrinthStrategySettingsScreen(
    saved: LabyrinthStrategySettings,
    saving: Boolean,
    message: String?,
    onSave: (LabyrinthStrategySettings) -> Unit,
    onClose: () -> Unit,
    loadRoleRatings: suspend () -> List<LabyrinthRoleRatingItem> = { emptyList() },
) {
    var draft by rememberSaveable(stateSaver = StrategySaver) { mutableStateOf(saved) }
    var section by rememberSaveable { mutableStateOf("评分") }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var showRoleRatings by rememberSaveable { mutableStateOf(false) }
    var showOpeningRoster by rememberSaveable { mutableStateOf(false) }
    val close = { if (!saving) { if (draft != saved) confirmDiscard = true else onClose() } }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                CompactTopBar(title = "策略设置", onBack = close, backEnabled = !saving, actions = {
                    TextButton(onClick = { confirmReset = true }, enabled = !saving) {
                        Text("恢复默认", style = MaterialTheme.typography.labelLarge)
                    }
                })
            },
            bottomBar = {
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                        Text(draft.validationError() ?: message ?: if (draft == saved) "配置已保存；下次启动生效" else "有未保存的修改",
                            style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { onSave(draft) }, enabled = !saving && draft.validationError() == null && draft != saved,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(if (saving) "正在保存…" else "保存配置")
                        }
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("全局配置 · 所有账号共用。保存后从下次启动识别/自动执行生效，当前任务不变。",
                    style = MaterialTheme.typography.bodyMedium)
                StrategySection("评分", "玩家评分 ${draft.playerWeight}% · 系统权重自动归一化", section, { section = it }) {
                    OutlinedButton(onClick = { showRoleRatings = true }, enabled = !saving,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("角色头像与个人评分 · 已修改 ${draft.personalRoleScores.size} 人")
                    }
                    Text("玩家评分缺失时仍按原规则降低有效权重。以下系统项目是相对权重，无需手动凑到 100。")
                    StrategyNumber("玩家评分占比", draft.playerWeight, 0..100, "%", !saving) { draft = draft.copy(playerWeight = it) }
                    StrategyNumber("系统：伤害", draft.damageWeight, 0..100, "", !saving) { draft = draft.copy(damageWeight = it) }
                    StrategyNumber("系统：生存", draft.survivalWeight, 0..100, "", !saving) { draft = draft.copy(survivalWeight = it) }
                    StrategyNumber("系统：功能", draft.functionWeight, 0..100, "", !saving) { draft = draft.copy(functionWeight = it) }
                    StrategyNumber("系统：阵型", draft.formationWeight, 0..100, "", !saving) { draft = draft.copy(formationWeight = it) }
                    StrategyNumber("系统：协同", draft.cohesionWeight, 0..100, "", !saving) { draft = draft.copy(cohesionWeight = it) }
                    StrategySwitch(
                        "属性配对增伤",
                        "关闭后角色属性仍会识别，并继续用于筛选/显示；只是评分时不再计算同属性 2/3/4/5 人的 8/16/25/75% 伤害加成。",
                        draft.attributePairingBonusEnabled,
                        !saving,
                    ) { draft = draft.copy(attributePairingBonusEnabled = it) }
                    StrategyNumber("EX/Boss物法混编扣分", draft.mixedDamagePenalty, 0..30, "分", !saving) {
                        draft = draft.copy(mixedDamagePenalty = it)
                    }
                    StrategyNumber("有效效果角色系统加分", draft.effectiveCharacterSystemBonus, 0..30, "分", !saving) {
                        draft = draft.copy(effectiveCharacterSystemBonus = it)
                    }
                    Text("有效效果加分只进入该角色的系统侧评价，再按玩家/系统权重融合；不会修改玩家评分，也不会在最终队伍总分外追加奖励。")
                }
                StrategySection("选人", "第二队 ${draft.secondTeamWeight}% · 第三队 ${draft.thirdTeamWeight}%", section, { section = it }) {
                    Text("角色奖励按能改善哪一队来评估，第一队权重固定为 100%。重复纯掩护/纯治疗按扣分处理，不是人数硬限制。")
                    StrategyNumber("第二队部署权重", draft.secondTeamWeight, 0..100, "%", !saving) { draft = draft.copy(secondTeamWeight = it) }
                    StrategyNumber("第三队部署权重", draft.thirdTeamWeight, 0..100, "%", !saving) { draft = draft.copy(thirdTeamWeight = it) }
                    StrategyNumber("重复纯职能基础扣分", draft.duplicateRolePenalty, 0..30, "分", !saving) { draft = draft.copy(duplicateRolePenalty = it) }
                }
                StrategySection(
                    "Boss编组",
                    (if (draft.bossTeamMode == LabyrinthBossTeamMode.MULTI_TEAM) "多队编组（默认）" else "单队编组") +
                        if (draft.preferPureBossDamageSystem) " · 优先纯物/法" else " · 允许自由混编",
                    section,
                    { section = it },
                ) {
                    Text("多队模式会按当前角色池自动使用可安全组成的3/2/1队，并优化多队总评分；单队模式只编第一队。每支实际编组队伍都要求一号位达到生存资格。物法偏好是软限制，不会为了纯体系牺牲明显更强或更能生存的阵容。")
                    StrategyRadioChoice(
                        label = "多队编组",
                        detail = "默认。依次生成并自动配置三支互不重复的 Boss 队伍。",
                        selected = draft.bossTeamMode == LabyrinthBossTeamMode.MULTI_TEAM,
                        enabled = !saving,
                    ) { draft = draft.copy(bossTeamMode = LabyrinthBossTeamMode.MULTI_TEAM) }
                    StrategyRadioChoice(
                        label = "单队编组",
                        detail = "只配置第一队；确认第二、三队为空后开始战斗。",
                        selected = draft.bossTeamMode == LabyrinthBossTeamMode.SINGLE_TEAM,
                        enabled = !saving,
                    ) { draft = draft.copy(bossTeamMode = LabyrinthBossTeamMode.SINGLE_TEAM) }
                    StrategySwitch(
                        "优先纯物理/纯法术编组",
                        "默认开启。Boss 使用与 EX 相同的物法混编扣分；关闭后 Boss 可自由混编，EX 仍保持单一物/法体系偏好。",
                        draft.preferPureBossDamageSystem,
                        !saving,
                    ) { draft = draft.copy(preferPureBossDamageSystem = it) }
                    StrategySwitch(
                        "单队Boss失败后切换多队",
                        "仅单队编组生效。达到下面设置的重新挑战次数后仍失败，不直接重刷开局，而是返回编组并临时切换为多队模式；随后把一号位生存线从55分放宽到40分，按当前角色池尽量组成3/2/1队。不会永久修改你的Boss编组设置。",
                        draft.singleBossFallbackToMultiAfterThreeFailures,
                        !saving,
                    ) { draft = draft.copy(singleBossFallbackToMultiAfterThreeFailures = it) }
                    StrategyNumber(
                        "单队Boss重新挑战次数",
                        draft.singleBossRetryCountBeforeMulti,
                        0..10,
                        "次",
                        !saving && draft.singleBossFallbackToMultiAfterThreeFailures,
                    ) { draft = draft.copy(singleBossRetryCountBeforeMulti = it) }
                    Text(
                        "当前设置：首次挑战失败后最多重新挑战 ${draft.singleBossRetryCountBeforeMulti} 次；" +
                            "第 ${draft.singleBossRetryCountBeforeMulti + 1} 次挑战仍失败时切换多队。0 表示首次失败就切多队。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                StrategySection(
                    "战斗失败",
                    when {
                        draft.bossTeamMode == LabyrinthBossTeamMode.SINGLE_TEAM && draft.singleBossFallbackToMultiAfterThreeFailures ->
                            "单队${draft.singleBossRetryCountBeforeMulti + 1}次挑战失败后切多队"
                        draft.rerollAfterThreeBattleFailures -> "3次失败后自动重刷"
                        else -> "达到原重试上限后停止"
                    },
                    section,
                    { section = it },
                ) {
                    StrategySwitch(
                        "3次失败后自动重刷开局",
                        "首次挑战 + 2次重新挑战均失败后，不点击结束/结算；停止视觉执行并直接调用刷开局流程撤退当前开局，再按已保存的刷开局条件重刷。开启时 Boss 也允许总共挑战3次。",
                        draft.rerollAfterThreeBattleFailures,
                        !saving,
                    ) { draft = draft.copy(rerollAfterThreeBattleFailures = it) }
                }
                StrategySection("遗物", "基础 ${draft.baselineTarget} 层 · 截止区域 ${draft.baselineLastArea}", section, { section = it }) {
                    Text("加速、暴击、守备的基础层数仅作加分，不强制凑齐。截止区域为 0 表示不使用前期加分；15 层档位优先保留。")
                    StrategyNumber("基础目标", draft.baselineTarget, 1..4, "层", !saving) { draft = draft.copy(baselineTarget = it) }
                    StrategyNumber("前期加分截止区域", draft.baselineLastArea, 0..5, "", !saving) { draft = draft.copy(baselineLastArea = it) }
                    StrategyNumber("转追削弱的最低层数", draft.debuffPivotMinimum, 5..15, "层", !saving) { draft = draft.copy(debuffPivotMinimum = it) }
                    StrategyNumber("削弱落后主印记的容忍量", draft.debuffPivotTolerance, 0..5, "层", !saving) { draft = draft.copy(debuffPivotTolerance = it) }
                }
                StrategySection("遗物优先级", relicPrioritySummary(draft.relicMarkPriority), section, { section = it }) {
                    Text(
                        "按你的顺序主追印记。留空时沿用内置顺序（以当前层数最高的未满印记为主追）。" +
                            "能一次跨到 15 层的选项仍然优先，这条不受顺序影响。",
                    )
                    RelicPriorityEditor(
                        priority = draft.relicMarkPriority,
                        enabled = !saving,
                        onChange = { draft = draft.copy(relicMarkPriority = it) },
                    )
                }
                StrategySection("商店", if (draft.buyRelics) "购买遗物 · ${if (draft.refreshShop) "区域 ${draft.refreshFromArea} 起刷新" else "不刷新"}" else "不购买", section, { section = it }) {
                    Text("遗物始终优先；一轮三个遗物买完且可刷新时继续刷新买遗物。只有遗物/刷新都无法继续时，才用剩余金币购买已确认的职能印记。无法确认的商品不会盲买。")
                    StrategySwitch("购买遗物", "关闭后退出商店，也不会刷新。", draft.buyRelics, !saving) { draft = draft.copy(buyRelics = it) }
                    StrategySwitch("允许商店刷新", "买完当前可确认遗物后，才考虑花费 300 金币刷新。", draft.refreshShop, !saving && draft.buyRelics) { draft = draft.copy(refreshShop = it) }
                    if (draft.buyRelics && draft.refreshShop) {
                        StrategyNumber("允许刷新起始区域", draft.refreshFromArea, 1..5, "", !saving) { draft = draft.copy(refreshFromArea = it) }
                        if (draft.refreshFromArea < 5) Text("提前刷新会消耗后续商店的金币，请谨慎调整。", color = MaterialTheme.colorScheme.error)
                    }
                }
                StrategySection("开局", openingRosterSummary(draft.openingRosters), section, { section = it }) {
                    Text(
                        "每个公会开局固定选 3 名角色。槽位内可以排多个候选，按顺序取第一个能在列表里找到的；" +
                            "公会附赠的角色由游戏决定，不可编辑。",
                    )
                    OutlinedButton(
                        onClick = { showOpeningRoster = true },
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text("编辑开局角色 · ${openingRosterSummary(draft.openingRosters)}")
                    }
                    draft.openingRosterError()?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                StrategySection("事件", "普通 ${draft.normalWinRate}% · 极难 ${draft.extremeWinRate}%", section, { section = it }) {
                    Text("仅用于比较事件战斗奖励的期望值，不代表实测胜率或胜利保证。")
                    StrategyNumber("普通战估计胜率", draft.normalWinRate, 0..100, "%", !saving) { draft = draft.copy(normalWinRate = it) }
                    StrategyNumber("极难战估计胜率", draft.extremeWinRate, 0..100, "%", !saving) { draft = draft.copy(extremeWinRate = it) }
                }
                Text("识别置信度、资料完整性、游戏属性档位和操作安全检查保持原规则，不随偏好配置关闭。",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (showRoleRatings) LabyrinthRoleRatingsScreen(
        scores = draft.personalRoleScores,
        loadCatalog = loadRoleRatings,
        onScoreChange = { id, value -> draft = draft.copy(personalRoleScores =
            if (value == null) draft.personalRoleScores - id else draft.personalRoleScores + (id to value)) },
        onClose = { showRoleRatings = false },
    )
    if (showOpeningRoster) LabyrinthOpeningRosterScreen(
        rosters = draft.openingRosters,
        loadCatalog = loadRoleRatings,
        onChange = { guildId, slots ->
            draft = draft.copy(
                openingRosters = if (slots == null) {
                    draft.openingRosters - guildId
                } else {
                    draft.openingRosters + (guildId to slots)
                },
            )
        },
        onClose = { showOpeningRoster = false },
    )
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false },
        title = { Text("放弃未保存的修改？") }, text = { Text("已保存的配置不会改变。") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; onClose() }) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } })
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false },
        title = { Text("恢复全部默认策略？") }, text = { Text("恢复后仍需点击保存，才会替换已保存的配置。") },
        confirmButton = { TextButton(onClick = { draft = LabyrinthStrategySettings(); confirmReset = false }) { Text("恢复默认") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } })
}

private fun relicPrioritySummary(priority: List<LabyrinthRelicMark>): String =
    if (priority.isEmpty()) "使用内置顺序" else priority.joinToString(" > ") { it.label }

@Composable
private fun RelicPriorityEditor(
    priority: List<LabyrinthRelicMark>,
    enabled: Boolean,
    onChange: (List<LabyrinthRelicMark>) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        priority.forEachIndexed { index, mark ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${index + 1}. ${mark.label}", Modifier.weight(1f))
                TextButton(
                    onClick = { onChange(priority.toMutableList().also { it.add(index - 1, it.removeAt(index)) }) },
                    enabled = enabled && index > 0,
                ) { Text("上移") }
                TextButton(onClick = { onChange(priority - mark) }, enabled = enabled) { Text("移除") }
            }
        }
        val remaining = LabyrinthRelicMark.entries.filterNot { it in priority }
        if (remaining.isNotEmpty()) {
            Text("添加：", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                remaining.forEach { mark ->
                    OutlinedButton(onClick = { onChange(priority + mark) }, enabled = enabled) {
                        Text(mark.label)
                    }
                }
            }
        }
        if (priority.isNotEmpty()) {
            TextButton(onClick = { onChange(emptyList()) }, enabled = enabled) { Text("恢复内置顺序") }
        }
    }
}

@Composable
private fun StrategyRadioChoice(
    label: String,
    detail: String,
    selected: Boolean,
    enabled: Boolean,
    select: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, onClick = select)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = select, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(label)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StrategySection(title: String, summary: String, active: String, select: (String) -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { select(if (active == title) "" else title) }) {
                Text("$title ${if (active == title) "▴" else "▾"}", style = MaterialTheme.typography.titleMedium)
                Text(summary, style = MaterialTheme.typography.bodySmall)
            }
            if (active == title) content()
        }
    }
}

@Composable
private fun StrategyNumber(label: String, value: Int, range: IntRange, suffix: String, enabled: Boolean, change: (Int) -> Unit) {
    Column {
        Text("$label：$value$suffix", style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { change(value - 1) }, enabled = enabled && value > range.first,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "$label 减少" }) { Text("−") }
            Slider(value = value.toFloat(), onValueChange = { change(it.roundToInt()) },
                valueRange = range.first.toFloat()..range.last.toFloat(), steps = (range.last - range.first - 1).coerceAtLeast(0),
                enabled = enabled, modifier = Modifier.weight(1f).semantics { contentDescription = label })
            TextButton(onClick = { change(value + 1) }, enabled = enabled && value < range.last,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "$label 增加" }) { Text("+") }
        }
    }
}

@Composable
private fun StrategySwitch(label: String, detail: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(label); Text(detail, style = MaterialTheme.typography.bodySmall) }
        Switch(checked = checked, onCheckedChange = change, enabled = enabled, modifier = Modifier.semantics { contentDescription = label })
    }
}
