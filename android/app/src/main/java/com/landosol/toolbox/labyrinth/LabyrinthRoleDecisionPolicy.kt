package com.landosol.toolbox.labyrinth

import kotlinx.serialization.Serializable
import java.util.PriorityQueue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

@Serializable
enum class LabyrinthCharacterAttribute(val label: String) {
    FIRE("火"),
    WATER("水"),
    WIND("风"),
    LIGHT("光"),
    DARK("暗"),
    UNKNOWN("未知"),
}

/** Attribute table indexed by ordinal, for the scorer's allocation-free accumulation. */
private val ATTRIBUTE_SLOTS = LabyrinthCharacterAttribute.values()
private val ATTRIBUTE_SLOT_COUNT = ATTRIBUTE_SLOTS.size

/**
 * Worker threads for the formation search. Not the CPU count on purpose: the game shares the
 * virtual machine, and the fork this was ported from measured 4 threads at 0.58x wall-clock and
 * 8 at 0.45x, i.e. more threads made the whole run slower, not faster.
 */
private const val ROLE_SEARCH_THREADS = 4

/** Below this many combinations the thread hand-off costs more than it saves. */
private const val PARALLEL_MIN_COMBINATIONS = 512

/** One process-wide pool: a run calls the search many times and must not spin threads up each time. */
private val ROLE_SEARCH_EXECUTOR: ExecutorService = Executors.newFixedThreadPool(ROLE_SEARCH_THREADS) { runnable ->
    Thread(runnable, "labyrinth-role-search").apply { isDaemon = true }
}

/**
 * A role can join either physical or magic cores only when its combat contribution is genuinely
 * system-agnostic.  Damage dealers / breakers never become universal merely because their own
 * damage is small.  This keeps 厄莉丝-like cross-system healing/support legal while preventing a
 * magic breaker such as 帆稀（夏日） from slipping into a physical core.
 */
private fun LabyrinthRoleProfile.isUniversalDamageSystemRole(): Boolean {
    val role = roleClass.orEmpty()
    val physicalUplift = physicalTeamUplift ?: 0.0
    val magicUplift = magicTeamUplift ?: 0.0
    val crossSystemOffense = minOf(physicalUplift, magicUplift) >= 0.08 ||
        support.universalOffense.orZero() >= 35.0
    val universalSurvival = support.universalSurvival.orZero() >= 35.0
    val healer = role == "治疗者" &&
        max(functions.healing.orZero(), functions.regeneration.orZero()) >= 25.0
    val vanguard = role == "掩护者" &&
        (functions.reliableVanguard.orZero() >= 45.0 || universalSurvival)
    val universalBuffer = role == "增益者" && crossSystemOffense
    return healer || vanguard || universalBuffer
}

private fun strictMemberDamageSystem(members: List<LabyrinthRoleProfile>): LabyrinthTeamDamageType {
    val typed = members
        .filterNot(LabyrinthRoleProfile::isUniversalDamageSystemRole)
        .mapNotNull(LabyrinthRoleProfile::damageType)
        .filter { it in setOf(LabyrinthRoleDamageType.PHYSICAL, LabyrinthRoleDamageType.MAGIC) }

    if (typed.isEmpty()) return LabyrinthTeamDamageType.NONE
    val hasPhysical = LabyrinthRoleDamageType.PHYSICAL in typed
    val hasMagic = LabyrinthRoleDamageType.MAGIC in typed
    return when {
        hasPhysical && hasMagic -> LabyrinthTeamDamageType.MIXED
        hasPhysical -> LabyrinthTeamDamageType.PHYSICAL
        hasMagic -> LabyrinthTeamDamageType.MAGIC
        else -> LabyrinthTeamDamageType.NONE
    }
}

@Serializable
enum class LabyrinthRoleDamageType {
    PHYSICAL,
    MAGIC,
    MIXED,
    NONE,
}

enum class LabyrinthTeamDamageType {
    PHYSICAL,
    MAGIC,
    MIXED,
    NONE,
}

enum class LabyrinthEnemyDamageType {
    PHYSICAL,
    MAGIC,
    MIXED,
    UNKNOWN,
}

@Serializable
enum class LabyrinthSkillEffectType {
    DAMAGE,
    ATTACK_BUFF,
    CRITICAL_BUFF,
    DEFENSE_DOWN,
    DAMAGE_TAKEN_UP,
    ACTION_SPEED_BUFF,
    TP_RECOVERY,
    HEAL,
    REGENERATION,
    SHIELD,
    DEFENSE_BUFF,
    ATTACK_DOWN,
    CONTROL,
    DOT,
    TAUNT,
    PUSH,
    PULL,
    UNKNOWN,
}

@Serializable
enum class LabyrinthSkillEffectSystem {
    PHYSICAL,
    MAGIC,
    UNIVERSAL,
    NONE,
}

@Serializable
enum class LabyrinthSkillTargetScope {
    ALLIES,
    SELF,
    AROUND_SELF,
}

@Serializable
enum class LabyrinthSkillTargetCondition {
    NONE,
    PHYSICAL,
    MAGIC,
    ATTRIBUTE,
    POSITION,
    ROLE_CLASS,
    HIGHEST_PHYSICAL_ATTACK,
    HIGHEST_MAGIC_ATTACK,
}

@Serializable
data class LabyrinthSkillTarget(
    val scope: LabyrinthSkillTargetScope,
    val condition: LabyrinthSkillTargetCondition = LabyrinthSkillTargetCondition.NONE,
    val attribute: LabyrinthCharacterAttribute? = null,
    val position: String? = null,
    val roleClass: String? = null,
) {
    init {
        require((condition == LabyrinthSkillTargetCondition.ATTRIBUTE) == (attribute != null))
        require((condition == LabyrinthSkillTargetCondition.POSITION) == !position.isNullOrBlank())
        require((condition == LabyrinthSkillTargetCondition.ROLE_CLASS) == !roleClass.isNullOrBlank())
    }
}

/** Parsed database fact. Unknown action types retain their raw value for manual review. */
@Serializable
data class LabyrinthSkillEffectFact(
    val skillId: String,
    val effect: LabyrinthSkillEffectType,
    val system: LabyrinthSkillEffectSystem,
    val valueFormula: String,
    val durationFormula: String? = null,
    val target: LabyrinthSkillTarget,
    val rawActionType: String? = null,
) {
    init {
        require(skillId.isNotBlank())
        require(valueFormula.isNotBlank())
        if (effect == LabyrinthSkillEffectType.UNKNOWN) require(!rawActionType.isNullOrBlank())
    }
}

/** Multi-dimensional support after the configured skill targets and coverage have been applied. */
@Serializable
data class LabyrinthRoleSupport(
    val physicalOffense: Double? = null,
    val magicOffense: Double? = null,
    val universalOffense: Double? = null,
    val universalSurvival: Double? = null,
    val control: Double? = null,
) {
    init {
        validateOptionalPercent("physicalOffense", physicalOffense)
        validateOptionalPercent("magicOffense", magicOffense)
        validateOptionalPercent("universalOffense", universalOffense)
        validateOptionalPercent("universalSurvival", universalSurvival)
        validateOptionalPercent("control", control)
    }
}

/**
 * Combat functions stay independent of job/class. In particular, healing and tanking are
 * capabilities rather than aliases of the role-draw pool.
 */
@Serializable
data class LabyrinthRoleFunctions(
    val reliableVanguard: Double? = null,
    val selfSustain: Double? = null,
    val healing: Double? = null,
    val regeneration: Double? = null,
    val physicalAttackReduction: Double? = null,
    val magicAttackReduction: Double? = null,
    val physicalDefenseDown: Double? = null,
    val magicDefenseDown: Double? = null,
    val aoeDamage: Double? = null,
    val singleTargetDamage: Double? = null,
    val twoTargetDamage: Double? = null,
    val threeTargetDamage: Double? = null,
    /** Geometry-derived coverage, independent from three-target damage throughput. */
    val wideAoeCoverage: Double? = null,
    /** Damage centered on / explicitly targeting the farthest enemy or enemy back line. */
    val backlineAoeCoverage: Double? = null,
    val bossMechanismValue: Double? = null,
    val pureTank: Double? = null,
    val pureHealer: Double? = null,
    /** Explicit encounter facts. Null means unknown; never interpret null as absence. */
    val dot: Double? = null,
    val control: Double? = null,
    val shield: Double? = null,
    val taunt: Double? = null,
    val burstDamage: Double? = null,
    /** Enemy-side position manipulation parsed from MOVE actions; excludes self movement. */
    val enemyPush: Double? = null,
    val enemyPull: Double? = null,
) {
    init {
        listOf(
            reliableVanguard,
            selfSustain,
            healing,
            regeneration,
            physicalAttackReduction,
            magicAttackReduction,
            physicalDefenseDown,
            magicDefenseDown,
            aoeDamage,
            singleTargetDamage,
            twoTargetDamage,
            threeTargetDamage,
            wideAoeCoverage,
            backlineAoeCoverage,
            bossMechanismValue,
            pureTank,
            pureHealer,
            dot,
            control,
            shield,
            taunt,
            burstDamage,
            enemyPush,
            enemyPull,
        ).forEach { validateOptionalPercent("role function", it) }
    }
}

@Serializable
data class LabyrinthRoleDataConfidence(
    val attribute: String? = null,
    val roleClass: String? = null,
    val damageType: String? = null,
    val position: String? = null,
    val support: String? = null,
)

@Serializable
data class LabyrinthVanguardProfile(
    val physicalDurability: Double? = null,
    val magicDurability: Double? = null,
    val panelHp: Int? = null,
    val panelPhysicalDefense: Int? = null,
    val panelMagicDefense: Int? = null,
    val panelDodge: Int? = null,
    val panelLifeSteal: Int? = null,
    val panelHpRecoveryRate: Int? = null,
    val untargetable: Double? = null,
    val invulnerability: Double? = null,
    val physicalEvasion: Double? = null,
    val magicEvasion: Double? = null,
    val revive: Double? = null,
    val requiresAllyInFront: Boolean = false,
) {
    init {
        listOf(
            physicalDurability, magicDurability, untargetable, invulnerability,
            physicalEvasion, magicEvasion, revive,
        ).forEach { validateOptionalPercent("vanguard profile", it) }
        listOf(
            panelHp, panelPhysicalDefense, panelMagicDefense, panelDodge,
            panelLifeSteal, panelHpRecoveryRate,
        ).forEach { require(it == null || it >= 0) }
    }
}

/** Lower position values mean the character stands closer to position one. */
@Serializable
data class LabyrinthRoleProfile(
    val characterId: String,
    val displayName: String,
    val roleClass: String? = null,
    val attribute: LabyrinthCharacterAttribute? = null,
    val damageType: LabyrinthRoleDamageType? = null,
    val position: Int? = null,
    /** CN player score on the shared 0..100 scale. Null means genuinely unrated. */
    val userScore: Double? = null,
    val userRatingCount: Int = 0,
    /** Schema-v1 compatibility only. New generated data writes [userScore]. */
    val baseQuality: Double? = null,
    /** Legacy/unmodeled single-scenario output fallback. */
    val physicalDamagePotential: Double? = null,
    val magicDamagePotential: Double? = null,
    val physicalDamage1Target: Double? = null,
    val physicalDamage2Target: Double? = null,
    val physicalDamage3Target: Double? = null,
    val magicDamage1Target: Double? = null,
    val magicDamage2Target: Double? = null,
    val magicDamage3Target: Double? = null,
    /** Signed modeled team uplift. Null means the legacy display-score fallback must be used. */
    val physicalTeamUplift: Double? = null,
    val magicTeamUplift: Double? = null,
    val modelStatus: String = "unavailable",
    /** Physical panel durability and non-stat frontline mechanics kept separate from job class. */
    val vanguardProfile: LabyrinthVanguardProfile? = null,
    val dataConfidence: LabyrinthRoleDataConfidence = LabyrinthRoleDataConfidence(),
    /** Database fact layer; manual quality and coverage scores remain in the fields above/below. */
    val skillFacts: List<LabyrinthSkillEffectFact> = emptyList(),
    val support: LabyrinthRoleSupport = LabyrinthRoleSupport(),
    val functions: LabyrinthRoleFunctions = LabyrinthRoleFunctions(),
    /** Local preference overlay; never changes the source rating or its contributor count. */
    val personalScore: Double? = null,
) {
    init {
        require(characterId.isNotBlank())
        require(displayName.isNotBlank())
        require(roleClass == null || roleClass.isNotBlank())
        require(attribute != LabyrinthCharacterAttribute.UNKNOWN)
        require(position == null || position > 0)
        require(userRatingCount >= 0)
        validateOptionalPercent("userScore", userScore)
        validateOptionalPercent("personalScore", personalScore)
        validateOptionalPercent("baseQuality", baseQuality)
        listOf(
            physicalDamagePotential,
            magicDamagePotential,
            physicalDamage1Target,
            physicalDamage2Target,
            physicalDamage3Target,
            magicDamage1Target,
            magicDamage2Target,
            magicDamage3Target,
        ).forEach { validateOptionalPercent("damage potential", it) }
        require(physicalTeamUplift == null || physicalTeamUplift.isFinite())
        require(magicTeamUplift == null || magicTeamUplift.isFinite())
        // Nominal attack type is a classification fact, not a promise that every modeled
        // skill uses only that chain. A small number of real units have cross-system damage.
    }

    val effectiveUserScore: Double?
        get() = personalScore ?: userScore ?: baseQuality

    val isStrictDecisionReady: Boolean
        get() = roleClass != null && attribute != null && damageType != null && position != null &&
            dataConfidence.support != "missing" &&
            (
                modelStatus.startsWith("database-v") ||
                    (physicalDamagePotential != null && magicDamagePotential != null)
                )
}

data class LabyrinthRoleDecisionContext(
    val defenseMarkStacks: Int,
    val enemyDamageType: LabyrinthEnemyDamageType = LabyrinthEnemyDamageType.UNKNOWN,
    /** Values greater than three share the 3+ target model. */
    val targetCount: Int = 1,
    /** Set only by an explicitly requested post-failure fallback search. */
    val survivalRecovery: Boolean = false,
    /** Boss planning: require a safe vanguard, then jointly optimize tank survivability + team-system synergy. */
    val optimizeBossVanguardSynergy: Boolean = false,
    /** Battle preference: search tries a cohesive physical/magic team before mixed fallback. */
    val preferSingleDamageSystem: Boolean = false,
    /** Encounter guide rules; EX comes from OCR, Boss may come from the persisted route quest id. */
    val encounterStrategy: LabyrinthExEncounterStrategy? = null,
    /** Official “有效效果” intersection with the currently owned roster. Empty means not scanned/none. */
    val effectiveCharacterIds: Set<String> = emptySet(),
    /**
     * Boss fallback after the configured single-team retries were exhausted. The strongest single
     * team has already wiped, so every additional team is a net gain: accept frontliners down to
     * [LabyrinthTeamScoringConfig.relaxedMinimumReliableVanguard] instead of the strict gate.
     * Scoring still prefers a genuinely reliable front; only the hard eligibility gate relaxes.
     */
    val relaxedVanguardGate: Boolean = false,
) {
    init {
        require(defenseMarkStacks >= 0)
        require(targetCount >= 1)
    }
}

/** Every numeric rule used by the scorer is centralized here. */
@Serializable
data class LabyrinthTeamScoringConfig(
    /** Must be supplied from the current game configuration; no 2/3/4-person values are guessed. */
    val attributeDamageBonus: Map<Int, Double>,
    val maximumActiveAttributeBonuses: Int = 2,
    val defenseBaselineStacks: Int = 4,
    val teamTypeThreshold: Double = 0.70,
    val minimumReliableVanguard: Double = 55.0,
    /** Hard vanguard gate used only when [LabyrinthRoleDecisionContext.relaxedVanguardGate] is set. */
    val relaxedMinimumReliableVanguard: Double = DEFAULT_RELAXED_MINIMUM_BATTLE_VANGUARD,
    val minimumEffectiveSustain: Double = 35.0,
    val minimumAoe: Double = 35.0,
    val minimumSingleTarget: Double = 35.0,
    val minimumDefenseDown: Double = 25.0,
    /** Used only for roles without database combat-model uplift. */
    val supportMultiplierAt100: Double = 0.22,
    val legacyRoleUpliftCap: Double = 0.50,
    /** Retained for schema-v1 compatibility; modeled and V2 legacy roles do not multiply by it. */
    val defenseDownMultiplierAt100: Double = 0.28,
    val playerWeight: Double = 0.70,
    val systemDamageWeight: Double = 0.50,
    val systemSurvivalWeight: Double = 0.23,
    val systemFunctionWeight: Double = 0.13,
    val systemFormationWeight: Double = 0.04,
    val systemCohesionWeight: Double = 0.10,
    /** Once a Boss vanguard clears the safety gate, only this fraction of extra tankiness is retained in scoring. */
    val bossExcessVanguardSurvivalRetention: Double = 0.30,
    val damageSoftCeiling: Double = 1.50,
    val modeledRolePositiveUpliftCap: Double = 0.80,
    val modeledRoleNegativeUpliftFloor: Double = -0.95,
    val combinedTeamUpliftCap: Double = 2.00,
    val combinedTeamUpliftFloor: Double = -0.95,
    val lowestDefenseNoAnchorMultiplier: Double = 0.58,
    val lowestDefenseOneAnchorMultiplier: Double = 0.88,
    val lowDefenseNoAnchorMultiplier: Double = 0.76,
    val lowDefenseOneAnchorMultiplier: Double = 0.95,
    val lowestDefenseGatePenalty: Double = 18.0,
    val lowDefenseGatePenalty: Double = 8.0,
    val riskGatePenaltyWeight: Double = 0.35,
    val formationMissingFunctionPenalty: Double = 8.0,
    val formationSurvivalGatePenaltyMultiplier: Double = 2.0,
    /** Schema-v1 weights retained for decoding/audit; V2 computes the normalized system score below. */
    val qualityWeight: Double = 0.20,
    val damageWeight: Double = 0.52,
    val survivalWeightBeforeFourDefense: Double = 0.20,
    val survivalWeightAfterFourDefense: Double = 0.08,
    val functionWeight: Double = 0.16,
    val completionWeight: Double = 0.08,
    val missingCriticalFunctionPenalty: Double = 7.0,
    val postFourDefensePureRolePenalty: Double = 6.0,
    val pureRoleThreshold: Double = 60.0,
    val bossSpecialistThreshold: Double = 50.0,
    val exhaustiveRosterLimit: Int = 24,
    val teamSearchBeamWidth: Int = 120,
    /** Soft redundancy cost, never a class-based exclusion or a mandatory tank/healer slot. */
    val duplicatePureRolePenalty: Double = 8.0,
    /** Extra soft cost for a genuinely mixed physical/magic EX/Boss team when enabled. */
    val battleMixedDamagePenalty: Double = 15.0,
    /**
     * Boss "single damage system" search is stricter than the generic team-type label.
     * 0.85 still allows a low-damage cross-system tank/universal support, but rejects a real
     * opposite-system damage core such as a 73/27 split.
     */
    val bossCohesiveDamageRatioThreshold: Double = 0.85,
    /** Per-role system-side bonus when the game itself marks the role as Effective Effect. */
    val encounterEffectiveCharacterBonus: Double = 8.0,
    /** Soft bonus for satisfying a preferred guide capability. */
    val encounterPreferredCapabilityBonus: Double = 3.0,
    val compositeOffenseThreshold: Double = 60.0,
    val lowestDefenseDuplicateMultiplier: Double = 0.50,
    val lowDefenseDuplicateMultiplier: Double = 0.75,
    val survivalRecoveryDuplicateMultiplier: Double = 0.50,
) {
    init {
        require(attributeDamageBonus.keys.containsAll((1..5).toList())) {
            "attributeDamageBonus must configure all team counts 1..5"
        }
        attributeDamageBonus.forEach { (count, bonus) ->
            require(count in 1..5)
            require(bonus >= 0.0)
        }
        require(attributeDamageBonus.getValue(1) == 0.0)
        require(maximumActiveAttributeBonuses in 1..2)
        require(defenseBaselineStacks > 0)
        require(teamTypeThreshold in 0.5..1.0)
        listOf(
            minimumReliableVanguard,
            relaxedMinimumReliableVanguard,
            minimumEffectiveSustain,
            minimumAoe,
            minimumSingleTarget,
            minimumDefenseDown,
            pureRoleThreshold,
            bossSpecialistThreshold,
        ).forEach { validatePercent("configured threshold", it) }
        require(supportMultiplierAt100 >= 0.0)
        require(legacyRoleUpliftCap >= 0.0)
        require(defenseDownMultiplierAt100 >= 0.0)
        require(playerWeight in 0.0..1.0)
        require(
            listOf(
                systemDamageWeight,
                systemSurvivalWeight,
                systemFunctionWeight,
                systemFormationWeight,
                systemCohesionWeight,
            ).all { it >= 0.0 },
        )
        require(
            kotlin.math.abs(
                systemDamageWeight + systemSurvivalWeight + systemFunctionWeight +
                    systemFormationWeight + systemCohesionWeight - 1.0,
            ) < 0.000001,
        )
        require(bossExcessVanguardSurvivalRetention in 0.0..1.0)
        require(damageSoftCeiling > 0.0)
        require(modeledRolePositiveUpliftCap >= 0.0)
        require(modeledRoleNegativeUpliftFloor in -1.0..0.0)
        require(combinedTeamUpliftCap >= 0.0)
        require(combinedTeamUpliftFloor in -1.0..0.0)
        listOf(
            lowestDefenseNoAnchorMultiplier,
            lowestDefenseOneAnchorMultiplier,
            lowDefenseNoAnchorMultiplier,
            lowDefenseOneAnchorMultiplier,
        ).forEach { require(it in 0.0..1.0) }
        require(lowestDefenseGatePenalty >= 0.0)
        require(lowDefenseGatePenalty >= 0.0)
        require(riskGatePenaltyWeight >= 0.0)
        require(formationMissingFunctionPenalty >= 0.0)
        require(formationSurvivalGatePenaltyMultiplier >= 0.0)
        require(battleMixedDamagePenalty >= 0.0)
        require(encounterEffectiveCharacterBonus >= 0.0)
        require(encounterPreferredCapabilityBonus >= 0.0)
        require(qualityWeight >= 0.0)
        require(damageWeight >= 0.0)
        require(survivalWeightBeforeFourDefense >= 0.0)
        require(survivalWeightAfterFourDefense >= 0.0)
        require(functionWeight >= 0.0)
        require(completionWeight >= 0.0)
        require(missingCriticalFunctionPenalty >= 0.0)
        require(postFourDefensePureRolePenalty >= 0.0)
        require(exhaustiveRosterLimit >= 5)
        require(teamSearchBeamWidth > 0)
        require(duplicatePureRolePenalty.isFinite() && duplicatePureRolePenalty >= 0.0)
        require(bossCohesiveDamageRatioThreshold in 0.5..1.0)
        validatePercent("compositeOffenseThreshold", compositeOffenseThreshold)
        listOf(lowestDefenseDuplicateMultiplier, lowDefenseDuplicateMultiplier,
            survivalRecoveryDuplicateMultiplier).forEach { require(it in 0.0..1.0) }
    }

    /** The hard first-position gate for this context: strict by default, relaxed for Boss fallback. */
    fun vanguardGate(context: LabyrinthRoleDecisionContext): Double =
        if (context.relaxedVanguardGate) relaxedMinimumReliableVanguard else minimumReliableVanguard
}

data class LabyrinthTeamEvaluation(
    val members: List<LabyrinthRoleProfile>,
    val score: Double,
    val systemScore: Double,
    val playerScore: Double?,
    val playerCoverage: Double,
    val requestedPlayerWeight: Double,
    val effectivePlayerWeight: Double,
    val damageType: LabyrinthTeamDamageType,
    val physicalRatio: Double,
    val physicalDamage: Double,
    val magicDamage: Double,
    val physicalTeamUplift: Double,
    val magicTeamUplift: Double,
    val targetCount: Int,
    val attributeCounts: Map<LabyrinthCharacterAttribute, Int>,
    val activeAttributeBonuses: Map<LabyrinthCharacterAttribute, Double>,
    val vanguardCharacterId: String,
    val frontlineScore: Double,
    val sustainScore: Double,
    val hasReliableFrontline: Boolean,
    val hasEffectiveSustain: Boolean,
    val survivalMultiplier: Double,
    val survivalGatePenalty: Double,
    val survivalAnchorCharacterId: String?,
    val missingCriticalFunctions: List<String>,
    val systemComponents: Map<String, Double>,
    val components: Map<String, Double>,
    val reasons: List<String>,
    val pureTankCount: Int = 0,
    val pureHealerCount: Int = 0,
    val duplicateRolePenalty: Double = 0.0,
)

class LabyrinthTeamScorer(
    val config: LabyrinthTeamScoringConfig,
) {
    fun evaluate(
        members: List<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
    ): LabyrinthTeamEvaluation {
        // Argument validation stays on the public entry: the search hot path evaluates tens of
        // thousands of teams whose pool was already de-duplicated at rankedFormationsInternal.
        require(members.isNotEmpty() && members.size <= TEAM_SIZE)
        require(members.distinctBy(LabyrinthRoleProfile::characterId).size == members.size)
        return evaluateInternal(members, context, explain = true)
    }

    internal fun evaluateForSearch(
        members: List<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
    ): LabyrinthTeamEvaluation = evaluateInternal(members, context, explain = false)

    private fun evaluateInternal(
        members: List<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
        explain: Boolean,
    ): LabyrinthTeamEvaluation {
        val targetCount = context.targetCount.coerceAtMost(3)
        // One pass over the members into ordinal-indexed arrays. This is the same computation as
        // the former mapNotNull/groupingBy/eachCount + Triple + stable sortedByDescending + take
        // chain, minus its intermediate collections and boxing: the search hot path evaluates
        // tens of thousands of teams per recommendation, and those temporaries were most of its
        // garbage. Attribute order is first-appearance order, exactly as groupingBy produced it,
        // and the per-attribute damage sums add members in member order, so the doubles are
        // bit-identical.
        val attributeCount = IntArray(ATTRIBUTE_SLOT_COUNT)
        val attributeGroupDamage = DoubleArray(ATTRIBUTE_SLOT_COUNT)
        val attributeFirstSeen = IntArray(members.size)
        var attributeSeenCount = 0
        for (member in members) {
            val slot = (member.attribute ?: continue).ordinal
            if (attributeCount[slot] == 0) attributeFirstSeen[attributeSeenCount++] = slot
            attributeCount[slot]++
            attributeGroupDamage[slot] += scenarioDamage(member, LabyrinthRoleDamageType.PHYSICAL, targetCount) +
                scenarioDamage(member, LabyrinthRoleDamageType.MAGIC, targetCount)
        }
        val attributeCounts: Map<LabyrinthCharacterAttribute, Int> = if (attributeSeenCount == 0) {
            emptyMap()
        } else {
            LinkedHashMap<LabyrinthCharacterAttribute, Int>(attributeSeenCount * 2).also { counts ->
                for (index in 0 until attributeSeenCount) {
                    val slot = attributeFirstSeen[index]
                    counts[ATTRIBUTE_SLOTS[slot]] = attributeCount[slot]
                }
            }
        }
        // Top-N by incremental damage with a bounded insertion sort. Ties keep first-appearance
        // order, which is what the stable sortedByDescending did.
        val maximumBonuses = config.maximumActiveAttributeBonuses
        val chosenSlots = IntArray(maximumBonuses)
        val chosenBonuses = DoubleArray(maximumBonuses)
        val chosenIncremental = DoubleArray(maximumBonuses)
        var chosenCount = 0
        for (index in 0 until attributeSeenCount) {
            val slot = attributeFirstSeen[index]
            val bonus = config.attributeDamageBonus.getValue(attributeCount[slot])
            if (bonus <= 0.0) continue
            val incremental = attributeGroupDamage[slot] * bonus
            if (chosenCount == maximumBonuses && chosenIncremental[chosenCount - 1] >= incremental) continue
            var position = if (chosenCount < maximumBonuses) chosenCount else chosenCount - 1
            while (position > 0 && chosenIncremental[position - 1] < incremental) {
                chosenSlots[position] = chosenSlots[position - 1]
                chosenBonuses[position] = chosenBonuses[position - 1]
                chosenIncremental[position] = chosenIncremental[position - 1]
                position--
            }
            chosenSlots[position] = slot
            chosenBonuses[position] = bonus
            chosenIncremental[position] = incremental
            if (chosenCount < maximumBonuses) chosenCount++
        }
        val activeBonuses: Map<LabyrinthCharacterAttribute, Double> = if (chosenCount == 0) {
            emptyMap()
        } else {
            LinkedHashMap<LabyrinthCharacterAttribute, Double>(chosenCount * 2).also { bonuses ->
                for (index in 0 until chosenCount) bonuses[ATTRIBUTE_SLOTS[chosenSlots[index]]] = chosenBonuses[index]
            }
        }

        val physicalBase = members.sumOf { role ->
            scenarioDamage(role, LabyrinthRoleDamageType.PHYSICAL, targetCount) *
                (1.0 + (role.attribute?.let(activeBonuses::get) ?: 0.0))
        }
        val magicBase = members.sumOf { role ->
            scenarioDamage(role, LabyrinthRoleDamageType.MAGIC, targetCount) *
                (1.0 + (role.attribute?.let(activeBonuses::get) ?: 0.0))
        }
        val physicalUplift = combinedTeamUplift(members, LabyrinthRoleDamageType.PHYSICAL)
        val magicUplift = combinedTeamUplift(members, LabyrinthRoleDamageType.MAGIC)
        // V2 raw uplift already includes modeled buff/debuff effects. Percentile-normalized
        // defense-down remains a function signal until real enemy defense is supplied.
        val physicalDamage = physicalBase * (1.0 + physicalUplift)
        val magicDamage = magicBase * (1.0 + magicUplift)
        val totalDamage = physicalDamage + magicDamage
        val physicalRatio = if (totalDamage <= 0.0) 0.0 else physicalDamage / totalDamage
        // Team system is a member-composition fact, not a damage-share estimate.  A 3P+2M team
        // must remain MIXED even when the physical members happen to contribute >70% of modeled
        // damage.  Genuine universal tank/healer/support roles are ignored for this classification.
        val damageType = strictMemberDamageSystem(members)

        val vanguard = members.minBy { it.position ?: Int.MAX_VALUE }
        val effectiveCharacterCount = members.count { role ->
            canonicalLabyrinthRoleId(role.characterId) in context.effectiveCharacterIds
        }
        // Effective Effect is a per-role system hint, not a team-total reward. Average the
        // individual system points across the current team, then let the configured player/system
        // blend decide how much of that hint reaches the final score. This prevents Effective
        // Effect from bypassing or diluting a deliberately low player score.
        val effectiveCharacterSystemBonus = if (
            effectiveCharacterCount > 0 &&
            (context.encounterStrategy?.preferEffectiveCharacters ?: true)
        ) {
            effectiveCharacterCount * config.encounterEffectiveCharacterBonus / members.size
        } else {
            0.0
        }
        // Preferred guide capabilities are graded on their actual 0..100 strength.  A true
        // 1300-range AOE (100) must be worth more than a marginal 300-range AOE (55); treating both
        // as a binary "hit" was exactly why Rino could lose her intended NY-Karyl niche.
        val preferredCapabilityContribution = context.encounterStrategy?.let { strategy ->
            strategy.preferredCapabilities.sumOf { capability ->
                val target = strategy.preferredCapabilityMemberTargets[capability] ?: 1
                members.mapNotNull { role -> role.encounterCapabilityScore(capability) }
                    .filter { it >= 50.0 }
                    .sortedDescending()
                    .take(target)
                    .sumOf { it / 100.0 }
            }
        } ?: 0.0
        val fallbackCapabilityContribution = context.encounterStrategy?.fallbackPreferences.orEmpty().sumOf { fallback ->
            val primaryCount = members.count { role ->
                (role.encounterCapabilityScore(fallback.primaryCapability) ?: -1.0) >= 50.0
            }
            if (primaryCount >= fallback.primaryTargetCount) {
                0.0
            } else {
                members.map { role ->
                    fallback.anyOf.maxOfOrNull { capability ->
                        role.encounterCapabilityScore(capability) ?: 0.0
                    } ?: 0.0
                }
                    .filter { it >= 50.0 }
                    .sortedDescending()
                    .take(fallback.maximumCount)
                    .sumOf { it / 100.0 }
            }
        }
        val preferredCapabilityBonus =
            (preferredCapabilityContribution + fallbackCapabilityContribution +
                encounterRequirementContribution(members, context.encounterStrategy)) *
                config.encounterPreferredCapabilityBonus
        val encounterBonus = preferredCapabilityBonus
        val frontlineScore = vanguard.labyrinthVanguardStrength(context)
        val frontlineScoreForTeam = if (
            context.optimizeBossVanguardSynergy &&
            frontlineScore > config.minimumReliableVanguard
        ) {
            config.minimumReliableVanguard +
                (frontlineScore - config.minimumReliableVanguard) * config.bossExcessVanguardSurvivalRetention
        } else {
            frontlineScore
        }
        val sustainAnchor = members.maxBy { effectiveSustainScore(it) }
        val sustainScore = effectiveSustainScore(sustainAnchor)
        val hasReliableFrontline = frontlineScore >= config.minimumReliableVanguard
        val hasEffectiveSustain = sustainScore >= config.minimumEffectiveSustain
        val relevantReduction = when (context.effectiveEnemyDamageType) {
            LabyrinthEnemyDamageType.PHYSICAL -> members.maxOf { it.functions.physicalAttackReduction.orZero() }
            LabyrinthEnemyDamageType.MAGIC -> members.maxOf { it.functions.magicAttackReduction.orZero() }
            LabyrinthEnemyDamageType.MIXED -> (
                members.maxOf { it.functions.physicalAttackReduction.orZero() } +
                    members.maxOf { it.functions.magicAttackReduction.orZero() }
                ) / 2.0
            LabyrinthEnemyDamageType.UNKNOWN -> max(
                members.maxOf { it.functions.physicalAttackReduction.orZero() },
                members.maxOf { it.functions.magicAttackReduction.orZero() },
            )
        }
        val survivalSupport = (
            members.sumOf { it.support.universalSurvival.orZero() } / members.size +
                members.maxOf { max(it.functions.healing.orZero(), it.functions.regeneration.orZero()) } +
                relevantReduction
            ) / 3.0
        val survivalScore = frontlineScoreForTeam * 0.70 + survivalSupport * 0.30

        val aoe = members.maxOf { it.functions.aoeDamage.orZero() }
        val singleTarget = members.maxOf { it.functions.singleTargetDamage.orZero() }
        val multiTarget = if (targetCount >= 3) {
            members.maxOf {
                it.functions.threeTargetDamage ?: (
                    scenarioDamage(it, LabyrinthRoleDamageType.PHYSICAL, 3) +
                        scenarioDamage(it, LabyrinthRoleDamageType.MAGIC, 3)
                    )
            }
        } else {
            members.maxOf {
                it.functions.twoTargetDamage ?: (
                    scenarioDamage(it, LabyrinthRoleDamageType.PHYSICAL, 2) +
                        scenarioDamage(it, LabyrinthRoleDamageType.MAGIC, 2)
                    )
            }
        }
        val bossValue = members.maxOf { it.functions.bossMechanismValue.orZero() }
        val matchingDefenseDown = when (damageType) {
            LabyrinthTeamDamageType.PHYSICAL -> members.maxOf { it.functions.physicalDefenseDown.orZero() }
            LabyrinthTeamDamageType.MAGIC -> members.maxOf { it.functions.magicDefenseDown.orZero() }
            LabyrinthTeamDamageType.MIXED -> (
                members.maxOf { it.functions.physicalDefenseDown.orZero() } +
                    members.maxOf { it.functions.magicDefenseDown.orZero() }
                ) / 2.0
            LabyrinthTeamDamageType.NONE -> 0.0
        }
        val scenarioFunction = if (targetCount == 1) singleTarget else max(aoe, multiTarget)
        val functionScore = (scenarioFunction + matchingDefenseDown + bossValue) / 3.0
        val missing = buildList {
            if (!hasReliableFrontline) add("可靠一号位/掩护者")
            if (context.defenseMarkStacks < config.defenseBaselineStacks && !hasEffectiveSustain) {
                add("有效治疗/续航")
            }
            if (targetCount >= 2 && multiTarget < config.minimumAoe) add("${targetCount}目标输出")
            if (targetCount == 1 && singleTarget < config.minimumSingleTarget) add("单体输出")
            if (matchingDefenseDown < config.minimumDefenseDown) {
                add(
                    when (damageType) {
                        LabyrinthTeamDamageType.PHYSICAL -> "物防降低"
                        LabyrinthTeamDamageType.MAGIC -> "魔防降低"
                        LabyrinthTeamDamageType.MIXED -> "双体系防御降低"
                        LabyrinthTeamDamageType.NONE -> "有效输出体系"
                    },
                )
            }
        }

        val ratedRoles = members.mapNotNull(LabyrinthRoleProfile::effectiveUserScore)
        val playerScore = ratedRoles.takeIf { it.isNotEmpty() }?.average()
        val playerCoverage = ratedRoles.size.toDouble() / members.size
        val damageScore = totalDamage / members.size
        val pureRolePenalty = if (context.defenseMarkStacks >= config.defenseBaselineStacks) {
            members.sumOf { role ->
                val bossSpecialist = role.functions.bossMechanismValue.orZero() >= config.bossSpecialistThreshold ||
                    role.functions.selfSustain.orZero() >= config.bossSpecialistThreshold
                val pure = role.functions.pureTank.orZero() >= config.pureRoleThreshold ||
                    role.functions.pureHealer.orZero() >= config.pureRoleThreshold
                if (pure && !bossSpecialist) config.postFourDefensePureRolePenalty else 0.0
            }
        } else {
            0.0
        }
        var pureTankCount = 0
        var pureHealerCount = 0
        for (role in members) {
            if (hasCompositeOffense(role, targetCount, damageType)) continue
            val tank = role.functions.pureTank.orZero()
            val healer = role.functions.pureHealer.orZero()
            // A dual-tagged defensive role occupies one slot, not two redundant slots.
            if (tank >= config.pureRoleThreshold && tank >= healer) pureTankCount++
            else if (healer >= config.pureRoleThreshold) pureHealerCount++
        }
        val duplicates = (pureTankCount - 1).coerceAtLeast(0) + (pureHealerCount - 1).coerceAtLeast(0)
        val survivalNeedsHelp = !hasReliableFrontline ||
            (context.defenseMarkStacks < config.defenseBaselineStacks && !hasEffectiveSustain)
        val duplicateMultiplier = when {
            context.defenseMarkStacks >= config.defenseBaselineStacks -> 1.0
            context.defenseMarkStacks <= 1 -> config.lowestDefenseDuplicateMultiplier
            else -> config.lowDefenseDuplicateMultiplier
        } * if (context.survivalRecovery || survivalNeedsHelp) config.survivalRecoveryDuplicateMultiplier else 1.0
        val duplicatePenalty = duplicates * config.duplicatePureRolePenalty * duplicateMultiplier
        var survivalMultiplier = 1.0
        var survivalGatePenalty = 0.0
        if (context.defenseMarkStacks <= 1) {
            if (!hasReliableFrontline && !hasEffectiveSustain) {
                survivalMultiplier = config.lowestDefenseNoAnchorMultiplier
                survivalGatePenalty = config.lowestDefenseGatePenalty
            } else if (!hasReliableFrontline || !hasEffectiveSustain) {
                survivalMultiplier = config.lowestDefenseOneAnchorMultiplier
            }
        } else if (context.defenseMarkStacks <= 3) {
            if (!hasReliableFrontline && !hasEffectiveSustain) {
                survivalMultiplier = config.lowDefenseNoAnchorMultiplier
                survivalGatePenalty = config.lowDefenseGatePenalty
            } else if (!hasReliableFrontline || !hasEffectiveSustain) {
                survivalMultiplier = config.lowDefenseOneAnchorMultiplier
            }
        }

        val systemDamage = (damageScore / config.damageSoftCeiling).coerceIn(0.0, 100.0)
        val systemSurvival = survivalScore.coerceIn(0.0, 100.0)
        val systemFunction = functionScore.coerceIn(0.0, 100.0)
        val systemCohesion = if (totalDamage > 0.0) {
            (max(physicalRatio, 1.0 - physicalRatio) * 100.0).coerceIn(50.0, 100.0)
        } else {
            100.0
        }
        val systemFormation = (
            100.0 - survivalGatePenalty * config.formationSurvivalGatePenaltyMultiplier -
                missing.size * config.formationMissingFunctionPenalty - pureRolePenalty
            ).coerceIn(0.0, 100.0)
        val mixedDamagePenalty = if (
            context.preferSingleDamageSystem && damageType == LabyrinthTeamDamageType.MIXED
        ) {
            config.battleMixedDamagePenalty
        } else {
            0.0
        }
        val systemComponents = if (!explain) emptyMap() else linkedMapOf(
            "预计输出" to systemDamage,
            "生存" to systemSurvival,
            "功能" to systemFunction,
            "阵型完整性" to systemFormation,
            "体系一致性" to systemCohesion,
            "有效效果角色系统加分" to effectiveCharacterSystemBonus,
            "重复纯职能扣分" to -duplicatePenalty,
            "物法混编偏好" to -mixedDamagePenalty,
            "遭遇攻略偏好" to preferredCapabilityBonus,
        )
        val rawSystemScore = (systemDamage * config.systemDamageWeight +
            systemSurvival * config.systemSurvivalWeight +
            systemFunction * config.systemFunctionWeight +
            systemFormation * config.systemFormationWeight +
            systemCohesion * config.systemCohesionWeight +
            effectiveCharacterSystemBonus).coerceIn(0.0, 100.0)
        val systemScore = max(0.0, rawSystemScore - duplicatePenalty - mixedDamagePenalty + encounterBonus)
        val effectivePlayerWeight = if (playerScore == null) 0.0 else config.playerWeight * playerCoverage
        val effectiveSystemWeight = 1.0 - effectivePlayerWeight
        val preRiskScore = (playerScore ?: 0.0) * effectivePlayerWeight + rawSystemScore * effectiveSystemWeight
        val riskAdjustedScore = max(
            0.0,
            preRiskScore * survivalMultiplier - survivalGatePenalty * config.riskGatePenaltyWeight,
        )
        val score = max(0.0, riskAdjustedScore - duplicatePenalty - mixedDamagePenalty + encounterBonus)
        val components = if (!explain) emptyMap() else linkedMapOf(
            "玩家经验" to (playerScore ?: 0.0) * effectivePlayerWeight,
            "系统适配" to rawSystemScore * effectiveSystemWeight,
            "生存风险" to riskAdjustedScore - preRiskScore,
            "重复纯职能" to -duplicatePenalty,
            "4守备后纯职能扣分" to -pureRolePenalty,
            "物法混编" to -mixedDamagePenalty,
            "遭遇攻略偏好" to preferredCapabilityBonus,
        )
        val reasons = if (!explain) emptyList() else buildList {
            labyrinthEncounterFormationReason(members, context)?.let(::add)
            if (duplicates > 0) {
                add("重复纯职能：纯坦${pureTankCount}名、纯治疗${pureHealerCount}名；扣${duplicatePenalty}分" +
                    if (context.survivalRecovery || survivalNeedsHelp) "（生存不足或失败后兜底，扣分减半）" else "")
            }
            add("预计${damageType.label()}输出，物理占比${(physicalRatio * 100).toInt()}%")
            if (mixedDamagePenalty > 0.0) {
                add(
                    "战斗编组启用单一物/法体系偏好：当前为混合输出，软扣${mixedDamagePenalty.toInt()}分；" +
                        "若混编综合优势足够大仍可入选",
                )
            }
            context.encounterStrategy?.let { strategy ->
                add("遭遇攻略：${strategy.identityName}")
                if (strategy.requirements.isNotEmpty()) {
                    add("攻略偏好（尽力满足）：${strategy.requirements.joinToString("、") { it.label }}")
                }
                encounterRequirementShortfallReason(members, strategy)?.let { add(0, it) }
            }
            if (effectiveCharacterCount > 0) {
                add(
                    "官方有效效果角色：${effectiveCharacterCount}名；每名系统侧+" +
                        "${config.encounterEffectiveCharacterBonus.toInt()}，队伍系统均值+" +
                        "${"%.1f".format(effectiveCharacterSystemBonus)}，再按玩家/系统权重融合",
                )
            }
            if (activeBonuses.isNotEmpty()) {
                add(
                    "属性增伤：" + activeBonuses.entries.joinToString("、") { (attribute, bonus) ->
                        "${attribute.label}${attributeCounts.getValue(attribute)}人+${(bonus * 100).toInt()}%"
                    },
                )
            }
            val vanguardPhysicalUplift = roleTeamUplift(vanguard, LabyrinthRoleDamageType.PHYSICAL)
            val vanguardMagicUplift = roleTeamUplift(vanguard, LabyrinthRoleDamageType.MAGIC)
            add("一号位${vanguard.displayName}，可靠度${frontlineScore.toInt()}")
            if (context.optimizeBossVanguardSynergy) {
                val durability = vanguard.vanguardProfile?.let { profile ->
                    "物耐${profile.physicalDurability.orZero().toInt()}/法耐${profile.magicDurability.orZero().toInt()}；"
                }.orEmpty()
                add(
                    "Boss一号位联合优化：${durability}过生存线后超额肉度按" +
                        "${(config.bossExcessVanguardSurvivalRetention * 100).toInt()}%计入；" +
                        "该T物理队增益${(vanguardPhysicalUplift * 100).toInt()}%、" +
                        "法术队增益${(vanguardMagicUplift * 100).toInt()}%",
                )
            }
            add("玩家评分覆盖${(playerCoverage * 100).toInt()}%，有效权重${(effectivePlayerWeight * 100).toInt()}%")
            if (survivalMultiplier < 1.0) {
                add("低守备生存门槛：存活输出倍率${(survivalMultiplier * 100).toInt()}%")
            }
            if (context.defenseMarkStacks >= config.defenseBaselineStacks) {
                add("已达${config.defenseBaselineStacks}守备，纯坦/纯治疗降权，输出与复合功能升权")
            } else {
                add("未达${config.defenseBaselineStacks}守备，保留一号位与续航权重")
            }
            if (missing.isNotEmpty()) add("仍缺：${missing.joinToString("、")}")
        }
        return LabyrinthTeamEvaluation(
            // Preserve enumeration order during search, including equal-position tie breaks.
            members = if (explain) members.sortedBy { it.position ?: Int.MAX_VALUE } else members.toList(),
            score = score,
            systemScore = systemScore,
            playerScore = playerScore,
            playerCoverage = playerCoverage,
            requestedPlayerWeight = config.playerWeight,
            effectivePlayerWeight = effectivePlayerWeight,
            damageType = damageType,
            physicalRatio = physicalRatio,
            physicalDamage = physicalDamage,
            magicDamage = magicDamage,
            physicalTeamUplift = physicalUplift,
            magicTeamUplift = magicUplift,
            targetCount = targetCount,
            attributeCounts = attributeCounts,
            activeAttributeBonuses = activeBonuses,
            vanguardCharacterId = vanguard.characterId,
            frontlineScore = frontlineScore,
            sustainScore = sustainScore,
            hasReliableFrontline = hasReliableFrontline,
            hasEffectiveSustain = hasEffectiveSustain,
            survivalMultiplier = survivalMultiplier,
            survivalGatePenalty = survivalGatePenalty,
            survivalAnchorCharacterId = when {
                hasReliableFrontline -> vanguard.characterId
                hasEffectiveSustain -> sustainAnchor.characterId
                else -> null
            },
            missingCriticalFunctions = missing,
            systemComponents = systemComponents,
            components = components,
            reasons = reasons,
            pureTankCount = pureTankCount,
            pureHealerCount = pureHealerCount,
            duplicateRolePenalty = duplicatePenalty,
        )
    }

    private fun hasCompositeOffense(
        role: LabyrinthRoleProfile,
        targetCount: Int,
        teamType: LabyrinthTeamDamageType,
    ): Boolean {
        val threshold = config.compositeOffenseThreshold
        val damage = scenarioDamage(role, LabyrinthRoleDamageType.PHYSICAL, targetCount) +
            scenarioDamage(role, LabyrinthRoleDamageType.MAGIC, targetCount)
        if (damage >= threshold || role.support.universalOffense.orZero() >= threshold) return true
        // Off-system support alone does not turn a pure healer/tank into useful offensive support.
        val physical = teamType == LabyrinthTeamDamageType.PHYSICAL || teamType == LabyrinthTeamDamageType.MIXED
        val magic = teamType == LabyrinthTeamDamageType.MAGIC || teamType == LabyrinthTeamDamageType.MIXED
        return (physical && (role.support.physicalOffense.orZero() >= threshold ||
            role.functions.physicalDefenseDown.orZero() >= threshold)) ||
            (magic && (role.support.magicOffense.orZero() >= threshold ||
                role.functions.magicDefenseDown.orZero() >= threshold))
    }

    private fun scenarioDamage(
        role: LabyrinthRoleProfile,
        system: LabyrinthRoleDamageType?,
        targetCount: Int,
    ): Double {
        val modeled = when (system) {
            LabyrinthRoleDamageType.PHYSICAL -> when (targetCount.coerceAtMost(3)) {
                1 -> role.physicalDamage1Target
                2 -> role.physicalDamage2Target
                else -> role.physicalDamage3Target
            }
            LabyrinthRoleDamageType.MAGIC -> when (targetCount.coerceAtMost(3)) {
                1 -> role.magicDamage1Target
                2 -> role.magicDamage2Target
                else -> role.magicDamage3Target
            }
            else -> null
        }
        return modeled ?: when (system) {
            LabyrinthRoleDamageType.PHYSICAL -> role.physicalDamagePotential.orZero()
            LabyrinthRoleDamageType.MAGIC -> role.magicDamagePotential.orZero()
            LabyrinthRoleDamageType.MIXED ->
                role.physicalDamagePotential.orZero() + role.magicDamagePotential.orZero()
            else -> 0.0
        }
    }

    private fun roleTeamUplift(role: LabyrinthRoleProfile, system: LabyrinthRoleDamageType): Double {
        val modeled = when (system) {
            LabyrinthRoleDamageType.PHYSICAL -> role.physicalTeamUplift
            LabyrinthRoleDamageType.MAGIC -> role.magicTeamUplift
            else -> null
        }
        if (role.modelStatus.startsWith("database-v") && modeled != null) {
            return modeled.coerceIn(
                config.modeledRoleNegativeUpliftFloor,
                config.modeledRolePositiveUpliftCap,
            )
        }
        val specific = when (system) {
            LabyrinthRoleDamageType.PHYSICAL -> role.support.physicalOffense.orZero()
            LabyrinthRoleDamageType.MAGIC -> role.support.magicOffense.orZero()
            else -> 0.0
        }
        return ((specific + role.support.universalOffense.orZero()) / 100.0 * config.supportMultiplierAt100)
            .coerceIn(0.0, config.legacyRoleUpliftCap)
    }

    private fun combinedTeamUplift(
        members: List<LabyrinthRoleProfile>,
        system: LabyrinthRoleDamageType,
    ): Double = members.fold(1.0) { factor, role ->
        factor * (1.0 + roleTeamUplift(role, system))
    }.minus(1.0).coerceIn(config.combinedTeamUpliftFloor, config.combinedTeamUpliftCap)

    private fun effectiveSustainScore(role: LabyrinthRoleProfile): Double = maxOf(
        role.functions.healing.orZero(),
        role.functions.regeneration.orZero(),
        role.support.universalSurvival.orZero() * 0.85,
    )

    private companion object {
        const val TEAM_SIZE = 5

        /**
         * Distinct-vanguard first teams tried when the beam holds no disjoint set. Each seed costs
         * one bounded search per follow-up team, so this stays small enough for on-device planning.
         */
        const val MAX_RESIDUAL_FIRST_TEAM_SEEDS = 6
    }
}

/** Identity-only entries carry no invented attributes, positions, ratings or combat facts. */
internal fun Map<String, LabyrinthRoleProfile>.withKnownRoleIdentities(
    ids: Collection<String>,
    names: Map<String, String> = emptyMap(),
): Map<String, LabyrinthRoleProfile> = this + ids.distinct().filterNot { containsKey(it) }.associateWith { id ->
    LabyrinthRoleProfile(characterId = id, displayName = names[id]?.takeIf { it.isNotBlank() } ?: id)
}

internal val LabyrinthRoleProfile.isLabyrinthBattleTank: Boolean
    get() = roleClass == "掩护者"

private const val DEFAULT_MINIMUM_BATTLE_VANGUARD = 55.0
internal const val DEFAULT_RELAXED_MINIMUM_BATTLE_VANGUARD = 40.0

/**
 * Actual first-position survival is a combat job, not a synonym for the “掩护者” class.
 * New production data uses panel durability plus explicit skill mechanisms. Older fixtures/data
 * without [vanguardProfile] retain the legacy protector fallback for schema compatibility.
 */
internal fun LabyrinthRoleProfile.labyrinthVanguardStrength(context: LabyrinthRoleDecisionContext): Double {
    val profile = vanguardProfile
    if (profile == null) {
        val modeled = functions.reliableVanguard.orZero() * 0.75 + functions.selfSustain.orZero() * 0.25
        val fallback = if (isLabyrinthBattleTank) {
            minOf(
                100.0,
                45.0 + functions.selfSustain.orZero() * 0.25 + support.universalSurvival.orZero() * 0.20,
            )
        } else {
            0.0
        }
        return max(modeled, fallback)
    }
    if (profile.requiresAllyInFront) return 0.0

    val panelDurability = when (context.effectiveEnemyDamageType) {
        LabyrinthEnemyDamageType.PHYSICAL -> profile.physicalDurability.orZero()
        LabyrinthEnemyDamageType.MAGIC -> profile.magicDurability.orZero()
        LabyrinthEnemyDamageType.MIXED -> (
            profile.physicalDurability.orZero() + profile.magicDurability.orZero()
            ) / 2.0
        LabyrinthEnemyDamageType.UNKNOWN -> minOf(
            profile.physicalDurability.orZero(),
            profile.magicDurability.orZero(),
        )
    }
    val conventional = (
        panelDurability * 0.68 +
            functions.reliableVanguard.orZero() * 0.17 +
            functions.selfSustain.orZero() * 0.10 +
            support.universalSurvival.orZero() * 0.05
        ).coerceIn(0.0, 100.0)

    val evasion = when (context.effectiveEnemyDamageType) {
        LabyrinthEnemyDamageType.PHYSICAL -> profile.physicalEvasion.orZero()
        LabyrinthEnemyDamageType.MAGIC -> profile.magicEvasion.orZero()
        LabyrinthEnemyDamageType.MIXED -> minOf(
            profile.physicalEvasion.orZero(), profile.magicEvasion.orZero(),
        )
        // Do not assume a physical-only dodge mechanic protects against an unknown EX.
        LabyrinthEnemyDamageType.UNKNOWN -> 0.0
    }
    // Temporary/conditional survival mechanics improve an already durable frontliner, but must not
    // turn a glass cannon into a universal tank by themselves. In particular, untargetable states
    // such as Grace's Spirit are setup-dependent (gained after UB), so they cannot independently
    // grant first-position eligibility before the role survives the opening pressure.
    val mechanismAdjusted = maxOf(
        conventional,
        conventional + profile.untargetable.orZero() * 0.20,
        conventional + profile.invulnerability.orZero() * 0.20,
        conventional + evasion * 0.35,
        conventional + profile.revive.orZero() * 0.12,
    )
    return mechanismAdjusted.coerceIn(0.0, 100.0)
}

/** Null [minimum] resolves the default gate for the context (strict, or relaxed for Boss fallback). */
internal fun LabyrinthRoleProfile.isEligibleBattleVanguard(
    context: LabyrinthRoleDecisionContext,
    minimum: Double? = null,
): Boolean {
    val gate = minimum ?: if (context.relaxedVanguardGate) {
        DEFAULT_RELAXED_MINIMUM_BATTLE_VANGUARD
    } else {
        DEFAULT_MINIMUM_BATTLE_VANGUARD
    }
    return position != null && labyrinthVanguardStrength(context) >= gate
}

internal fun List<LabyrinthRoleProfile>.hasEligibleVanguardAtActualFront(
    context: LabyrinthRoleDecisionContext,
    minimum: Double? = null,
): Boolean {
    if (any { it.position == null }) return false
    val frontPosition = mapNotNull(LabyrinthRoleProfile::position).minOrNull() ?: return false
    val actualFront = filter { it.position == frontPosition }
    // Equal positions stay conservative: every possible frontmost unit must pass the survival gate.
    return actualFront.isNotEmpty() && actualFront.all { it.isEligibleBattleVanguard(context, minimum) }
}

class LabyrinthTeamOptimizer(
    private val scorer: LabyrinthTeamScorer,
    /**
     * Worker threads for the combination walk. Deliberately not tied to the CPU count: the game
     * and this app share one virtual machine, and saturating every core makes the game drop
     * frames, which lengthens the run more than the search it was meant to speed up. Set to 1 to
     * force the serial path.
     */
    private val searchThreads: Int = ROLE_SEARCH_THREADS,
) {
    val scoringConfig: LabyrinthTeamScoringConfig
        get() = scorer.config

    fun bestFormation(
        roster: Collection<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
        requiredCharacterId: String? = null,
    ): LabyrinthTeamEvaluation? = rankedFormationsInternal(
        roster, context, requiredCharacterId, systemOnly = false, limit = 1,
        requireFrontmostTank = false,
    ).firstOrNull()

    /**
     * Battle-only formation search. Reward/event marginal-value calculations deliberately keep
     * using [bestFormation], so this hard tank rule does not rewrite acquisition preferences.
     */
    /**
     * Best battle formation that contains every id in [requiredIds]. The bounded search pool
     * keeps the required roles in, and only combinations holding all of them are scored, so a
     * guide-mandated or official effective-effect role cannot be out-scored by a generic
     * high-rated pick. Returns null when no valid formation includes them (no compatible tank,
     * position clash).
     */
    fun bestBattleFormationIncluding(
        roster: Collection<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
        requiredIds: Set<String>,
    ): LabyrinthTeamEvaluation? {
        if (requiredIds.isEmpty()) return bestBattleFormation(roster, context)
        val unique = roster.distinctBy(LabyrinthRoleProfile::characterId)
        if (!requiredIds.all { id -> unique.any { it.characterId == id } }) return null
        if (context.preferSingleDamageSystem && context.encounterStrategy?.vanguardIsolationRadius == null) {
            val cohesive = rankedFormationsInternal(
                roster = unique, context = context, requiredCharacterId = null, systemOnly = false,
                limit = 1, requireFrontmostTank = true, forbidMixedDamageSystem = true,
                requiredCharacterIds = requiredIds,
            ).firstOrNull()
            if (cohesive != null) return cohesive
        }
        return rankedFormationsInternal(
            roster = unique, context = context, requiredCharacterId = null, systemOnly = false,
            limit = 1, requireFrontmostTank = true, requiredCharacterIds = requiredIds,
        ).firstOrNull()
    }

    fun bestBattleFormation(
        roster: Collection<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
    ): LabyrinthTeamEvaluation? {
        if (context.preferSingleDamageSystem && context.encounterStrategy?.vanguardIsolationRadius == null) {
            val cohesive = rankedFormationsInternal(
                roster = roster,
                context = context,
                requiredCharacterId = null,
                systemOnly = false,
                limit = 1,
                requireFrontmostTank = true,
                forbidMixedDamageSystem = true,
            ).firstOrNull()
            if (cohesive != null) return cohesive
        }
        return rankedFormationsInternal(
            roster = roster,
            context = context,
            requiredCharacterId = null,
            systemOnly = false,
            limit = 1,
            requireFrontmostTank = true,
        ).firstOrNull()
    }

    /**
     * Best formation with the frontmost-tank gate switched off. Used only to fill Boss follow-up
     * slots that would otherwise stay empty: an unsafe team that lands a few hits is strictly
     * better than no team when the Boss is down to a sliver.
     */
    fun bestSupplementFormation(
        roster: Collection<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
    ): LabyrinthTeamEvaluation? = rankedFormationsInternal(
        roster = roster,
        context = context,
        requiredCharacterId = null,
        systemOnly = false,
        limit = 1,
        requireFrontmostTank = false,
    ).firstOrNull()

    fun bestSystemFormation(
        roster: Collection<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
        requiredCharacterId: String? = null,
    ): LabyrinthTeamEvaluation? = rankedFormationsInternal(
        roster = roster,
        context = context,
        requiredCharacterId = requiredCharacterId,
        systemOnly = true,
        limit = 1,
        requireFrontmostTank = false,
    ).firstOrNull()

    fun rankedFormations(
        roster: Collection<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
        requiredCharacterId: String? = null,
    ): List<LabyrinthTeamEvaluation> = rankedFormationsInternal(
        roster = roster,
        context = context,
        requiredCharacterId = requiredCharacterId,
        systemOnly = false,
        requireFrontmostTank = false,
    )

    fun rankedBattleFormations(
        roster: Collection<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
    ): List<LabyrinthTeamEvaluation> = rankedFormationsInternal(
        roster = roster,
        context = context,
        requiredCharacterId = null,
        systemOnly = false,
        requireFrontmostTank = true,
    )

    private fun rankedFormationsInternal(
        roster: Collection<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
        requiredCharacterId: String?,
        systemOnly: Boolean,
        limit: Int = scorer.config.teamSearchBeamWidth,
        requireFrontmostTank: Boolean,
        forbidMixedDamageSystem: Boolean = false,
        requiredCharacterIds: Set<String> = emptySet(),
    ): List<LabyrinthTeamEvaluation> {
        val unique = roster.distinctBy(LabyrinthRoleProfile::characterId)
        if (unique.isEmpty()) return emptyList()
        if (requiredCharacterId != null && unique.none { it.characterId == requiredCharacterId }) return emptyList()
        if (requireFrontmostTank && unique.none {
                it.isEligibleBattleVanguard(context, scorer.config.vanguardGate(context))
            }) {
            return emptyList()
        }
        val searchPool = if (unique.size <= scorer.config.exhaustiveRosterLimit) {
            unique
        } else if (requireFrontmostTank) {
            battleSearchPool(unique, context, reservedIds = requiredCharacterIds)
        } else {
            unique.sortedByDescending { individualSearchPriority(it, context) }
                .take(scorer.config.exhaustiveRosterLimit)
                .let { limited ->
                    if (requiredCharacterId == null || limited.any { it.characterId == requiredCharacterId }) {
                        limited
                    } else {
                        limited.dropLast(1) + requireNotNull(unique.firstOrNull { it.characterId == requiredCharacterId })
                    }
                }
        }
        val size = minOf(TEAM_SIZE, searchPool.size)
        // Worst candidate first. For equal scores keep the earlier enumeration, exactly like
        // the old stable full sort, without retaining tens of thousands of evaluations.
        val order = compareByDescending<RankedCandidate> { it.formationRisk }
            .thenBy { it.score }.thenByDescending { it.ordinal }
        val comboCount = binomialCount(searchPool.size, size)
        val filter = CombinationFilter(
            context = context,
            requiredCharacterId = requiredCharacterId,
            requiredCharacterIds = requiredCharacterIds,
            vanguardGate = if (requireFrontmostTank) scorer.config.vanguardGate(context) else null,
            forbidMixedDamageSystem = forbidMixedDamageSystem,
            systemOnly = systemOnly,
        )
        val ranked = if (searchThreads > 1 && comboCount >= PARALLEL_MIN_COMBINATIONS) {
            searchFormationsParallel(searchPool, size, comboCount.toInt(), filter, limit, order)
        } else {
            val best = PriorityQueue(order)
            var ordinal = 0L
            visitCombinations(searchPool, size) { team ->
                val candidate = filter.evaluate(team, ordinal++) ?: return@visitCombinations
                offer(best, candidate, limit)
            }
            best.sortedWith(order.reversed())
        }
        return ranked.map { scorer.evaluate(it.members, context) }
    }

    /** The per-team gate and score shared verbatim by the serial and parallel walks. */
    private inner class CombinationFilter(
        private val context: LabyrinthRoleDecisionContext,
        private val requiredCharacterId: String?,
        private val requiredCharacterIds: Set<String>,
        private val vanguardGate: Double?,
        private val forbidMixedDamageSystem: Boolean,
        private val systemOnly: Boolean,
    ) {
        fun evaluate(team: List<LabyrinthRoleProfile>, ordinal: Long): RankedCandidate? {
            if (requiredCharacterId != null && team.none { it.characterId == requiredCharacterId }) return null
            if (requiredCharacterIds.isNotEmpty() && !requiredCharacterIds.all { id -> team.any { it.characterId == id } }) return null
            if (vanguardGate != null && !team.hasEligibleVanguardAtActualFront(context, vanguardGate)) return null
            val evaluation = scorer.evaluateForSearch(team, context)
            if (forbidMixedDamageSystem &&
                evaluation.damageType != LabyrinthTeamDamageType.PHYSICAL &&
                evaluation.damageType != LabyrinthTeamDamageType.MAGIC
            ) {
                return null
            }
            val score = if (systemOnly) evaluation.systemScore else evaluation.score
            return RankedCandidate(evaluation.members, score, ordinal,
                labyrinthEncounterFormationRisk(team, context))
        }
    }

    private fun offer(best: PriorityQueue<RankedCandidate>, candidate: RankedCandidate, limit: Int) {
        val worst = best.peek()
        if (best.size < limit || (worst != null && requireNotNull(best.comparator()).compare(candidate, worst) > 0)) {
            if (best.size == limit) best.poll()
            best.add(candidate)
        }
    }

    /**
     * Splits the [comboCount] lexicographic combinations into [searchThreads] contiguous ranges,
     * ranks each range to its local top [limit], then merges. The merge order is (score desc,
     * enumeration index asc), which is the serial path's order: the serial ordinal counts only
     * teams that passed the gate, this one counts every enumerated combination, but both are
     * strictly increasing along the same enumeration, so every tie resolves to the same team.
     */
    private fun searchFormationsParallel(
        searchPool: List<LabyrinthRoleProfile>,
        size: Int,
        comboCount: Int,
        filter: CombinationFilter,
        limit: Int,
        order: Comparator<RankedCandidate>,
    ): List<RankedCandidate> {
        // One flat index buffer for every combination (C(24,5) x 5 = 212,520 ints, ~850 KB)
        // instead of tens of thousands of small arrays, which would be a single-threaded
        // allocation storm that eats the parallel gain.
        val flat = IntArray(comboCount * size)
        fillCombinationIndices(searchPool.size, size, flat)
        val chunkCount = minOf(searchThreads, comboCount)
        val chunkSize = (comboCount + chunkCount - 1) / chunkCount
        val tasks = (0 until chunkCount).map { chunk ->
            Callable {
                val best = PriorityQueue(order)
                val team = ArrayList<LabyrinthRoleProfile>(size)
                val from = chunk * chunkSize
                val until = minOf(comboCount, from + chunkSize)
                for (index in from until until) {
                    team.clear()
                    val base = index * size
                    for (slot in 0 until size) team.add(searchPool[flat[base + slot]])
                    val candidate = filter.evaluate(team, index.toLong()) ?: continue
                    // The candidate keeps evaluation.members, a fresh list, so reusing `team` is safe.
                    offer(best, candidate, limit)
                }
                best.toList()
            }
        }
        val partials = ROLE_SEARCH_EXECUTOR.invokeAll(tasks).map { it.get() }
        val merged = ArrayList<RankedCandidate>(partials.sumOf { it.size })
        partials.forEach(merged::addAll)
        merged.sortWith(order.reversed())
        return merged.take(limit)
    }

    /** C(n, k), used to size the parallel split. */
    private fun binomialCount(n: Int, k: Int): Long {
        if (k < 0 || k > n) return 0L
        var result = 1L
        for (index in 0 until k) result = result * (n - index) / (index + 1)
        return result
    }

    /** Writes every k-combination of 0 until n in the same lexicographic order as [visitCombinations]. */
    private fun fillCombinationIndices(n: Int, k: Int, target: IntArray) {
        val working = IntArray(k)
        var position = 0
        fun visit(start: Int, depth: Int) {
            if (depth == k) {
                val base = position * k
                for (slot in 0 until k) target[base + slot] = working[slot]
                position++
                return
            }
            val remaining = k - depth
            for (index in start..n - remaining) {
                working[depth] = index
                visit(index + 1, depth + 1)
            }
        }
        visit(0, 0)
    }

    /**
     * A bounded search must not trim away a low-scoring tank before combinations are generated.
     * Reserve one structurally valid cohort around the database-frontmost available tank, then
     * fill remaining beam input slots by the ordinary individual priority.
     */
    internal fun battleSearchPool(
        unique: List<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
        reservedIds: Set<String> = emptySet(),
    ): List<LabyrinthRoleProfile> {
        val limit = scorer.config.exhaustiveRosterLimit
        val teamSize = minOf(TEAM_SIZE, unique.size)
        val gate = scorer.config.vanguardGate(context)
        val frontmostTank = unique
            .asSequence()
            .filter { it.isEligibleBattleVanguard(context, gate) }
            .minWithOrNull(compareBy<LabyrinthRoleProfile> { it.position }.thenBy { it.characterId })
            ?: return emptyList()
        val tankPosition = requireNotNull(frontmostTank.position)
        val compatible = unique
            .asSequence()
            .filter { role ->
                role.characterId != frontmostTank.characterId &&
                    role.position != null &&
                    (role.isEligibleBattleVanguard(context, gate) ||
                        requireNotNull(role.position) > tankPosition)
            }
            .sortedWith(compareBy<LabyrinthRoleProfile> {
                labyrinthEncounterFormationRisk(listOf(frontmostTank, it), context)
            }.thenByDescending { individualSearchPriority(it, context) })
            .take(teamSize - 1)
            .toList()
        if (compatible.size != teamSize - 1) return emptyList()

        val structuralCohort = listOf(frontmostTank) + compatible
        // Explicitly reserved roles (a guide core the caller decided to build the team around)
        // always enter the pool. The generic Effective Effect hint alone does not: it contributes
        // only through the role's weighted individual score, so a low score can still leave that
        // role outside the bounded pool unless the caller reserves it.
        val reserved = unique.filter { it.characterId in reservedIds && it.characterId != frontmostTank.characterId }
        val forcedIds = (structuralCohort + reserved).mapTo(hashSetOf(), LabyrinthRoleProfile::characterId)
        val remainder = unique
            .asSequence()
            .filterNot { it.characterId in forcedIds }
            .sortedByDescending { individualSearchPriority(it, context) }
            .toList()
        return (structuralCohort + reserved + remainder).distinctBy { it.characterId }.take(limit)
    }

    private data class RankedCandidate(
        val members: List<LabyrinthRoleProfile>,
        val score: Double,
        val ordinal: Long,
        val formationRisk: Int,
    )

    private fun individualSearchPriority(role: LabyrinthRoleProfile, context: LabyrinthRoleDecisionContext): Double {
        val targetCount = context.targetCount
        val physical = when (targetCount.coerceAtMost(3)) {
            1 -> role.physicalDamage1Target
            2 -> role.physicalDamage2Target
            else -> role.physicalDamage3Target
        } ?: role.physicalDamagePotential
        val magic = when (targetCount.coerceAtMost(3)) {
            1 -> role.magicDamage1Target
            2 -> role.magicDamage2Target
            else -> role.magicDamage3Target
        } ?: role.magicDamagePotential
        val scenarioFunction = if (targetCount == 1) {
            role.functions.singleTargetDamage.orZero()
        } else {
            maxOf(
                role.functions.aoeDamage.orZero(),
                if (targetCount >= 3) role.functions.threeTargetDamage.orZero()
                else role.functions.twoTargetDamage.orZero(),
            )
        }
        val effectiveSystemBonus = if (
            canonicalLabyrinthRoleId(role.characterId) in context.effectiveCharacterIds &&
            (context.encounterStrategy?.preferEffectiveCharacters ?: true)
        ) {
            scorer.config.encounterEffectiveCharacterBonus
        } else {
            0.0
        }
        val guideCapabilityPriority = context.encounterStrategy?.preferredCapabilities.orEmpty().sumOf { capability ->
            if ((role.encounterCapabilityScore(capability) ?: -1.0) >= 50.0) {
                scorer.config.encounterPreferredCapabilityBonus
            } else {
                0.0
            }
        }
        // Keep the prefilter on the same conceptual scale as the final player/system blend.
        // Effective Effect is added only to the system side, so a low player score remains low
        // instead of being bypassed by an unconditional post-blend bonus.
        val modeledTeamUpliftPriority = maxOf(
            role.physicalTeamUplift.orZero(),
            role.magicTeamUplift.orZero(),
        ).coerceAtLeast(0.0).times(100.0).coerceAtMost(100.0)
        val baseSystemPriority = (
            maxOf(physical.orZero(), magic.orZero()) +
                role.functions.reliableVanguard.orZero() * 0.5 +
                modeledTeamUpliftPriority * 0.45 +
                maxOf(
                    role.functions.physicalDefenseDown.orZero(),
                    role.functions.magicDefenseDown.orZero(),
                ) * 0.3 +
                scenarioFunction * 0.2
            ) / 2.45
        val requirementPriority = encounterRequirementContribution(listOf(role), context.encounterStrategy) *
            scorer.config.encounterPreferredCapabilityBonus
        val systemPriority = (baseSystemPriority + effectiveSystemBonus + guideCapabilityPriority + requirementPriority)
            .coerceIn(0.0, 100.0)
        val playerScore = role.effectiveUserScore
        val playerWeight = if (playerScore == null) 0.0 else scorer.config.playerWeight
        return (playerScore ?: 0.0) * playerWeight + systemPriority * (1.0 - playerWeight)
    }

    private fun <T> visitCombinations(values: List<T>, size: Int, consume: (List<T>) -> Unit) {
        val working = ArrayList<T>(size)
        fun visit(start: Int) {
            if (working.size == size) {
                consume(working)
                return
            }
            val remaining = size - working.size
            for (index in start..values.size - remaining) {
                working += values[index]
                visit(index + 1)
                working.removeAt(working.lastIndex)
            }
        }
        visit(0)
    }

    private companion object {
        const val TEAM_SIZE = 5

        /**
         * Distinct-vanguard first teams tried when the beam holds no disjoint set. Each seed costs
         * one bounded search per follow-up team, so this stays small enough for on-device planning.
         */
        const val MAX_RESIDUAL_FIRST_TEAM_SEEDS = 6
    }
}

@Serializable
data class LabyrinthRoleChoiceConfig(
    /** Empty keeps schema-v1 payloads valid and resolves to 1.00 / 0.35 / 0.20. */
    val candidateTeamWeights: List<Double> = emptyList(),
    /** Schema-v1 field: now interpreted as the second-team deployment weight only. */
    val forcedCandidateTeamWeight: Double? = null,
    /** Schema-v1 field: now interpreted as the third-team deployment weight only. */
    val secondTeamPotentialWeight: Double? = null,
    val requireStrictProfiles: Boolean = true,
) {
    init {
        require(candidateTeamWeights.isEmpty() || candidateTeamWeights.size == 3)
        require(candidateTeamWeights.all { it >= 0.0 })
        require(candidateTeamWeights.isEmpty() || candidateTeamWeights.sum() > 0.0)
        require(forcedCandidateTeamWeight == null || forcedCandidateTeamWeight >= 0.0)
        require(secondTeamPotentialWeight == null || secondTeamPotentialWeight >= 0.0)
    }

    fun effectiveCandidateTeamWeights(): List<Double> = if (candidateTeamWeights.isNotEmpty()) {
        candidateTeamWeights
    } else {
        listOf(1.0, forcedCandidateTeamWeight ?: 0.35, secondTeamPotentialWeight ?: 0.20)
    }
}

data class LabyrinthRoleCandidateRanking(
    val characterId: String,
    val displayName: String,
    val score: Double,
    val playerScore: Double?,
    val systemScore: Double,
    /** Zero-based candidate deployment layer. */
    val candidateLayer: Int,
    val firstTeam: LabyrinthTeamEvaluation,
    val candidateTeam: LabyrinthTeamEvaluation,
    val secondTeam: LabyrinthTeamEvaluation?,
    val thirdTeam: LabyrinthTeamEvaluation?,
)

sealed interface LabyrinthOneRoleDecision {
    data class Ready(
        val chosen: LabyrinthRoleProfile,
        val rankings: List<LabyrinthRoleCandidateRanking>,
        val reasons: List<String>,
    ) : LabyrinthOneRoleDecision

    data class Unavailable(
        val reason: String,
        val missingProfileIds: List<String> = emptyList(),
        val incompleteProfileIds: List<String> = emptyList(),
    ) : LabyrinthOneRoleDecision
}

/** Evaluates three known characters; it is deliberately unrelated to connect-attribute choice. */
class LabyrinthRoleChoicePolicy(
    private val optimizer: LabyrinthTeamOptimizer,
    private val config: LabyrinthRoleChoiceConfig = LabyrinthRoleChoiceConfig(),
) {
    fun chooseOneRole(
        candidateIds: List<String>,
        acquiredCharacterIds: Set<String>,
        profiles: Map<String, LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
        requireStrictProfiles: Boolean = config.requireStrictProfiles,
    ): LabyrinthOneRoleDecision {
        if (candidateIds.isEmpty() || candidateIds.distinct().size != candidateIds.size) {
            return LabyrinthOneRoleDecision.Unavailable("候选角色必须稳定识别且互不重复")
        }
        // The game does not offer an already-owned role in a three-card reward. A collision is
        // therefore stale local roster evidence, not a reason to strand the run. Exclude the
        // visible candidates from the *scoring* roster so none is counted twice, but still rank
        // all three visible cards normally. The caller records the discrepancy for diagnosis.
        val scoringAcquiredIds = acquiredCharacterIds - candidateIds.toSet()
        val requiredIds = candidateIds.toSet() + scoringAcquiredIds
        val missing = requiredIds.filterNot(profiles::containsKey).sorted()
        if (missing.isNotEmpty()) {
            return LabyrinthOneRoleDecision.Unavailable(
                reason = "角色资料不完整，保持等待而不盲选：${missing.joinToString()}",
                missingProfileIds = missing,
            )
        }
        val incomplete = requiredIds.filterNot { profiles.getValue(it).isStrictDecisionReady }.sorted()
        if (requireStrictProfiles && incomplete.isNotEmpty()) {
            return LabyrinthOneRoleDecision.Unavailable(
                reason = "角色资料缺少严格决策所需的属性/职阶/物法/站位，保持等待：${incomplete.joinToString()}",
                incompleteProfileIds = incomplete,
            )
        }
        val acquired = scoringAcquiredIds.map(profiles::getValue)
        val rankings = candidateIds.map { candidateId ->
            val candidate = profiles.getValue(candidateId)
            val pool = acquired + candidate
            val placement = requireNotNull(bestCandidatePlacementPlan(pool, candidate, context))
            val playerScore = candidate.effectiveUserScore
            val playerWeight = optimizer.scoringConfig.playerWeight
            LabyrinthRoleCandidateRanking(
                characterId = candidateId,
                displayName = candidate.displayName,
                score = if (playerScore == null) {
                    placement.systemScore
                } else {
                    playerScore * playerWeight + placement.systemScore * (1.0 - playerWeight)
                },
                playerScore = playerScore,
                systemScore = placement.systemScore,
                candidateLayer = placement.candidateLayer,
                firstTeam = placement.teams.first(),
                candidateTeam = placement.candidateTeam,
                secondTeam = placement.teams.getOrNull(1),
                thirdTeam = placement.teams.getOrNull(2),
            )
        }.sortedWith(
            compareByDescending<LabyrinthRoleCandidateRanking> { it.score }
                .thenByDescending { profiles.getValue(it.characterId).effectiveUserScore.orZero() }
                .thenBy { candidateIds.indexOf(it.characterId) },
        )
        val best = rankings.first()
        val chosen = profiles.getValue(best.characterId)
        val reasons = buildList {
            val conflicted = candidateIds.filter(acquiredCharacterIds::contains)
            if (conflicted.isNotEmpty()) {
                add("画面候选与本地已获得名单冲突（${conflicted.joinToString()}）；按实际三选一画面重新评分")
            }
            add("${chosen.displayName}的最佳部署落点为第${best.candidateLayer + 1}队；第一队保持当前池最优组合")
            chosen.attribute?.let { attribute ->
                val sameAttribute = best.candidateTeam.attributeCounts[attribute]
                val bonus = best.candidateTeam.activeAttributeBonuses[attribute]
                if (bonus != null && sameAttribute != null) {
                    add("与队内${attribute.label}属性组成${sameAttribute}人档，角色自身及同属性角色获得${(bonus * 100).toInt()}%伤害")
                }
            }
            addAll(best.candidateTeam.reasons.filterNot { it.startsWith("预计") }.take(3))
            val runnerUp = rankings.getOrNull(1)
            if (runnerUp != null) {
                add("综合边际评分${formatScore(best.score)}，高于${runnerUp.displayName}的${formatScore(runnerUp.score)}")
            }
        }
        return LabyrinthOneRoleDecision.Ready(chosen, rankings, reasons)
    }

    private fun bestCandidatePlacementPlan(
        pool: List<LabyrinthRoleProfile>,
        candidate: LabyrinthRoleProfile,
        context: LabyrinthRoleDecisionContext,
    ): CandidatePlacementPlan? {
        val plans = buildList {
            config.effectiveCandidateTeamWeights().indices.forEach { candidateLayer ->
                if (pool.distinctBy(LabyrinthRoleProfile::characterId).size - 1 < candidateLayer * TEAM_SIZE) {
                    return@forEach
                }
                candidatePlacementPlan(pool, candidate, context, candidateLayer)?.let(::add)
            }
        }
        return plans.maxWithOrNull(
            compareBy<CandidatePlacementPlan> { it.systemScore }
                .thenBy { -it.candidateLayer },
        )
    }

    private fun candidatePlacementPlan(
        pool: List<LabyrinthRoleProfile>,
        candidate: LabyrinthRoleProfile,
        context: LabyrinthRoleDecisionContext,
        candidateLayer: Int,
    ): CandidatePlacementPlan? {
        var remaining = pool.distinctBy(LabyrinthRoleProfile::characterId)
        val teams = mutableListOf<LabyrinthTeamEvaluation>()
        config.effectiveCandidateTeamWeights().indices.forEach { layer ->
            if (layer > 0 && remaining.size < TEAM_SIZE) return@forEach
            val teamPool: List<LabyrinthRoleProfile>
            val requiredId: String?
            when {
                layer < candidateLayer -> {
                    teamPool = remaining.filterNot { it.characterId == candidate.characterId }
                    if (teamPool.size < TEAM_SIZE) return null
                    requiredId = null
                }
                layer == candidateLayer -> {
                    if (remaining.none { it.characterId == candidate.characterId }) return null
                    teamPool = remaining
                    requiredId = candidate.characterId
                }
                else -> {
                    teamPool = remaining
                    requiredId = null
                }
            }
            val team = optimizer.bestSystemFormation(teamPool, context, requiredId) ?: return null
            teams += team
            val used = team.members.map(LabyrinthRoleProfile::characterId).toSet()
            remaining = remaining.filterNot { it.characterId in used }
        }
        val candidateTeam = teams.getOrNull(candidateLayer)
            ?.takeIf { team -> team.members.any { it.characterId == candidate.characterId } }
            ?: return null
        val weights = config.effectiveCandidateTeamWeights()
        val weightSum = teams.indices.sumOf { weights[it] }
        val systemScore = teams.indices.sumOf { teams[it].systemScore * weights[it] } / weightSum
        return CandidatePlacementPlan(candidateLayer, teams, candidateTeam, systemScore)
    }

    private data class CandidatePlacementPlan(
        val candidateLayer: Int,
        val teams: List<LabyrinthTeamEvaluation>,
        val candidateTeam: LabyrinthTeamEvaluation,
        val systemScore: Double,
    )

    private companion object {
        const val TEAM_SIZE = 5

        /**
         * Distinct-vanguard first teams tried when the beam holds no disjoint set. Each seed costs
         * one bounded search per follow-up team, so this stays small enough for on-device planning.
         */
        const val MAX_RESIDUAL_FIRST_TEAM_SEEDS = 6
    }
}

enum class LabyrinthTeamPlanKind {
    FIRST_ATTEMPT_ONE_TEAM,
    ONE_TEAM_STABLE_KILL,
    MAIN_PLUS_CLEANUP,
    TWO_STABLE_TEAMS,
    THREE_TEAM_FALLBACK,
    INSUFFICIENT_ROLES,
    RESTART_RECOMMENDED,
    /** Boss follow-up teams formed without a qualified vanguard, purely to add damage. */
    SUPPLEMENT_FILL,
}

@Serializable
data class LabyrinthTeamPlanSearchConfig(
    val oneTeamKillScore: Double,
    val mainTeamScore: Double,
    val cleanupTeamScore: Double,
    val mainPlusCleanupCombinedScore: Double,
    val stableTeamScore: Double,
    val fallbackTeamScore: Double,
    val threeTeamCombinedScore: Double,
    val multiTeamBeamWidth: Int = 40,
) {
    init {
        listOf(
            oneTeamKillScore,
            mainTeamScore,
            cleanupTeamScore,
            mainPlusCleanupCombinedScore,
            stableTeamScore,
            fallbackTeamScore,
            threeTeamCombinedScore,
        ).forEach { require(it >= 0.0) }
        require(multiTeamBeamWidth > 0)
    }
}

data class LabyrinthTeamPlan(
    val kind: LabyrinthTeamPlanKind,
    val teams: List<LabyrinthTeamEvaluation>,
    val reason: String,
    /** Leading teams that passed the vanguard survival gate; any team after them is a damage supplement. */
    val safeTeamCount: Int = teams.size,
)

/**
 * Battle planning is intentionally two-stage:
 * 1) the initial attempt always sends the strongest single team;
 * 2) only after an observed failure may the caller request fallback/multi-team planning.
 *
 * This prevents paper thresholds from weakening the first attempt before the game has shown
 * that a special EX/Boss counter or an additional team is actually necessary.
 */
class LabyrinthTeamPlanSearcher(
    private val optimizer: LabyrinthTeamOptimizer,
    private val config: LabyrinthTeamPlanSearchConfig,
) {
    fun search(
        roster: Collection<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
    ): LabyrinthTeamPlan = initialSearch(roster, context)

    fun initialSearch(
        roster: Collection<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
    ): LabyrinthTeamPlan {
        if (roster.distinctBy(LabyrinthRoleProfile::characterId).isEmpty()) {
            return LabyrinthTeamPlan(
                LabyrinthTeamPlanKind.INSUFFICIENT_ROLES,
                emptyList(),
                "没有可用于编组的可靠角色",
            )
        }
        val guideCore = labyrinthEncounterGuideCore(roster, context)
        val coreFirst = if (guideCore.isEmpty()) null else optimizer.bestBattleFormationIncluding(roster, context, guideCore)
        val ordinary = if (coreFirst == null || context.encounterStrategy?.vanguardIsolationRadius != null) {
            optimizer.bestBattleFormation(roster, context)
        } else null
        val chosenCore = coreFirst?.takeIf { core ->
            ordinary == null || labyrinthEncounterFormationRisk(core.members, context) <=
                labyrinthEncounterFormationRisk(ordinary.members, context)
        }
        val first = chosenCore
            ?: ordinary
            // Nobody in this pool can lead. Refusing leaves the run with nothing to send and the
            // fight still has to be played, so compose the best team the pool allows and say
            // plainly that it leads without a qualified tank (2026-09-19, by request). Boss
            // follow-up slots have filled this way since 2026-09-17.
            //
            // Deliberately narrow: a pool that *does* hold a qualified vanguard but still cannot
            // form a team has a different problem (unknown 站位, too few roles), and its specific
            // reason below is what tells the user which. Only a genuinely tankless pool falls back.
            ?: return if (hasAnyEligibleVanguard(roster, context)) {
                LabyrinthTeamPlan(
                    LabyrinthTeamPlanKind.INSUFFICIENT_ROLES,
                    emptyList(),
                    "自动战斗要求实际一号位满足生存资格；当前角色池无法组成满足条件的队伍",
                )
            } else {
                vanguardlessPlan(roster, context)
            }
        val coreNames = guideCore.joinToString("、") { id ->
            roster.firstOrNull { it.characterId == id }?.displayName ?: id
        }
        val coreNote = when {
            guideCore.isEmpty() -> ""
            chosenCore != null -> "；已按攻略锁定核心角色：$coreNames"
            coreFirst != null -> "；有效效果核心与隔离前排冲突，优先减少近身队友"
            else -> "；攻略核心角色无法与合格一号位同队，退回普通最佳队"
        }
        return LabyrinthTeamPlan(
            LabyrinthTeamPlanKind.FIRST_ATTEMPT_ONE_TEAM,
            listOf(first),
            (if (context.optimizeBossVanguardSynergy) {
                "Boss主力队先要求一号位通过生存线，再将T的物理/法术队伍增益与其余四人联合优化，评分${formatScore(first.score)}"
            } else {
                "首次挑战使用满足“一号位生存资格”硬约束的当前最佳队伍，评分${formatScore(first.score)}；实际失败后才启用后续队伍"
            }) + coreNote,
        )
    }

    /**
     * Boss multi-team mode is a global roster-allocation problem, not three independent greedy
     * single-team searches. Pick the largest safe team count that can actually be formed (3 -> 2
     * -> 1), then maximize the combined score of disjoint teams at that count.
     */
    fun bossMultiTeamSearch(
        roster: Collection<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
        requestedTeams: Int = 3,
        excludedTeamSignatures: Set<String> = emptySet(),
        survivalRecovery: Boolean = false,
        /**
         * Fill the slots the safe search cannot cover with vanguard-less damage teams instead of
         * leaving them empty. 2026-09-17 user: a Boss survived on a sliver because the third slot
         * was left blank when no third qualified tank existed. Safe teams always come first, so
         * the tank-led teams still take the opening hits.
         */
        supplementTeams: Boolean = true,
    ): LabyrinthTeamPlan {
        require(requestedTeams in 1..3)
        val unique = roster.distinctBy(LabyrinthRoleProfile::characterId)
        if (unique.size < TEAM_SIZE) {
            return LabyrinthTeamPlan(
                LabyrinthTeamPlanKind.INSUFFICIENT_ROLES,
                emptyList(),
                "Boss可用角色不足五名，无法组成安全队伍",
            )
        }
        val searchContext = if (survivalRecovery) context.copy(survivalRecovery = true) else context
        val ranked = optimizer.rankedBattleFormations(unique, searchContext)
            .filterNot { evaluation -> evaluation.teamSignature() in excludedTeamSignatures }
        if (ranked.isEmpty()) {
            if (supplementTeams) {
                val fill = supplementFill(emptyList(), unique, searchContext, requestedTeams, excludedTeamSignatures)
                if (fill.isNotEmpty()) {
                    return LabyrinthTeamPlan(
                        LabyrinthTeamPlanKind.SUPPLEMENT_FILL,
                        fill,
                        "Boss当前角色池没有合格一号位；按输出补刀队填充${fill.size}队，不留空队",
                        safeTeamCount = 0,
                    )
                }
            }
            return LabyrinthTeamPlan(
                LabyrinthTeamPlanKind.INSUFFICIENT_ROLES,
                emptyList(),
                "Boss当前角色池无法组成满足一号位生存资格的完整队伍",
            )
        }

        val vanguardGate = optimizer.scoringConfig.vanguardGate(searchContext)
        val qualifiedVanguards = unique.count {
            it.isEligibleBattleVanguard(searchContext, vanguardGate)
        }
        val gateNote = if (searchContext.relaxedVanguardGate) {
            "；失败后放宽一号位生存线至${formatScore(vanguardGate)}"
        } else {
            ""
        }
        val fullTeamCapacity = unique.size / TEAM_SIZE
        val safeCapacity = minOf(requestedTeams, qualifiedVanguards, fullTeamCapacity)
        if (safeCapacity <= 0) {
            return LabyrinthTeamPlan(
                LabyrinthTeamPlanKind.INSUFFICIENT_ROLES,
                emptyList(),
                "Boss没有可确认的安全一号位，无法自动编组",
            )
        }

        // The beam holds the globally best formations, so pairing inside it gives the best answer
        // whenever a disjoint pair exists there. It often does not: on multi-target Bosses the top
        // formations reuse one AOE/DOT core, so every beam entry overlaps. Falling back to a
        // residual-pool search then still finds real multi-team plans, and it keeps only one
        // evaluation per follow-up team instead of widening the beam, which matters on device.
        val beam = ranked
        for (targetTeams in safeCapacity downTo 2) {
            val teams = when (targetTeams) {
                3 -> bestDisjointTriple(beam, enforceThresholds = false)
                2 -> bestDisjointPair(beam) { _, _ -> true }
                else -> null
            }
                ?: residualPoolTeams(
                    beam = beam,
                    unique = unique,
                    context = searchContext,
                    targetTeams = targetTeams,
                    excludedTeamSignatures = excludedTeamSignatures,
                )
                ?: continue
            val combined = teams.sumOf(LabyrinthTeamEvaluation::score)
            val kind = if (targetTeams == 3) {
                LabyrinthTeamPlanKind.THREE_TEAM_FALLBACK
            } else {
                LabyrinthTeamPlanKind.TWO_STABLE_TEAMS
            }
            val fill = if (supplementTeams) {
                supplementFill(teams, unique, searchContext, requestedTeams, excludedTeamSignatures)
            } else {
                emptyList()
            }
            return LabyrinthTeamPlan(
                kind,
                teams + fill,
                "Boss多队安全容量${targetTeams}队（请求${requestedTeams}队；可靠一号位${qualifiedVanguards}名；完整队伍容量${fullTeamCapacity}$gateNote），" +
                    "按${targetTeams}队总评分优化，总分${formatScore(combined)}" + supplementNote(fill.size),
                safeTeamCount = teams.size,
            )
        }

        val single = if (
            context.optimizeBossVanguardSynergy &&
            !survivalRecovery &&
            excludedTeamSignatures.isEmpty()
        ) {
            initialSearch(unique, context).teams.singleOrNull() ?: ranked.first()
        } else {
            ranked.first()
        }
        val fill = if (supplementTeams) {
            supplementFill(listOf(single), unique, searchContext, requestedTeams, excludedTeamSignatures)
        } else {
            emptyList()
        }
        return LabyrinthTeamPlan(
            LabyrinthTeamPlanKind.FIRST_ATTEMPT_ONE_TEAM,
            listOf(single) + fill,
            "Boss仅能安全组成1队（可靠一号位${qualifiedVanguards}名$gateNote），按单队评分优化，评分${formatScore(single.score)}" +
                supplementNote(fill.size),
            safeTeamCount = 1,
        )
    }

    /**
     * Follow-up teams for the slots [safeTeams] leaves open, built from the leftover roster with
     * the frontmost-tank gate off. Never reorders or replaces a safe team.
     */
    /**
     * One damage-only team for a pool with no qualified vanguard at all.
     *
     * [LabyrinthTeamPlanKind.SUPPLEMENT_FILL] with `safeTeamCount = 0` is the existing contract
     * for "this team leads without a tank"; callers already surface that instead of treating it
     * as a normal safe team.
     */
    private fun hasAnyEligibleVanguard(
        roster: Collection<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
    ): Boolean {
        val gate = optimizer.scoringConfig.vanguardGate(context)
        return roster.any { it.isEligibleBattleVanguard(context, gate) }
    }

    private fun vanguardlessPlan(
        roster: Collection<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
        excludedTeamSignatures: Set<String> = emptySet(),
    ): LabyrinthTeamPlan {
        val unique = roster.distinctBy(LabyrinthRoleProfile::characterId)
        val team = optimizer.bestSupplementFormation(unique, context)
            ?.takeUnless { it.teamSignature() in excludedTeamSignatures }
            ?: return LabyrinthTeamPlan(
                LabyrinthTeamPlanKind.INSUFFICIENT_ROLES,
                emptyList(),
                "自动战斗要求实际一号位满足生存资格；当前角色池无法组成满足条件的队伍",
            )
        return LabyrinthTeamPlan(
            LabyrinthTeamPlanKind.SUPPLEMENT_FILL,
            listOf(team),
            "当前角色池没有满足生存资格的一号位；按无T阵容上场，评分${formatScore(team.score)}",
            safeTeamCount = 0,
        )
    }

    private fun supplementFill(
        safeTeams: List<LabyrinthTeamEvaluation>,
        unique: List<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
        requestedTeams: Int,
        excludedTeamSignatures: Set<String>,
    ): List<LabyrinthTeamEvaluation> {
        val used = safeTeams.flatMapTo(hashSetOf()) { team -> team.members.map(LabyrinthRoleProfile::characterId) }
        val fill = mutableListOf<LabyrinthTeamEvaluation>()
        while (safeTeams.size + fill.size < requestedTeams) {
            val remaining = unique.filterNot { it.characterId in used }
            if (remaining.size < TEAM_SIZE) break
            val next = optimizer.bestSupplementFormation(remaining, context)
                ?.takeUnless { it.teamSignature() in excludedTeamSignatures }
                ?: break
            fill += next
            next.members.forEach { used += it.characterId }
        }
        return fill
    }

    private fun supplementNote(count: Int): String =
        if (count == 0) "" else "；其后${count}队无合格一号位，作为输出补刀队填充而不留空"

    fun fallbackSearch(
        roster: Collection<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
        excludedTeamSignatures: Set<String> = emptySet(),
    ): LabyrinthTeamPlan {
        if (roster.distinctBy(LabyrinthRoleProfile::characterId).size < 5) {
            return LabyrinthTeamPlan(
                LabyrinthTeamPlanKind.INSUFFICIENT_ROLES,
                emptyList(),
                "首战失败后可用角色不足五名，无法生成兜底队",
            )
        }
        val ranked = optimizer.rankedBattleFormations(roster, context.copy(survivalRecovery = true))
            .filterNot { evaluation ->
                evaluation.members
                    .map(LabyrinthRoleProfile::characterId)
                    .map(::canonicalLabyrinthRoleId)
                    .sorted()
                    .joinToString(",") in excludedTeamSignatures
            }
        if (ranked.isEmpty()) {
            // Same reasoning as the first attempt: with nobody fit to lead, a vanguard-less retry
            // beats leaving the run parked on the failure page. A pool that does have a tank but
            // exhausted its untried formations keeps the original message.
            if (!hasAnyEligibleVanguard(roster, context)) {
                return vanguardlessPlan(roster, context, excludedTeamSignatures)
            }
            return LabyrinthTeamPlan(
                LabyrinthTeamPlanKind.INSUFFICIENT_ROLES,
                emptyList(),
                "首战失败后仍无法组成未重复失败阵容且实际一号位为T（掩护者）的兜底队",
            )
        }
        val first = ranked.first()
        if (context.encounterStrategy?.vanguardIsolationRadius != null) {
            return LabyrinthTeamPlan(
                LabyrinthTeamPlanKind.FIRST_ATTEMPT_ONE_TEAM,
                listOf(first),
                "首战失败后按隔离前排策略选择未重复失败的队伍，评分${formatScore(first.score)}",
            )
        }
        if (first.score >= config.oneTeamKillScore) {
            return LabyrinthTeamPlan(
                LabyrinthTeamPlanKind.ONE_TEAM_STABLE_KILL,
                listOf(first),
                "首战失败后重新计算到一支可稳定击杀的特化队，评分${formatScore(first.score)}",
            )
        }

        val beam = ranked.take(config.multiTeamBeamWidth)
        val mainCleanup = bestDisjointPair(beam) { main, cleanup ->
            main.score >= config.mainTeamScore &&
                cleanup.score >= config.cleanupTeamScore &&
                main.score + cleanup.score >= config.mainPlusCleanupCombinedScore
        }
        if (mainCleanup != null) {
            return LabyrinthTeamPlan(
                LabyrinthTeamPlanKind.MAIN_PLUS_CLEANUP,
                mainCleanup,
                "一队暂不足单杀，采用主力队＋第二队收尾",
            )
        }

        val twoStable = bestDisjointPair(beam) { firstTeam, secondTeam ->
            firstTeam.score >= config.stableTeamScore && secondTeam.score >= config.stableTeamScore
        }
        if (twoStable != null) {
            return LabyrinthTeamPlan(
                LabyrinthTeamPlanKind.TWO_STABLE_TEAMS,
                twoStable,
                "未找到主力＋收尾阈值方案，改用两队稳定击杀",
            )
        }

        val three = bestDisjointTriple(beam)
        if (three != null) {
            return LabyrinthTeamPlan(
                LabyrinthTeamPlanKind.THREE_TEAM_FALLBACK,
                three,
                "前两档方案均不足，使用三队兜底",
            )
        }
        return LabyrinthTeamPlan(
            LabyrinthTeamPlanKind.RESTART_RECOMMENDED,
            listOf(first),
            "没有达到任何可靠击杀阈值，建议重开；当前最佳一队${formatScore(first.score)}",
        )
    }

    /**
     * Builds [targetTeams] disjoint teams by fixing a first team and re-searching the remaining
     * roster, used only when no disjoint set exists inside the ranked beam.
     *
     * First-team candidates are taken from the beam but deduplicated by vanguard, because a beam
     * whose entries all overlap is usually a beam that reuses one frontliner. Each follow-up team
     * is a fresh bounded search over the leftover pool that retains a single evaluation, so this
     * costs a few extra searches rather than a wider beam held in memory.
     */
    private fun residualPoolTeams(
        beam: List<LabyrinthTeamEvaluation>,
        unique: List<LabyrinthRoleProfile>,
        context: LabyrinthRoleDecisionContext,
        targetTeams: Int,
        excludedTeamSignatures: Set<String>,
    ): List<LabyrinthTeamEvaluation>? {
        if (unique.size < targetTeams * TEAM_SIZE) return null
        val seeds = beam
            .distinctBy(LabyrinthTeamEvaluation::vanguardCharacterId)
            .take(MAX_RESIDUAL_FIRST_TEAM_SEEDS)
        var best: List<LabyrinthTeamEvaluation>? = null
        var bestScore = Double.NEGATIVE_INFINITY
        seeds.forEach { seed ->
            val teams = mutableListOf(seed)
            val used = seed.members.mapTo(hashSetOf(), LabyrinthRoleProfile::characterId)
            while (teams.size < targetTeams) {
                val remaining = unique.filterNot { it.characterId in used }
                val next = optimizer.bestBattleFormation(remaining, context)
                    ?.takeUnless { it.teamSignature() in excludedTeamSignatures }
                    ?: break
                teams += next
                next.members.forEach { used += it.characterId }
            }
            if (teams.size < targetTeams) return@forEach
            val score = teams.sumOf(LabyrinthTeamEvaluation::score)
            if (score > bestScore) {
                best = teams.toList()
                bestScore = score
            }
        }
        return best
    }

    private fun bestDisjointPair(
        teams: List<LabyrinthTeamEvaluation>,
        accepted: (LabyrinthTeamEvaluation, LabyrinthTeamEvaluation) -> Boolean,
    ): List<LabyrinthTeamEvaluation>? {
        var best: List<LabyrinthTeamEvaluation>? = null
        var bestScore = Double.NEGATIVE_INFINITY
        teams.forEachIndexed { firstIndex, first ->
            val firstIds = first.members.map(LabyrinthRoleProfile::characterId).toSet()
            teams.drop(firstIndex + 1).forEach { second ->
                if (second.members.any { it.characterId in firstIds } || !accepted(first, second)) return@forEach
                val score = first.score + second.score
                if (score > bestScore) {
                    best = listOf(first, second)
                    bestScore = score
                }
            }
        }
        return best
    }

    private fun bestDisjointTriple(
        teams: List<LabyrinthTeamEvaluation>,
        enforceThresholds: Boolean = true,
    ): List<LabyrinthTeamEvaluation>? {
        var best: List<LabyrinthTeamEvaluation>? = null
        var bestScore = Double.NEGATIVE_INFINITY
        teams.forEachIndexed { firstIndex, first ->
            val firstIds = first.members.map(LabyrinthRoleProfile::characterId).toSet()
            teams.drop(firstIndex + 1).forEachIndexed secondLoop@{ relativeSecondIndex, second ->
                if (second.members.any { it.characterId in firstIds }) return@secondLoop
                val used = firstIds + second.members.map(LabyrinthRoleProfile::characterId)
                teams.drop(firstIndex + relativeSecondIndex + 2).forEach thirdLoop@{ third ->
                    if (third.members.any { it.characterId in used }) return@thirdLoop
                    if (enforceThresholds && listOf(first, second, third).any { it.score < config.fallbackTeamScore }) return@thirdLoop
                    val score = first.score + second.score + third.score
                    if ((!enforceThresholds || score >= config.threeTeamCombinedScore) && score > bestScore) {
                        best = listOf(first, second, third)
                        bestScore = score
                    }
                }
            }
        }
        return best
    }

    private companion object {
        const val TEAM_SIZE = 5

        /**
         * Distinct-vanguard first teams tried when the beam holds no disjoint set. Each seed costs
         * one bounded search per follow-up team, so this stays small enough for on-device planning.
         */
        const val MAX_RESIDUAL_FIRST_TEAM_SEEDS = 6
    }
}

/**
 * Roles the first EX/Boss attempt is built around rather than merely nudged toward.
 *
 * The in-game effective-effect list is the game's own statement of what works against this
 * enemy, and a hard requirement such as "at least two DOT dealers" is the guide's stated win
 * condition. Both used to be small scoring bonuses (+8 system side at 30 percent weight, +3 per
 * requirement), so a roster of highly rated generic attackers out-scored them every time and the
 * automatic team ignored the guide (2026-09-16 00:26 bundle: the only DOT dealer owned was
 * confirmed as effective and never fielded). Reserve them and let the search fill the rest.
 *
 * Bounded to leave a tank slot and at least one free slot, and skips a role that would have to
 * stand in front of every eligible vanguard.
 */
internal fun labyrinthEncounterGuideCore(
    roster: Collection<LabyrinthRoleProfile>,
    context: LabyrinthRoleDecisionContext,
): Set<String> {
    val strategy = context.encounterStrategy ?: return emptySet()
    val unique = roster.distinctBy(LabyrinthRoleProfile::characterId)
    val core = linkedSetOf<String>()
    if (strategy.preferEffectiveCharacters) {
        unique.filter { canonicalLabyrinthRoleId(it.characterId) in context.effectiveCharacterIds }
            .sortedByDescending { it.effectiveUserScore ?: 0.0 }
            .forEach { core += it.characterId }
    }
    strategy.requirements.filter { it.hard }.forEach { requirement ->
        fun covers(role: LabyrinthRoleProfile) = evaluateEncounterRequirement(listOf(role), requirement).matchedCount > 0
        val already = unique.count { it.characterId in core && covers(it) }
        unique.filter { it.characterId !in core && covers(it) }
            .sortedByDescending { role -> requirement.anyOf.maxOf { role.encounterCapabilityScore(it) ?: 0.0 } }
            .take((requirement.minimumCount - already).coerceAtLeast(0))
            .forEach { core += it.characterId }
    }
    val eligibleTanks = unique.filter { it.isEligibleBattleVanguard(context) }
    return core.filter { id ->
        val role = unique.first { it.characterId == id }
        val position = role.position ?: return@filter false
        eligibleTanks.any { tank ->
            tank.characterId == id || if (strategy.vanguardIsolationRadius != null) {
                role.roleClass != "掩护者" && position - (tank.position ?: Int.MAX_VALUE) > strategy.vanguardIsolationRadius
            } else {
                (tank.position ?: Int.MAX_VALUE) <= position
            }
        }
    }.take(ENCOUNTER_GUIDE_CORE_MAX).toSet()
}

private const val ENCOUNTER_GUIDE_CORE_MAX = 3

private fun LabyrinthTeamEvaluation.teamSignature(): String = members
    .map(LabyrinthRoleProfile::characterId)
    .map(::canonicalLabyrinthRoleId)
    .sorted()
    .joinToString(",")

private fun LabyrinthTeamDamageType.label(): String = when (this) {
    LabyrinthTeamDamageType.PHYSICAL -> "物理"
    LabyrinthTeamDamageType.MAGIC -> "法术"
    LabyrinthTeamDamageType.MIXED -> "混合"
    LabyrinthTeamDamageType.NONE -> "无有效"
}

private fun validatePercent(name: String, value: Double) {
    require(value in 0.0..100.0) { "$name must be in 0..100, was $value" }
}

private fun validateOptionalPercent(name: String, value: Double?) {
    if (value != null) validatePercent(name, value)
}

private fun Double?.orZero(): Double = this ?: 0.0

private fun formatScore(value: Double): String = "%.2f".format(value)
