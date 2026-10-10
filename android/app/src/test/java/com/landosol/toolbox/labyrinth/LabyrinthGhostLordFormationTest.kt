package com.landosol.toolbox.labyrinth

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class LabyrinthGhostLordFormationTest {
    private val ghost = LabyrinthExEncounterCatalog.singleTarget.single { it.id == "ghost_lord" }
    private val context = LabyrinthRoleDecisionContext(
        defenseMarkStacks = 4, encounterStrategy = ghost, preferSingleDamageSystem = true,
    )
    private val scoring = LabyrinthTeamScoringConfig(attributeDamageBonus = (1..5).associateWith { 0.0 }, playerWeight = 1.0)

    @Test fun `four distant allies beat higher rated melee and a cohesive packed team`() {
        val roster = standardRoster()
        val result = planner(roster).initialRecommendation(roster.keys, context) as LabyrinthBattleTeamRecommendationResult.Ready
        assertEquals(setOf("tank", "rear1", "rear2", "rear3", "rear4"), result.recommendation.members.map { it.characterId }.toSet())
        assertEquals(LabyrinthTeamDamageType.MIXED, result.recommendation.damageType)
        assertTrue(result.recommendation.reasons.any { it.contains("隔离前排") })
    }

    @Test fun `nearby effective roles cannot override the isolated formation`() {
        val roster = standardRoster()
        val result = planner(roster).initialRecommendation(
            roster.keys, context.copy(effectiveCharacterIds = setOf("melee1", "melee2", "rear1")),
        ) as LabyrinthBattleTeamRecommendationResult.Ready
        assertEquals(0, labyrinthEncounterFormationRisk(result.recommendation.members.map { roster.getValue(it.characterId) }, context))
        assertTrue(result.recommendation.members.any { it.characterId == "rear1" })
    }

    @Test fun `bounded serial and parallel searches preserve low rated rear picks`() {
        val roster = (standardRoster().values + (5..28).map { role("melee$it", 180 + it, 100.0) })
            .distinctBy { it.characterId }
        val serial = requireNotNull(LabyrinthTeamOptimizer(LabyrinthTeamScorer(scoring), searchThreads = 1).bestBattleFormation(roster, context))
        val parallel = requireNotNull(LabyrinthTeamOptimizer(LabyrinthTeamScorer(scoring), searchThreads = 4).bestBattleFormation(roster, context))
        assertEquals(0, labyrinthEncounterFormationRisk(serial.members, context))
        assertEquals(serial.members.map { it.characterId }, parallel.members.map { it.characterId })
    }

    @Test fun `incomplete rear roster still fields five and minimizes nearby allies`() {
        val roster = standardRoster().filterKeys { it !in setOf("rear3", "rear4") }
        val result = planner(roster).initialRecommendation(roster.keys, context) as LabyrinthBattleTeamRecommendationResult.Ready
        assertEquals(5, result.recommendation.members.size)
        assertTrue(result.recommendation.members.map { it.characterId }.containsAll(listOf("rear1", "rear2")))
        assertTrue(result.recommendation.reasons.any { it.contains("仍有2名") })
    }

    @Test fun `ghost survival uses magic durability without overwriting explicit caller knowledge`() {
        val magicTank = role("magicTank", 100, 80.0, tank = true).copy(
            vanguardProfile = LabyrinthVanguardProfile(physicalDurability = 10.0, magicDurability = 90.0),
        )
        assertTrue(magicTank.isEligibleBattleVanguard(context))
        assertFalse(magicTank.isEligibleBattleVanguard(context.copy(enemyDamageType = LabyrinthEnemyDamageType.PHYSICAL)))
        assertFalse(magicTank.isEligibleBattleVanguard(context.copy(encounterStrategy = null)))
    }

    @Test fun `second failure healer swap cannot reintroduce nearby allies`() {
        val roster = standardRoster() + listOf(
            role("nearHeal", 250, 100.0, healing = 100.0),
            role("rearHeal", 800, 60.0, healing = 80.0),
        ).associateBy { it.characterId }
        val failed = labyrinthBattleTeamSignature(listOf("tank", "rear1", "rear2", "rear3", "rear4"))
        val result = planner(roster).retryRecommendation(
            roster.keys, context, setOf(failed), retryNumber = 2, lastFailedTeamSignature = failed,
        ) as LabyrinthBattleTeamRecommendationResult.Ready
        val ids = result.recommendation.members.map { it.characterId }
        assertFalse(ids.contains("nearHeal"))
        assertTrue(ids.contains("rearHeal"))
        assertEquals(0, labyrinthEncounterFormationRisk(ids.map(roster::getValue), context))
    }

    @Test fun `extra protector outside the radius is not mistaken for one tank four rear allies`() {
        val roster = standardRoster() + ("extraTank" to role("extraTank", 900, 100.0, tank = true))
        val result = planner(roster).initialRecommendation(roster.keys, context) as LabyrinthBattleTeamRecommendationResult.Ready
        assertFalse(result.recommendation.members.any { it.characterId == "extraTank" })
    }

    @Test fun `radius boundary and unknown positions are treated as exposed`() {
        val tank = role("tank", 100, 80.0, tank = true)
        assertEquals(1, labyrinthEncounterFormationRisk(listOf(tank, role("boundary", 400, 80.0)), context))
        assertEquals(0, labyrinthEncounterFormationRisk(listOf(tank, role("outside", 401, 80.0)), context))
        assertEquals(1, labyrinthEncounterFormationRisk(listOf(tank, role("unknown", 500, 80.0).copy(position = null)), context))
        val unknownTeam = listOf(tank, role("unknown1", 500, 80.0), role("unknown2", 600, 80.0))
            .map { it.copy(position = null) }
        assertEquals(2, labyrinthEncounterFormationRisk(unknownTeam, context))
    }

    @Test fun `tankless fallback still returns a team with the limitation exposed`() {
        val roster = standardRoster().filterKeys { it != "tank" }
        val result = planner(roster).initialRecommendation(roster.keys, context) as LabyrinthBattleTeamRecommendationResult.Ready
        assertEquals(5, result.recommendation.members.size)
        assertEquals(0, result.recommendation.safeBossTeamCount)
    }

    @Test fun `production profiles yield an isolated front with known magic survival`() {
        val file = generateSequence(File(".").canonicalFile, File::getParentFile)
            .map { File(it, "android/app/src/main/assets/resource-packs/cn-bilibili/labyrinth-role-decision.json") }
            .first(File::isFile)
        val runtime = (LabyrinthRoleDecisionDataParser.parse(file.readText()) as LabyrinthRoleDecisionDataResult.Ready).runtime
        val result = runtime.battleTeamRecommendationPlanner.initialRecommendation(runtime.profiles.keys, context)
            as LabyrinthBattleTeamRecommendationResult.Ready
        val members = result.recommendation.members.map { runtime.profiles.getValue(it.characterId) }
        assertEquals(0, labyrinthEncounterFormationRisk(members, context))
        assertTrue(runtime.profiles.getValue(result.recommendation.vanguard.characterId).isEligibleBattleVanguard(context))
    }

    @Test fun `ordinary encounters retain their generic player rating preference`() {
        val roster = standardRoster()
        val ordinary = context.copy(encounterStrategy = LabyrinthExEncounterCatalog.singleTarget.single { it.id == "antimatter_beast" })
        val result = planner(roster).initialRecommendation(roster.keys, ordinary) as LabyrinthBattleTeamRecommendationResult.Ready
        assertTrue(result.recommendation.members.any { it.characterId.startsWith("melee") })
    }

    private fun standardRoster() = buildList {
        add(role("tank", 100, 80.0, tank = true))
        (1..4).forEach { add(role("melee$it", 150 + it * 10, 100.0)) }
        (1..4).forEach { index ->
            add(role("rear$index", 450 + index * 50, 35.0).let {
                if (index == 4) it.copy(damageType = LabyrinthRoleDamageType.MAGIC,
                    physicalDamagePotential = 0.0, magicDamagePotential = 70.0) else it
            })
        }
    }.associateBy { it.characterId }

    private fun planner(roster: Map<String, LabyrinthRoleProfile>) = LabyrinthBattleTeamRecommendationPlanner(
        roster, LabyrinthTeamPlanSearcher(
            LabyrinthTeamOptimizer(LabyrinthTeamScorer(scoring)),
            LabyrinthTeamPlanSearchConfig(
                oneTeamKillScore = 0.0, mainTeamScore = 0.0, cleanupTeamScore = 0.0,
                mainPlusCleanupCombinedScore = 0.0, stableTeamScore = 0.0,
                fallbackTeamScore = 0.0, threeTeamCombinedScore = 0.0,
            ),
        ),
    )

    private fun role(id: String, position: Int, rating: Double, tank: Boolean = false, healing: Double = 0.0) = LabyrinthRoleProfile(
        characterId = id, displayName = id, position = position, userScore = rating,
        roleClass = if (tank) "掩护者" else if (healing > 0) "治疗者" else "攻击者",
        attribute = LabyrinthCharacterAttribute.LIGHT, damageType = LabyrinthRoleDamageType.PHYSICAL,
        physicalDamagePotential = 70.0, magicDamagePotential = 0.0,
        functions = LabyrinthRoleFunctions(reliableVanguard = if (tank) 100.0 else 0.0, healing = healing),
    )
}
