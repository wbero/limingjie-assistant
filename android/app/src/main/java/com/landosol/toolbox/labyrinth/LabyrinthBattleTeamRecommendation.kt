package com.landosol.toolbox.labyrinth

data class LabyrinthRecommendedTeamMember(
    val characterId: String,
    val displayName: String,
)

/** Five-member recommendation consumed by the guarded battle-team executor. */
data class LabyrinthBattleTeamRecommendation(
    val members: List<LabyrinthRecommendedTeamMember>,
    val damageType: LabyrinthTeamDamageType,
    val score: Double,
    val vanguard: LabyrinthRecommendedTeamMember,
    val survivalAnchor: LabyrinthRecommendedTeamMember?,
    val reasons: List<String>,
    val defenseMarkStacks: Int,
    val targetCount: Int,
    /** Total Boss teams the planner will field this attempt, tank-led safe teams plus damage supplements. */
    val plannedBossTeamCount: Int = 1,
    /** Leading teams that passed the vanguard survival gate; the rest of [plannedBossTeamCount] are supplements. */
    val safeBossTeamCount: Int = plannedBossTeamCount,
    val excludedIncompleteCharacterIds: List<String> = emptyList(),
) {
    init {
        require(members.size in 1..5)
        require(members.distinctBy(LabyrinthRecommendedTeamMember::characterId).size == members.size)
        require(vanguard.characterId in members.map(LabyrinthRecommendedTeamMember::characterId))
        require(survivalAnchor == null || survivalAnchor.characterId in members.map(LabyrinthRecommendedTeamMember::characterId))
        require(defenseMarkStacks >= 0)
        require(targetCount >= 1)
        require(plannedBossTeamCount in 1..3)
        require(safeBossTeamCount in 0..plannedBossTeamCount)
    }

    val damageTypeLabel: String
        get() = when (damageType) {
            LabyrinthTeamDamageType.PHYSICAL -> "物理"
            LabyrinthTeamDamageType.MAGIC -> "法术"
            LabyrinthTeamDamageType.MIXED -> "混合"
            LabyrinthTeamDamageType.NONE -> "无有效输出"
        }

    fun overlayLines(): List<String> = listOf(
        "推荐第一队：" + members.joinToString(" / ") { "${it.characterId} ${it.displayName}" },
        "体系：$damageTypeLabel · 评分：${formatBattleTeamScore(score)} · " +
            "一号位：${vanguard.characterId} ${vanguard.displayName} · " +
            "生存锚点：${survivalAnchor?.let { "${it.characterId} ${it.displayName}" } ?: "无"}",
        "推荐原因：" + reasons.take(3).joinToString("；").ifBlank { "当前角色池最多五人的最高分队伍" },
    )

    /** Compact running-overlay summary. Full details remain available while paused and in logs. */
    fun compactOverlayLine(): String =
        "主力：$damageTypeLabel ${formatBattleTeamScore(score)} · 一号位 ${vanguard.displayName}"

    fun logText(): String = buildString {
        append(overlayLines().joinToString(" | "))
        append(" | 守备=")
        append(defenseMarkStacks)
        append(" | 目标数=")
        append(if (targetCount >= 3) "3+" else targetCount)
        append(" | Boss安全队数=")
        append(plannedBossTeamCount)
        if (excludedIncompleteCharacterIds.isNotEmpty()) {
            append(" | 严格资料排除=")
            append(excludedIncompleteCharacterIds.joinToString())
        }
    }
}

internal fun labyrinthBattleTeamSignature(characterIds: Collection<String>): String =
    characterIds
        .map(::canonicalLabyrinthRoleId)
        .distinct()
        .sorted()
        .joinToString(",")

sealed interface LabyrinthBattleTeamRecommendationResult {
    data class Ready(val recommendation: LabyrinthBattleTeamRecommendation) :
        LabyrinthBattleTeamRecommendationResult

    data class Unavailable(val reason: String) : LabyrinthBattleTeamRecommendationResult
}

/** Bridges persisted run-state IDs to the production team planner. */
class LabyrinthBattleTeamRecommendationPlanner(
    private val profiles: Map<String, LabyrinthRoleProfile>,
    private val teamPlanSearcher: LabyrinthTeamPlanSearcher,
) {
    fun initialRecommendation(
        acquiredCharacterIds: Collection<String>,
        context: LabyrinthRoleDecisionContext,
        requestedBossTeamCount: Int = 1,
        /**
         * Boss follow-up slot (team 2/3): the tank-led teams are already committed, so the team
         * for this slot may lead without a qualified vanguard rather than leave the slot empty.
         */
        allowSupplementLead: Boolean = false,
    ): LabyrinthBattleTeamRecommendationResult {
        require(requestedBossTeamCount in 1..3)
        val canonicalIds = acquiredCharacterIds.map(::canonicalLabyrinthRoleId).distinct()
        val knownProfiles = profiles.withKnownRoleIdentities(canonicalIds)
        val resolved = canonicalIds.map(knownProfiles::getValue)
        val incompleteIds = resolved
            .filterNot(LabyrinthRoleProfile::isStrictDecisionReady)
            .map(LabyrinthRoleProfile::characterId)
            .sorted()
        if (resolved.isEmpty()) {
            return LabyrinthBattleTeamRecommendationResult.Unavailable("没有已获得的可用角色")
        }
        // A pool with nobody fit to lead is not a reason to skip the fight: the run still has to
        // play it. Fall back to the vanguard-less composition the Boss follow-up slots already use
        // rather than blocking 编组 (2026-09-19, by request). The planner still never promotes a
        // 掩护者 who fails the survival line into a "safe" lead; it just says so and plays on.
        val poolHasVanguard = resolved.any { it.isEligibleBattleVanguard(context) }
        val supplementLead = allowSupplementLead || !poolHasVanguard

        val plan = if (requestedBossTeamCount > 1 || allowSupplementLead) {
            teamPlanSearcher.bossMultiTeamSearch(
                roster = resolved,
                context = context,
                requestedTeams = requestedBossTeamCount,
            )
        } else {
            teamPlanSearcher.initialSearch(resolved, context)
        }
        if (!supplementLead && plan.teams.isNotEmpty() && plan.safeTeamCount == 0) {
            return LabyrinthBattleTeamRecommendationResult.Unavailable(
                "当前角色池中没有满足生存资格的一号位；不再仅按掩护者职阶强行上T",
            )
        }
        val evaluation = plan.teams.firstOrNull()
            ?: return LabyrinthBattleTeamRecommendationResult.Unavailable(
                plan.reason + resolved.filter { it.position == null }.takeIf { it.isNotEmpty() }?.let {
                    "；无法确认一号位站位关系的角色：${it.joinToString { role -> role.displayName }}"
                }.orEmpty(),
            )
        val expectedTeamSize = minOf(TEAM_SIZE, resolved.size)
        if (evaluation.members.size != expectedTeamSize) {
            return LabyrinthBattleTeamRecommendationResult.Unavailable(
                "Boss战斗规划未返回预期的${expectedTeamSize}人队伍：${plan.reason}",
            )
        }
        val members = evaluation.members.map { it.toRecommendedMember() }
        val memberById = members.associateBy(LabyrinthRecommendedTeamMember::characterId)
        val vanguard = requireNotNull(memberById[evaluation.vanguardCharacterId])
        val leadIsSupplement = plan.safeTeamCount == 0
        if (!leadIsSupplement && profiles[evaluation.vanguardCharacterId]?.isEligibleBattleVanguard(context) != true) {
            return LabyrinthBattleTeamRecommendationResult.Unavailable(
                "自动战斗一号位生存资格校验失败，已拒绝进入自动编组",
            )
        }
        val survivalAnchor = evaluation.survivalAnchorCharacterId?.let(memberById::get)
        val reasons = buildList {
            addAll(evaluation.reasons)
            if (incompleteIds.isNotEmpty()) {
                add("部分角色资料不完整，按已有信息参与编组评分：${incompleteIds.joinToString()}")
            }
            if (leadIsSupplement) {
                add(
                    if (poolHasVanguard) {
                        "Boss后续队伍：无合格一号位，作为输出补刀队上场而不留空"
                    } else {
                        "角色池中没有满足生存资格的一号位；按无T阵容上场，不再空等"
                    },
                )
            }
            add(plan.reason)
        }.distinct()
        return LabyrinthBattleTeamRecommendationResult.Ready(
            LabyrinthBattleTeamRecommendation(
                members = members,
                damageType = evaluation.damageType,
                score = evaluation.score,
                vanguard = vanguard,
                survivalAnchor = survivalAnchor,
                reasons = reasons,
                defenseMarkStacks = context.defenseMarkStacks,
                targetCount = context.targetCount,
                plannedBossTeamCount = plan.teams.size.coerceIn(1, 3),
                safeBossTeamCount = plan.safeTeamCount.coerceIn(0, plan.teams.size.coerceIn(1, 3)),
            ),
        )
    }

    /**
     * After an EX has already failed twice, treat the repeated wipe as a survival signal instead
     * of merely asking the generic fallback search for another nearby high-score formation.
     *
     * Keep the qualified vanguard and every official “有效效果” member intact, remove the lowest-rated remaining
     * non-vanguard from the most recently failed team, and add one healer from the owned roster.
     * Guide requirements remain scoring preferences even when capabilities are insufficient.
     * The resulting five roles are then re-evaluated by the normal scorer so all
     * existing position/frontmost-tank safety checks still apply.
     */
    private fun exSecondFailureHealerRecovery(
        roster: List<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
        failedTeamSignatures: Set<String>,
        lastFailedTeamSignature: String,
    ): LabyrinthBattleTeamRecommendationResult.Ready? {
        val failedIds = lastFailedTeamSignature
            .split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .map(::canonicalLabyrinthRoleId)
            .distinct()
        if (failedIds.size != TEAM_SIZE) return null

        val byId = roster.associateBy(LabyrinthRoleProfile::characterId)
        val failedTeam = failedIds.mapNotNull(byId::get)
        if (failedTeam.size != TEAM_SIZE) return null

        val protectedEffectiveIds = context.effectiveCharacterIds
            .map(::canonicalLabyrinthRoleId)
            .toSet()
        val byScore = compareBy<LabyrinthRoleProfile> { it.effectiveUserScore ?: Double.NEGATIVE_INFINITY }
            .thenBy { it.characterId }
        val nonVanguard = failedTeam.filterNot { it.isEligibleBattleVanguard(context) }
        // Effective-effect roles go last, not never: 2026-09-17 a team of five effective roles
        // made the retry refuse every swap and the run stalled on the failure page.
        val preferred = nonVanguard.filterNot { it.characterId in protectedEffectiveIds }.sortedWith(byScore)
        val lastResort = nonVanguard.filter { it.characterId in protectedEffectiveIds }.sortedWith(byScore)
        val removable = preferred + lastResort
        if (removable.isEmpty()) return null

        val failedIdSet = failedTeam.map(LabyrinthRoleProfile::characterId).toSet()
        val healers = roster
            .filterNot { it.characterId in failedIdSet }
            .filter { it.isExRetryHealer() }
            .sortedWith(
                compareByDescending<LabyrinthRoleProfile> { it.roleClass == "治疗者" }
                    .thenByDescending { it.exRetryHealingStrength() }
                    .thenByDescending { it.effectiveUserScore ?: 0.0 }
                    .thenBy { it.characterId },
            )
        if (healers.isEmpty()) return null

        for (removed in removable) {
            for (healer in healers) {
                val candidate = failedTeam.filterNot { it.characterId == removed.characterId } + healer
                val signature = labyrinthBattleTeamSignature(candidate.map(LabyrinthRoleProfile::characterId))
                if (signature in failedTeamSignatures) continue

                val plan = teamPlanSearcher.initialSearch(
                    roster = candidate,
                    context = context.copy(survivalRecovery = true),
                )
                val evaluation = plan.teams.singleOrNull() ?: continue
                if (evaluation.members.size != TEAM_SIZE) continue
                val members = evaluation.members.map { it.toRecommendedMember() }
                val memberById = members.associateBy(LabyrinthRecommendedTeamMember::characterId)
                val vanguard = memberById[evaluation.vanguardCharacterId] ?: continue
                if (profiles[evaluation.vanguardCharacterId]?.isEligibleBattleVanguard(context) != true) continue

                return LabyrinthBattleTeamRecommendationResult.Ready(
                    LabyrinthBattleTeamRecommendation(
                        members = members,
                        damageType = evaluation.damageType,
                        score = evaluation.score,
                        vanguard = vanguard,
                        survivalAnchor = evaluation.survivalAnchorCharacterId?.let(memberById::get),
                        reasons = (
                            evaluation.reasons +
                                "EX第二次失败生存兜底：保留一号位候选，移除最低分非一号位 ${removed.displayName}" +
                                "（${formatBattleTeamScore(removed.effectiveUserScore ?: 0.0)}），加入治疗 ${healer.displayName}" +
                                "（治疗强度${formatBattleTeamScore(healer.exRetryHealingStrength())}）" +
                                (if (removed.characterId in protectedEffectiveIds) {
                                    "；失败队全员为有效效果角色，有效效果角色不得不换出最低分的一名"
                                } else {
                                    "；有效效果角色全部保留"
                                }) +
                                "；EX攻略条件按现有角色尽力满足"
                            ).distinct(),
                        defenseMarkStacks = context.defenseMarkStacks,
                        targetCount = context.targetCount,
                    ),
                )
            }
        }
        return null
    }

    private fun LabyrinthRoleProfile.isExRetryHealer(): Boolean =
        roleClass == "治疗者" || exRetryHealingStrength() >= EX_RETRY_HEALER_MIN_STRENGTH

    private fun LabyrinthRoleProfile.exRetryHealingStrength(): Double = maxOf(
        functions.healing ?: 0.0,
        functions.regeneration ?: 0.0,
        functions.pureHealer ?: 0.0,
    )

    fun retryRecommendation(
        acquiredCharacterIds: Collection<String>,
        context: LabyrinthRoleDecisionContext,
        failedTeamSignatures: Set<String>,
        retryNumber: Int = 1,
        lastFailedTeamSignature: String? = null,
        requestedBossTeamCount: Int = 1,
        allowSupplementLead: Boolean = false,
    ): LabyrinthBattleTeamRecommendationResult {
        require(requestedBossTeamCount in 1..3)
        val canonicalIds = acquiredCharacterIds.map(::canonicalLabyrinthRoleId).distinct()
        val knownProfiles = profiles.withKnownRoleIdentities(canonicalIds)
        val resolved = canonicalIds.map(knownProfiles::getValue)
        if (resolved.size < TEAM_SIZE) {
            return LabyrinthBattleTeamRecommendationResult.Unavailable(
                "战斗失败后可用角色不足${TEAM_SIZE}名，无法生成完整重试队伍",
            )
        }
        // A retry with a vanguard-less team beats leaving the run parked on the failure page
        // (2026-09-19, by request; same fallback as the first attempt).
        val retryPoolHasVanguard = resolved.any { it.isEligibleBattleVanguard(context) }
        val retrySupplementLead = allowSupplementLead || !retryPoolHasVanguard

        val isolatedRetryPlan = if (context.encounterStrategy?.vanguardIsolationRadius != null) {
            teamPlanSearcher.fallbackSearch(resolved, context, failedTeamSignatures)
        } else null

        if (context.encounterStrategy != null && retryNumber >= 2 && lastFailedTeamSignature != null) {
            val recovery = exSecondFailureHealerRecovery(
                roster = resolved,
                context = context,
                failedTeamSignatures = failedTeamSignatures,
                lastFailedTeamSignature = lastFailedTeamSignature,
            )
            val recoveryRisk = recovery?.let { result ->
                labyrinthEncounterFormationRisk(result.recommendation.members.map {
                    knownProfiles.getValue(it.characterId)
                }, context)
            }
            val bestRetryRisk = isolatedRetryPlan?.teams?.firstOrNull()?.let {
                labyrinthEncounterFormationRisk(it.members, context)
            }
            if (recovery != null && (bestRetryRisk == null || requireNotNull(recoveryRisk) <= bestRetryRisk)) {
                return recovery
            }
            // No unfailed healer swap is possible, or it puts more allies around the front
            // than the best available retry. Fall through to the fallback search.
        }

        val plan = if (requestedBossTeamCount > 1 || allowSupplementLead) {
            teamPlanSearcher.bossMultiTeamSearch(
                roster = resolved,
                context = context,
                requestedTeams = requestedBossTeamCount,
                excludedTeamSignatures = failedTeamSignatures,
                survivalRecovery = true,
            )
        } else {
            isolatedRetryPlan ?: teamPlanSearcher.fallbackSearch(
                roster = resolved,
                context = context,
                excludedTeamSignatures = failedTeamSignatures,
            )
        }
        if (!retrySupplementLead && plan.teams.isNotEmpty() && plan.safeTeamCount == 0) {
            return LabyrinthBattleTeamRecommendationResult.Unavailable(
                "战斗失败后角色池中没有满足生存资格的一号位；不能生成安全重试队伍",
            )
        }
        val retryLeadIsSupplement = plan.safeTeamCount == 0
        val evaluation = plan.teams.firstOrNull()
            ?: return LabyrinthBattleTeamRecommendationResult.Unavailable(plan.reason)
        if (evaluation.members.size != TEAM_SIZE) {
            return LabyrinthBattleTeamRecommendationResult.Unavailable(
                "失败重试规划未返回完整${TEAM_SIZE}人队伍：${plan.reason}",
            )
        }
        val signature = labyrinthBattleTeamSignature(
            evaluation.members.map(LabyrinthRoleProfile::characterId),
        )
        if (signature in failedTeamSignatures) {
            return LabyrinthBattleTeamRecommendationResult.Unavailable(
                "失败重试规划仍返回已经失败过的同一阵容，已拒绝再次提交",
            )
        }
        val members = evaluation.members.map { it.toRecommendedMember() }
        val memberById = members.associateBy(LabyrinthRecommendedTeamMember::characterId)
        val vanguard = memberById[evaluation.vanguardCharacterId]
            ?: return LabyrinthBattleTeamRecommendationResult.Unavailable("失败重试阵容缺少可确认的一号位")
        if (!retryLeadIsSupplement && profiles[evaluation.vanguardCharacterId]?.isEligibleBattleVanguard(context) != true) {
            return LabyrinthBattleTeamRecommendationResult.Unavailable(
                "失败重试一号位生存资格校验失败",
            )
        }
        return LabyrinthBattleTeamRecommendationResult.Ready(
            LabyrinthBattleTeamRecommendation(
                members = members,
                damageType = evaluation.damageType,
                score = evaluation.score,
                vanguard = vanguard,
                survivalAnchor = evaluation.survivalAnchorCharacterId?.let(memberById::get),
                reasons = (
                    evaluation.reasons + plan.reason + "失败重试：禁止复用已失败的完整阵容" +
                        if (retryLeadIsSupplement && !retryPoolHasVanguard) {
                            listOf("角色池中没有满足生存资格的一号位；按无T阵容重试")
                        } else {
                            emptyList()
                        }
                    ).distinct(),
                defenseMarkStacks = context.defenseMarkStacks,
                targetCount = context.targetCount,
                plannedBossTeamCount = plan.teams.size.coerceIn(1, 3),
                safeBossTeamCount = plan.safeTeamCount.coerceIn(0, plan.teams.size.coerceIn(1, 3)),
            ),
        )
    }

    private fun LabyrinthRoleProfile.toRecommendedMember() = LabyrinthRecommendedTeamMember(
        characterId = characterId,
        displayName = displayName,
    )

    private companion object {
        const val TEAM_SIZE = 5
        const val EX_RETRY_HEALER_MIN_STRENGTH = 50.0
    }
}

internal fun labyrinthBattleTeamSelectionMessage(
    combatContext: LabyrinthCombatContext?,
    recommendation: LabyrinthBattleTeamRecommendation?,
    unavailableReason: String?,
    selectionPlan: LabyrinthBattleTeamSelectionPlan? = null,
): String {
    val waiting = combatContext?.let { context ->
        when (context.kind) {
            LabyrinthCombatKind.BOSS -> "准备 Boss 第${context.teamIndex}/3 队编组"
            LabyrinthCombatKind.NORMAL -> "准备普通战编组"
            LabyrinthCombatKind.EX -> "准备 EX 战编组"
        }
    } ?: "准备战斗编组"
    return listOfNotNull(
        waiting,
        recommendation?.let {
            "推荐第一队：${it.members.joinToString("、") { member -> member.displayName }}；" +
                "${it.damageTypeLabel}；评分${formatBattleTeamScore(it.score)}" +
                if (combatContext?.kind == LabyrinthCombatKind.BOSS) {
                    "；本轮计划${it.plannedBossTeamCount}队（安全${it.safeBossTeamCount}队）"
                } else {
                    ""
                }
        },
        unavailableReason?.let { "第一队建议不可用：$it" },
        selectionPlan?.let { plan ->
            when {
                plan.teamReady -> "第一队编组已与推荐一致，准备开始战斗"
                plan.readyToExecute ->
                    "自动编组：保持${plan.alreadyCorrectIds.size}、取消${plan.needDeselectIds.size}、" +
                        "选择${plan.needSelectIds.size}"
                else -> "自动编组阻塞：${plan.blockedReason ?: "未知原因"}"
            }
        },
    ).joinToString("；")
}

private fun formatBattleTeamScore(value: Double): String = "%.2f".format(value)
