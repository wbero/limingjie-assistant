package com.landosol.toolbox.labyrinth

/** Explicit caller knowledge wins; the guide fills only an otherwise unknown damage type. */
internal val LabyrinthRoleDecisionContext.effectiveEnemyDamageType: LabyrinthEnemyDamageType
    get() = if (enemyDamageType != LabyrinthEnemyDamageType.UNKNOWN) enemyDamageType
    else encounterStrategy?.enemyDamageType ?: LabyrinthEnemyDamageType.UNKNOWN

/**
 * Count allies that violate the guide's isolated-front formation. A lower count wins before
 * player rating/system cohesion, so a high-rated melee cannot defeat four available rear picks.
 * This models database starting positions, not a guarantee about live UB radius after movement.
 */
internal fun labyrinthEncounterFormationRisk(
    members: Collection<LabyrinthRoleProfile>,
    context: LabyrinthRoleDecisionContext,
): Int {
    val radius = context.encounterStrategy?.vanguardIsolationRadius ?: return 0
    val front = members.minByOrNull { it.position ?: Int.MAX_VALUE } ?: return 0
    val frontPosition = front.position ?: return (members.size - 1).coerceAtLeast(0)
    return members.count { role ->
        role.characterId != front.characterId &&
            (role.position == null || role.position - frontPosition <= radius || role.roleClass == "掩护者")
    }
}

internal fun labyrinthEncounterFormationReason(
    members: Collection<LabyrinthRoleProfile>,
    context: LabyrinthRoleDecisionContext,
): String? {
    val radius = context.encounterStrategy?.vanguardIsolationRadius ?: return null
    val risk = labyrinthEncounterFormationRisk(members, context)
    val safeAllies = (members.size - 1 - risk).coerceAtLeast(0)
    return "${context.encounterStrategy.identityName}隔离前排：${safeAllies}名队友起始站位远于一号位${radius}且非额外掩护者" +
        if (risk > 0) "；仍有${risk}名近身/额外掩护者或站位未知，按可用角色尽量减少波及" else "；优先一名前排与中后排搭配"
}
