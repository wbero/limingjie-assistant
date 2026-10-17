package com.landosol.toolbox.labyrinth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class LabyrinthBossTeamMode {
    MULTI_TEAM,
    SINGLE_TEAM,
}

/**
 * 「特别装备」自动穿戴模式（语义见 `android/docs/special-equipment.md`）。
 *
 * 只有 [AUTO_EQUIP] 才可能触发「队伍编组页 → 特别装备设定 → 自动特别装备设定」流程；[OFF] 是
 * 默认值，自动执行对它产生 0 次额外点击。
 */
@Serializable
enum class LabyrinthSpecialEquipmentMode {
    /** 关闭（默认）：不自动穿戴特别装备。 */
    OFF,

    /** 自动穿戴：只有本次战斗的关卡类型在 [LabyrinthStrategySettings.specialEquipmentKinds] 内才执行。 */
    AUTO_EQUIP,
}

/** User preferences only: game facts and recognition safety thresholds are not editable. */
@Serializable
data class LabyrinthStrategySettings(
    val playerWeight: Int = 70,
    val damageWeight: Int = 50,
    val survivalWeight: Int = 23,
    val functionWeight: Int = 13,
    val formationWeight: Int = 4,
    val cohesionWeight: Int = 10,
    /** Apply the game's same-attribute damage tiers (2/3/4/5 = 8/16/25/75%). */
    val attributePairingBonusEnabled: Boolean = true,
    val duplicateRolePenalty: Int = 8,
    /** EX/Boss team-level soft penalty applied after player/system blending. */
    val mixedDamagePenalty: Int = 15,
    /** Per-role Effective Effect bonus applied only to that role's system-side score. */
    val effectiveCharacterSystemBonus: Int = 8,
    val secondTeamWeight: Int = 35,
    val thirdTeamWeight: Int = 20,
    /** Boss battle composition mode. Multi-team is the product default. */
    val bossTeamMode: LabyrinthBossTeamMode = LabyrinthBossTeamMode.MULTI_TEAM,
    /** Boss-only soft preference: favor a coherent physical or magic damage system. */
    val preferPureBossDamageSystem: Boolean = true,
    /** Single-team Boss fallback: after the configured retries are exhausted, retry in multi-team mode. */
    val singleBossFallbackToMultiAfterThreeFailures: Boolean = true,
    /** Number of "重新挑战" taps allowed in single-team Boss mode before switching to multi-team. */
    val singleBossRetryCountBeforeMulti: Int = 2,
    /** After the third failed combat attempt, abandon the run and start rerolling via the API. */
    val rerollAfterThreeBattleFailures: Boolean = true,
    val baselineLastArea: Int = 3,
    val baselineTarget: Int = 4,
    val debuffPivotMinimum: Int = 12,
    val debuffPivotTolerance: Int = 2,
    val buyRelics: Boolean = true,
    val refreshShop: Boolean = true,
    val refreshFromArea: Int = 5,
    val normalWinRate: Int = 95,
    val extremeWinRate: Int = 70,
    val personalRoleScores: Map<String, Int> = emptyMap(),
    /**
     * Per-guild opening picks, replacing the shipped plan: guild id -> three slots, each an
     * ordered candidate list. The first candidate a slot finds on screen wins, so the order is the
     * user's preference order. Guilds left out here keep the shipped plan.
     */
    val openingRosters: Map<Int, List<List<String>>> = emptyMap(),
    /**
     * Preferred relic marks, strongest first. Empty keeps the built-in ordering.
     *
     * Only the choices the policy is otherwise indifferent about are affected; reaching a 15 tier
     * immediately still wins regardless of order.
     */
    val relicMarkPriority: List<LabyrinthRelicMark> = emptyList(),
    /** 特别装备模式；默认 [LabyrinthSpecialEquipmentMode.OFF]，旧配置缺字段时同样得到「关闭」。 */
    val specialEquipmentMode: LabyrinthSpecialEquipmentMode = LabyrinthSpecialEquipmentMode.OFF,
    /**
     * 需要自动穿戴特别装备的关卡类型，取值就是既有的 [LabyrinthCombatKind]：普通 = NORMAL、
     * EX = EX、BOSS = BOSS），不另造第二套枚举或字符串。
     *
     * 默认三类全选：一旦选中「自动穿戴」就必须有可执行的关卡类型，空集合是校验错误。
     * 这个集合是**显式**的——空集合在执行侧一律解释为「不执行」，绝不等价于「全部关卡」。
     */
    val specialEquipmentKinds: Set<LabyrinthCombatKind> =
        setOf(LabyrinthCombatKind.NORMAL, LabyrinthCombatKind.EX, LabyrinthCombatKind.BOSS),
) {
    fun validationError(): String? = when {
        specialEquipmentMode == LabyrinthSpecialEquipmentMode.AUTO_EQUIP && specialEquipmentKinds.isEmpty() ->
            SPECIAL_EQUIPMENT_EMPTY_KINDS_ERROR
        listOf(playerWeight, damageWeight, survivalWeight, functionWeight, formationWeight,
            cohesionWeight, secondTeamWeight, thirdTeamWeight, normalWinRate, extremeWinRate)
            .any { it !in 0..100 } -> "权重和胜率必须在 0–100 之间"
        damageWeight + survivalWeight + functionWeight + formationWeight + cohesionWeight == 0 ->
            "系统评分至少保留一项非零权重"
        duplicateRolePenalty !in 0..30 -> "重复纯职能扣分必须在 0–30 之间"
        mixedDamagePenalty !in 0..30 -> "物法混编扣分必须在 0–30 之间"
        effectiveCharacterSystemBonus !in 0..30 -> "有效效果角色系统加分必须在 0–30 之间"
        singleBossRetryCountBeforeMulti !in 0..10 -> "单队Boss重试次数必须在 0–10 之间"
        baselineLastArea !in 0..5 -> "补基础层数的截止区域必须在 0–5 之间"
        baselineTarget !in 1..4 -> "基础目标层数必须在 1–4 之间"
        debuffPivotMinimum !in 5..15 -> "转追削弱的最低层数必须在 5–15 之间"
        debuffPivotTolerance !in 0..5 -> "削弱落后容忍层数必须在 0–5 之间"
        refreshFromArea !in 1..5 -> "商店刷新起始区域必须在 1–5 之间"
        personalRoleScores.any { (id, score) -> !id.matches(Regex("[0-9]{4,6}")) || score !in 0..100 } ->
            "个人角色评分必须使用有效角色 ID，分数在 0–100 之间"
        openingRosterError() != null -> openingRosterError()
        relicMarkPriority.distinct().size != relicMarkPriority.size -> "遗物优先级不能重复"
        else -> null
    }

    /** First problem across every configured guild, named so the settings screen can show it. */
    fun openingRosterError(): String? = openingRosters.firstNotNullOfOrNull { (guildId, slots) ->
        val guild = LabyrinthOpeningRosterCatalog.configs[guildId]
            ?: return@firstNotNullOfOrNull "开局方案包含未知公会 $guildId"
        openingRosterOverrideError(slots)?.let { "${guild.guildName}：$it" }
    }

    fun applyTo(document: LabyrinthRoleDecisionDocument): LabyrinthRoleDecisionDocument {
        require(validationError() == null)
        val total = (damageWeight + survivalWeight + functionWeight + formationWeight + cohesionWeight).toDouble()
        return document.copy(
            characters = if (personalRoleScores.isEmpty()) document.characters else document.characters.map { role ->
                personalRoleScores[role.characterId]?.let { role.copy(personalScore = it.toDouble()) } ?: role
            },
            scoring = document.scoring.copy(
                attributeDamageBonus = if (attributePairingBonusEnabled) {
                    document.scoring.attributeDamageBonus
                } else {
                    document.scoring.attributeDamageBonus.keys.associateWith { 0.0 }
                },
                playerWeight = playerWeight / 100.0,
                systemDamageWeight = damageWeight / total,
                systemSurvivalWeight = survivalWeight / total,
                systemFunctionWeight = functionWeight / total,
                systemFormationWeight = formationWeight / total,
                systemCohesionWeight = cohesionWeight / total,
                duplicatePureRolePenalty = duplicateRolePenalty.toDouble(),
                battleMixedDamagePenalty = mixedDamagePenalty.toDouble(),
                encounterEffectiveCharacterBonus = effectiveCharacterSystemBonus.toDouble(),
            ),
            roleChoice = document.roleChoice.copy(
                candidateTeamWeights = listOf(1.0, secondTeamWeight / 100.0, thirdTeamWeight / 100.0),
            ),
        )
    }

    fun relicPolicy() = LabyrinthRelicChoicePolicy(LabyrinthRelicChoicePolicyConfig(
        baselineTarget = baselineTarget,
        debuffPivotMinimum = debuffPivotMinimum,
        debuffPivotTolerance = debuffPivotTolerance,
        baselineLastArea = baselineLastArea,
        markPriority = relicMarkPriority.distinct(),
    ))

    /**
     * 单一判据：本次战斗的关卡类型是否需要自动穿戴特别装备。
     *
     * 设置页里三个多选项的勾选状态和自动执行的触发判定都必须调用本函数，不得各自复写一份规则。
     * 空集合在这里返回 false —— 即「不执行」，而不是「全部关卡」。
     */
    fun autoEquipsSpecialEquipment(kind: LabyrinthCombatKind): Boolean =
        specialEquipmentMode == LabyrinthSpecialEquipmentMode.AUTO_EQUIP && kind in specialEquipmentKinds

    /**
     * 设置页「保存配置」按钮的启用判据：草稿校验通过、与已保存配置不同、且不在保存中。
     * 界面直接调用它，单测钉住同一份表达式，避免界面判定与写入判定分叉。
     */
    internal fun canSaveDraft(saved: LabyrinthStrategySettings, saving: Boolean): Boolean =
        !saving && validationError() == null && this != saved

    companion object {
        /**
         *勾选「自动穿戴」却一个关卡类型都没勾选时的中文错误文案。
         * 设置页提示与单测引用同一常量，避免文案各自漂移。
         */
        const val SPECIAL_EQUIPMENT_EMPTY_KINDS_ERROR =
            "已开启特别装备自动穿戴，请至少勾选一个关卡类型（普通/EX/BOSS）"
    }
}

/** Read one immutable snapshot at session start; saving never mutates a running planner. */
data class LabyrinthStrategySnapshot(
    val settings: LabyrinthStrategySettings,
    val roleRuntime: LabyrinthRoleDecisionRuntime?,
)

internal object LabyrinthStrategySettingsCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun decode(raw: String?): LabyrinthStrategySettings = runCatching {
        raw?.let { json.decodeFromString<LabyrinthStrategySettings>(it) }
            ?.takeIf { it.validationError() == null }
    }.getOrNull() ?: LabyrinthStrategySettings()

    fun encode(settings: LabyrinthStrategySettings): String {
        require(settings.validationError() == null)
        return encodeDraft(settings)
    }

    fun encodeDraft(settings: LabyrinthStrategySettings): String =
        json.encodeToString(LabyrinthStrategySettings.serializer(), settings)

    fun decodeDraft(raw: String): LabyrinthStrategySettings =
        json.decodeFromString<LabyrinthStrategySettings>(raw)
}
