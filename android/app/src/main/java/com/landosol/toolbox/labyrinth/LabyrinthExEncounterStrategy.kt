package com.landosol.toolbox.labyrinth

import kotlin.math.max

/**
 * Stable combat capabilities referenced by EX guide rules.
 *
 * These are deliberately independent from the in-game job/class. A 妨碍者 often has CONTROL,
 * but a class label alone is only a fallback when the generated combat profile already marks the
 * role as a controller. DOT/shield/taunt remain explicit facts so the planner never invents them.
 */
enum class LabyrinthEncounterCapability(val label: String) {
    DOT("持续伤害"),
    CONTROL("控制/无法行动"),
    SHIELD("护盾"),
    HEAL("治疗/持续回复"),
    TAUNT("嘲讽"),
    AOE("AOE/多目标输出"),
    WIDE_AOE("3+目标覆盖"),
    BACKLINE_AOE("后排锚定AOE"),
    PUSH("击退敌人"),
    PULL("拉近敌人"),
    BURST("快速爆发"),
}

data class LabyrinthEncounterFallbackPreference(
    /** The fallback becomes useful when fewer than this many members satisfy [primaryCapability]. */
    val primaryCapability: LabyrinthEncounterCapability,
    val primaryTargetCount: Int,
    /** Any one of these capabilities can satisfy one fallback slot. */
    val anyOf: Set<LabyrinthEncounterCapability>,
    val maximumCount: Int = 1,
) {
    init {
        require(primaryTargetCount > 0)
        require(anyOf.isNotEmpty())
        require(maximumCount > 0)
    }
}

/**
 * Route-resolved Boss mechanics. Boss identity comes from the saved route quest id rather than
 * challenge-page OCR, so only encounters with confirmed guide semantics belong here.
 */
object LabyrinthBossEncounterCatalog {
    private val byUnitId = mapOf(
        319604 to LabyrinthExEncounterStrategy(
            id = "boss_frost_wolf",
            identityName = "冰霜魔狼",
            // The scorer treats every value >=3 as the shared 3+ target model.
            targetCount = 3,
            preferredCapabilities = setOf(
                LabyrinthEncounterCapability.AOE,
                LabyrinthEncounterCapability.DOT,
            ),
            notes = listOf(
                "攻略：多目标首领，需要全程安排群攻角色；首领受到的DOT伤害大幅提高，可用持续伤害补伤害",
            ),
        ),
    )

    fun forUnitId(unitId: Int?): LabyrinthExEncounterStrategy? = unitId?.let(byUnitId::get)
}

data class LabyrinthEncounterRequirement(
    /** A member satisfies the requirement when any capability in this set passes [minimumScore]. */
    val anyOf: Set<LabyrinthEncounterCapability>,
    val minimumCount: Int = 1,
    val minimumScore: Double = 50.0,
    /** Original guide metadata. Both hard and optional entries are runtime scoring preferences. */
    val hard: Boolean = true,
    val label: String,
) {
    init {
        require(anyOf.isNotEmpty())
        require(minimumCount > 0)
        require(minimumScore in 0.0..100.0)
        require(label.isNotBlank())
    }
}

data class LabyrinthExEncounterStrategy(
    val id: String,
    /** Stable name seen either on the single-EX challenge page or in the slot-3 monster detail. */
    val identityName: String,
    /** Alternate guide/client spellings accepted only for identity matching. */
    val identityAliases: Set<String> = emptySet(),
    val targetCount: Int,
    val requirements: List<LabyrinthEncounterRequirement> = emptyList(),
    val preferredCapabilities: Set<LabyrinthEncounterCapability> = emptySet(),
    /**
     * Optional preferred member count per capability.  The default is one member, preserving the
     * old semantics.  Encounters that explicitly want a damage core built around a capability can
     * request more than one without turning it into an all-or-nothing hard requirement.
     */
    val preferredCapabilityMemberTargets: Map<LabyrinthEncounterCapability, Int> = emptyMap(),
    /** Conditional alternatives, e.g. displacement when a wide-AOE core cannot fully cover. */
    val fallbackPreferences: List<LabyrinthEncounterFallbackPreference> = emptyList(),
    val preferEffectiveCharacters: Boolean = true,
    /** Confirmed encounter damage type; UNKNOWN retains the generic survival model. */
    val enemyDamageType: LabyrinthEnemyDamageType = LabyrinthEnemyDamageType.UNKNOWN,
    /** Static starting-position heuristic; skills can move units after the battle starts. */
    val vanguardIsolationRadius: Int? = null,
    val notes: List<String> = emptyList(),
) {
    init {
        require(id.isNotBlank())
        require(identityName.isNotBlank())
        require(targetCount >= 1)
        require(vanguardIsolationRadius == null || vanguardIsolationRadius > 0)
        preferredCapabilityMemberTargets.forEach { (capability, count) ->
            require(capability in preferredCapabilities)
            require(count > 0)
        }
    }
}

/** Current fixed EX catalogue. Numerical buffs can change without changing encounter identity. */
object LabyrinthExEncounterCatalog {
    // Single-target EX: the challenge page itself normally exposes the boss name + HP bar.
    val singleTarget = listOf(
        LabyrinthExEncounterStrategy(
            id = "ghost_lord",
            identityName = "幽灵领主",
            targetCount = 1,
            enemyDamageType = LabyrinthEnemyDamageType.MAGIC,
            vanguardIsolationRadius = 300,
            preferredCapabilities = setOf(LabyrinthEncounterCapability.HEAL),
            notes = listOf("攻略：1个坦克+4个后排，坦克与后排尽量拉开，降低以坦克为中心AOE波及"),
        ),
        LabyrinthExEncounterStrategy(
            id = "good_friend_x",
            identityName = "好朋友X",
            targetCount = 1,
            requirements = listOf(
                LabyrinthEncounterRequirement(
                    anyOf = setOf(LabyrinthEncounterCapability.DOT),
                    minimumCount = 2,
                    label = "至少2名持续伤害角色",
                ),
            ),
            notes = listOf("攻略：持续伤害是核心机制；防御很高，不满足DOT条件时不应按普通高分队硬撞"),
        ),
        LabyrinthExEncounterStrategy(
            id = "island_whale",
            identityName = "岛鲸",
            targetCount = 1,
            notes = listOf("攻略：常规阵容；若自动仍打不过，优先检查护盾/无敌窗口而非盲目换高分输出"),
        ),
        LabyrinthExEncounterStrategy(
            id = "antimatter_beast",
            identityName = "反物质兽",
            targetCount = 1,
            notes = listOf("攻略：常规阵容；随机UB效果更偏战斗过程机制，不作为首轮编组硬约束"),
        ),
        LabyrinthExEncounterStrategy(
            id = "giant_tuna",
            identityName = "巨型吞拿鱼",
            targetCount = 1,
            preferredCapabilities = setOf(LabyrinthEncounterCapability.HEAL),
            notes = listOf("攻略：前3位角色需要更高肉度，首轮提高生存/续航权重"),
        ),
        LabyrinthExEncounterStrategy(
            id = "lava_dragon",
            identityName = "熔岩龙",
            targetCount = 1,
            notes = listOf("攻略：尽量选择前中排且站位接近的阵容，降低分散导致的机制损失"),
        ),
    )

    /**
     * Special EX: exactly two detail-capable combat objects are shown on the challenge page.
     * The left object's exact relic identity is not confirmed yet; the right object carries the
     * encounter identity and is the only one used to distinguish these encounters.
     */
    val specialDualTarget = listOf(
        LabyrinthExEncounterStrategy(
            id = "queen_bee",
            identityName = "黄蜂女王",
            identityAliases = setOf("蜂后"),
            targetCount = 2,
            requirements = listOf(
                LabyrinthEncounterRequirement(
                    anyOf = setOf(LabyrinthEncounterCapability.CONTROL),
                    minimumCount = 1,
                    label = "至少1名可靠控制角色",
                ),
            ),
            preferredCapabilities = setOf(LabyrinthEncounterCapability.BURST),
            notes = listOf("特殊EX：未知遗物 + 黄蜂女王；攻略偏好控制关键目标并快速输出"),
        ),
        LabyrinthExEncounterStrategy(
            id = "misora",
            identityName = "美空",
            targetCount = 2,
            preferredCapabilities = setOf(LabyrinthEncounterCapability.AOE, LabyrinthEncounterCapability.BURST),
            notes = listOf("特殊EX：未知遗物 + 美空；优先全体/越前排AOE与高总输出"),
        ),
    )

    // Multi-monster EX: slot 3 is unique in the current guide/version and is used as the primary
    // encounter fingerprint. The sixth row has now been confirmed in-game as 破坏・遗物.
    val multiTarget = listOf(
        LabyrinthExEncounterStrategy(
            id = "multi_explosion_relic",
            identityName = "爆炸・遗物",
            targetCount = 5,
            requirements = listOf(
                LabyrinthEncounterRequirement(
                    anyOf = setOf(LabyrinthEncounterCapability.SHIELD, LabyrinthEncounterCapability.CONTROL),
                    minimumCount = 1,
                    label = "护盾或控制位至少1名",
                ),
            ),
            preferredCapabilities = setOf(LabyrinthEncounterCapability.HEAL),
            notes = listOf("攻略：魔物具有AOE/机器人高伤，开局快速展开护盾或持续控制，持续回复优先"),
        ),
        LabyrinthExEncounterStrategy(
            id = "multi_kurumi_shadow",
            identityName = "胡桃的暗影",
            targetCount = 5,
            requirements = listOf(
                LabyrinthEncounterRequirement(
                    anyOf = setOf(LabyrinthEncounterCapability.DOT),
                    minimumCount = 2,
                    label = "至少2名持续伤害角色",
                ),
            ),
            notes = listOf("攻略：机器人存活时敌方全队高减伤，必须优先用DOT处理机制"),
        ),
        LabyrinthExEncounterStrategy(
            id = "multi_ny_karyl_shadow",
            identityName = "凯露（新年）的暗影",
            targetCount = 5,
            requirements = listOf(
                LabyrinthEncounterRequirement(
                    anyOf = setOf(
                        LabyrinthEncounterCapability.WIDE_AOE,
                        LabyrinthEncounterCapability.AOE,
                    ),
                    minimumCount = 1,
                    label = "至少1名可靠群攻输出用于尽快击杀机器人",
                ),
            ),
            preferredCapabilities = setOf(
                LabyrinthEncounterCapability.WIDE_AOE,
                LabyrinthEncounterCapability.BACKLINE_AOE,
            ),
            preferredCapabilityMemberTargets = mapOf(
                LabyrinthEncounterCapability.WIDE_AOE to 2,
                LabyrinthEncounterCapability.BACKLINE_AOE to 1,
            ),
            fallbackPreferences = listOf(
                LabyrinthEncounterFallbackPreference(
                    primaryCapability = LabyrinthEncounterCapability.WIDE_AOE,
                    primaryTargetCount = 3,
                    // The full-heal robot is enemy slot 5 (backmost). Pull can move the back line
                    // forward directly; push can move front enemies backward and compress the
                    // enemy formation, which can also bring slot 5 into a narrower AOE footprint.
                    anyOf = setOf(
                        LabyrinthEncounterCapability.PUSH,
                        LabyrinthEncounterCapability.PULL,
                    ),
                    maximumCount = 1,
                ),
            ),
            notes = listOf(
                "攻略：机器人每次行动都会回复自身和友军100%生命值，不能靠持续硬打磨过去；优先用群攻尽快切掉机器人",
                "回血机器人位于敌方第5位（最后排）；若群攻无法命中，可用拉近把后排带前，或用击退把前排往后压缩敌方站位，让窄范围群攻覆盖到机器人",
            ),
        ),
        LabyrinthExEncounterStrategy(
            id = "multi_mifuyu_work_shadow",
            identityName = "美冬（工作服）的暗影",
            targetCount = 5,
            requirements = listOf(
                LabyrinthEncounterRequirement(
                    anyOf = setOf(LabyrinthEncounterCapability.TAUNT, LabyrinthEncounterCapability.SHIELD),
                    minimumCount = 1,
                    label = "嘲讽坦克或护盾位至少1名",
                ),
            ),
            preferredCapabilities = setOf(LabyrinthEncounterCapability.HEAL),
            notes = listOf("攻略：机器人高伤随机点名，嘲讽坦克/护盾奶用于保护高危目标"),
        ),
        LabyrinthExEncounterStrategy(
            id = "multi_ruka_shadow",
            identityName = "流夏的暗影",
            targetCount = 5,
            preferredCapabilities = setOf(LabyrinthEncounterCapability.SHIELD),
            notes = listOf("攻略：有护盾则优先携带，否则允许综合最优阵容通过"),
        ),
        LabyrinthExEncounterStrategy(
            id = "multi_destruction_relic",
            identityName = "破坏・遗物",
            targetCount = 5,
            preferredCapabilities = setOf(LabyrinthEncounterCapability.BURST),
            notes = listOf("攻略：快速输出，并保留能收残局的输出坦克"),
        ),
    )

    val all: List<LabyrinthExEncounterStrategy> = singleTarget + multiTarget + specialDualTarget

    fun matchObservedName(text: String?): LabyrinthExEncounterStrategy? {
        val observedVariants = encounterObservedNameVariants(text ?: return null)
        if (observedVariants.isEmpty()) return null
        return all
            .filterNot { it.identityName.startsWith("？？？") }
            .map { strategy ->
                val names = sequenceOf(strategy.identityName) + strategy.identityAliases.asSequence()
                strategy to names.maxOf { name ->
                    encounterIdentityNameVariants(name).maxOf { expected ->
                        observedVariants.maxOf { candidate ->
                            encounterNameSimilarity(expected, candidate)
                        }
                    }
                }
            }
            .filter { (_, score) -> score >= MIN_NAME_SCORE }
            .sortedByDescending { it.second }
            .let { matches ->
                val best = matches.firstOrNull() ?: return null
                val rival = matches.getOrNull(1)?.second ?: 0.0
                best.first.takeIf { best.second - rival >= MIN_NAME_MARGIN || best.second >= 0.96 }
            }
    }

    private fun normalizeEncounterName(value: String): String = value
        .replace('·', '・')
        .replace('•', '・')
        .replace("的暗影", "暗影")
        .replace('（', '(')
        .replace('）', ')')
        .map { character -> ENCOUNTER_OCR_EQUIVALENTS[character] ?: character }
        .filterNot(Char::isWhitespace)
        .joinToString("")
        .lowercase()

    /**
     * ML Kit returns all text blocks in the crop joined with newlines. Score each row separately so
     * a nearby level/weakness label does not lengthen the actual name and make a valid read fail.
     * Do not split arbitrary text within one row: accepting a known substring from a description
     * would make unrelated detail text look like encounter identity evidence.
     *
     * Monster-detail titles have a decorative blue curl immediately to the left of the name.
     * Android OCR can consistently turn a clipped piece of that curl into one ASCII glyph (the
     * live 爆炸・遗物 sample produced "C爆炸・遗物").  Repair only one non-CJK edge glyph; never
     * use arbitrary substring containment as an OCR shortcut.
     */
    private fun encounterObservedNameVariants(text: String): Set<String> = buildSet {
        text.lineSequence().forEach { line ->
            val observed = normalizeEncounterName(line)
            if (observed.isBlank()) return@forEach
            add(observed)
            addShadowBaseVariant(observed)
            if (observed.length >= 4 && observed.first().isEncounterEdgeNoise()) {
                observed.drop(1).also {
                    add(it)
                    addShadowBaseVariant(it)
                }
            }
            if (observed.length >= 4 && observed.last().isEncounterEdgeNoise()) {
                observed.dropLast(1).also {
                    add(it)
                    addShadowBaseVariant(it)
                }
            }
        }
    }

    /** The common "的暗影" suffix carries no identity and is often omitted by a narrow OCR row. */
    private fun encounterIdentityNameVariants(name: String): Set<String> = buildSet {
        val normalized = normalizeEncounterName(name)
        add(normalized)
        addShadowBaseVariant(normalized)
    }

    private fun MutableSet<String>.addShadowBaseVariant(value: String) {
        if (value.endsWith(SHADOW_SUFFIX) && value.length > SHADOW_SUFFIX.length + 1) {
            add(value.removeSuffix(SHADOW_SUFFIX))
        }
    }

    private fun Char.isEncounterEdgeNoise(): Boolean = code < 128 &&
        (isLetterOrDigit() || this in setOf(':', ';', '|', '[', ']', '{', '}', '<', '>', '_'))

    private fun encounterNameSimilarity(expected: String, observed: String): Double {
        if (expected == observed) return 1.0
        // Use the longer string as the denominator. The old expected-only denominator gave
        // "C爆炸・遗物" a perfect 1.0 simply because it contained the known name, and broad title
        // decorations/text could therefore masquerade as a clean identity match.
        val lcs = longestCommonSubsequence(expected, observed).toDouble() /
            max(1, max(expected.length, observed.length))
        val expectedBigrams = expected.windowed(2).toSet()
        val observedBigrams = observed.windowed(2).toSet()
        val dice = if (expectedBigrams.isEmpty() || observedBigrams.isEmpty()) 0.0 else {
            2.0 * expectedBigrams.intersect(observedBigrams).size /
                (expectedBigrams.size + observedBigrams.size).toDouble()
        }
        return max(lcs, dice)
    }

    private fun longestCommonSubsequence(left: String, right: String): Int {
        var previous = IntArray(right.length + 1)
        left.forEach { a ->
            val current = IntArray(right.length + 1)
            right.forEachIndexed { index, b ->
                current[index + 1] = if (a == b) previous[index] + 1
                else max(current[index], previous[index + 1])
            }
            previous = current
        }
        return previous.last()
    }

    private const val MIN_NAME_SCORE = 0.72
    private const val MIN_NAME_MARGIN = 0.08
    private const val SHADOW_SUFFIX = "暗影"

    /** Traditional glyphs that commonly appear in CN OCR output for names in this catalogue. */
    private val ENCOUNTER_OCR_EQUIVALENTS = mapOf(
        '凱' to '凯',
        '島' to '岛',
        '鯨' to '鲸',
        '靈' to '灵',
        '領' to '领',
        '質' to '质',
        '獸' to '兽',
        '魚' to '鱼',
        '龍' to '龙',
        '黃' to '黄',
        '遺' to '遗',
        '壞' to '坏',
    )
}

internal data class LabyrinthEncounterRequirementStatus(
    val requirement: LabyrinthEncounterRequirement,
    val matchedCount: Int,
    val unknownCount: Int,
) {
    val satisfied: Boolean get() = matchedCount >= requirement.minimumCount
}

internal fun LabyrinthRoleProfile.encounterCapabilityScore(
    capability: LabyrinthEncounterCapability,
): Double? = when (capability) {
    LabyrinthEncounterCapability.DOT -> functions.dot
    LabyrinthEncounterCapability.CONTROL -> listOfNotNull(
        functions.control,
        support.control,
        skillFacts.takeIf { facts -> facts.any { it.effect == LabyrinthSkillEffectType.CONTROL } }?.let { 80.0 },
    ).maxOrNull()
    LabyrinthEncounterCapability.SHIELD -> listOfNotNull(
        functions.shield,
        skillFacts.takeIf { facts -> facts.any { it.effect == LabyrinthSkillEffectType.SHIELD } }?.let { 80.0 },
    ).maxOrNull()
    LabyrinthEncounterCapability.HEAL -> listOfNotNull(
        functions.healing,
        functions.regeneration,
        skillFacts.takeIf { facts -> facts.any { it.effect in setOf(LabyrinthSkillEffectType.HEAL, LabyrinthSkillEffectType.REGENERATION) } }
            ?.let { 80.0 },
    ).maxOrNull()
    LabyrinthEncounterCapability.TAUNT -> functions.taunt
    LabyrinthEncounterCapability.AOE -> functions.aoeDamage
    // Coverage is a geometry fact. Never use three-target damage throughput as a substitute:
    // a narrow skill may deal excellent 3-target damage without ever reaching enemy slot 5.
    LabyrinthEncounterCapability.WIDE_AOE -> functions.wideAoeCoverage
    LabyrinthEncounterCapability.BACKLINE_AOE -> functions.backlineAoeCoverage
    LabyrinthEncounterCapability.PUSH -> listOfNotNull(
        functions.enemyPush,
        skillFacts.takeIf { facts -> facts.any { it.effect == LabyrinthSkillEffectType.PUSH } }?.let { 80.0 },
    ).maxOrNull()
    LabyrinthEncounterCapability.PULL -> listOfNotNull(
        functions.enemyPull,
        skillFacts.takeIf { facts -> facts.any { it.effect == LabyrinthSkillEffectType.PULL } }?.let { 80.0 },
    ).maxOrNull()
    LabyrinthEncounterCapability.BURST -> functions.burstDamage
}

internal fun evaluateEncounterRequirement(
    members: Collection<LabyrinthRoleProfile>,
    requirement: LabyrinthEncounterRequirement,
): LabyrinthEncounterRequirementStatus {
    var matched = 0
    var unknown = 0
    members.forEach { member ->
        val scores = requirement.anyOf.map(member::encounterCapabilityScore)
        if (scores.any { (it ?: Double.NEGATIVE_INFINITY) >= requirement.minimumScore }) {
            matched++
        } else if (scores.all { it == null }) {
            unknown++
        }
    }
    return LabyrinthEncounterRequirementStatus(requirement, matched, unknown)
}

/** Partial, confirmed coverage earns credit; unknown capability data never becomes a match. */
internal fun encounterRequirementContribution(
    members: Collection<LabyrinthRoleProfile>,
    strategy: LabyrinthExEncounterStrategy?,
): Double = strategy?.requirements?.sumOf { requirement ->
    val status = evaluateEncounterRequirement(members, requirement)
    minOf(status.matchedCount, requirement.minimumCount).toDouble() / requirement.minimumCount
} ?: 0.0

internal fun encounterRequirementShortfallReason(
    roster: Collection<LabyrinthRoleProfile>,
    strategy: LabyrinthExEncounterStrategy?,
): String? {
    strategy ?: return null
    val failures = strategy.requirements
        .map { evaluateEncounterRequirement(roster, it) }
        .filterNot(LabyrinthEncounterRequirementStatus::satisfied)
    if (failures.isEmpty()) return null
    return failures.joinToString("；") { status ->
        "${strategy.identityName}攻略偏好“${status.requirement.label}”：本队确认${status.matchedCount}/${status.requirement.minimumCount}" +
            if (status.unknownCount > 0) "，${status.unknownCount}名成员能力数据缺失；按现有角色继续编组"
            else "；按现有角色继续编组"
    }
}
