package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.outcome.PublicSingleTurnProjector
import jbro.cobblemon.morebattlecontent.betterai.state.PublicTurnProjection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalSideGuardProjectionTest {
    @Test
    fun `wide guard blocks a protectable spread attack for the whole side`() {
        val outcome = project(
            guard("wideguard"),
            attack("rockslide", priority = 0, pattern = BattleMoveTargetPattern.ALL_OPPONENTS),
        )

        assertEquals(1.0, hp(outcome, GUARD_USER), 1e-9)
        assertEquals(1.0, hp(outcome, PARTNER), 1e-9)
        assertTrue(outcome.directDamage.amounts.isEmpty())
    }

    @Test
    fun `wide guard does not block a single target attack`() {
        val outcome = project(
            guard("wideguard"),
            attack("rockslide", priority = 0, pattern = BattleMoveTargetPattern.SELECTED_OPPONENT),
        )

        assertEquals(1.0, hp(outcome, GUARD_USER), 1e-9)
        assertTrue(hp(outcome, PARTNER) < 1.0)
    }

    @Test
    fun `quick guard blocks positive priority but not ordinary priority`() {
        val blocked = project(
            guard("quickguard"),
            attack("aquajet", priority = 1, pattern = BattleMoveTargetPattern.SELECTED_OPPONENT),
        )
        val ordinary = project(
            guard("quickguard"),
            attack("watergun", priority = 0, pattern = BattleMoveTargetPattern.SELECTED_OPPONENT),
        )

        assertEquals(1.0, hp(blocked, PARTNER), 1e-9)
        assertTrue(ordinary.state.pokemon.single { it.battlePokemonId == PARTNER }.hpFraction < 1.0)
    }

    @Test
    fun `a protection breaking priority move bypasses quick guard`() {
        val outcome = project(
            guard("quickguard"),
            attack(
                "feint",
                priority = 2,
                pattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
                breaksProtection = true,
            ),
        )

        assertTrue(hp(outcome, PARTNER) < 1.0)
    }

    @Test
    fun `quick guard uses prankster modified priority`() {
        val outcome = project(
            guard("quickguard"),
            statusAttack("scaryface"),
            state(attackerAbility = "prankster"),
        )

        val partner = outcome.state.pokemon.single { it.battlePokemonId == PARTNER }
        assertEquals(0, partner.statStages["speed"] ?: 0)
    }

    @Test
    fun `crafty shield blocks targeted status but not damage`() {
        val blocked = project(guard("craftyshield"), statusAttack("scaryface"))
        val damage = project(
            guard("craftyshield"),
            attack("rockthrow", priority = 0, pattern = BattleMoveTargetPattern.SELECTED_OPPONENT),
        )

        assertEquals(0, blocked.state.pokemon.single { it.battlePokemonId == PARTNER }.statStages["speed"] ?: 0)
        assertTrue(hp(damage, PARTNER) < 1.0)
    }

    @Test
    fun `mat block creates its side guard and blocks damage but not status`() {
        val blocked = project(
            guard("matblock"),
            attack("rockthrow", priority = 0, pattern = BattleMoveTargetPattern.SELECTED_OPPONENT),
        )
        val status = project(guard("matblock"), statusAttack("scaryface", targetSlot = 0))

        assertEquals(1.0, hp(blocked, PARTNER), 1e-9)
        assertEquals(-2, status.state.pokemon.single { it.battlePokemonId == GUARD_USER }.statStages["speed"])
    }

    private fun project(
        guard: BattleActionCandidate,
        attack: BattleActionCandidate,
        initial: BattleStateView = state(),
    ): PublicTurnProjection {
        val ally = joint("ally", guard, wait("ally_wait", 1, BattleSide.ALLY))
        val opponent = joint("opponent", attack, wait("opponent_wait", 1, BattleSide.OPPONENT))
        return PublicSingleTurnProjector.project(
            initial,
            ally,
            opponent,
            BattleDecisionContext(UUID.randomUUID(), initial, listOf(ally), Long.MAX_VALUE),
        ).single()
    }

    private fun guard(id: String) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = 0,
        moveSlot = 0,
        moveId = id,
        targets = emptyList(),
        moveDetails = BattleMoveCandidateView(
            typeId = "normal",
            damageCategory = BattleMoveDamageCategory.STATUS,
            power = 0.0,
            accuracy = 100.0,
            priority = if (id == "matblock") 0 else 3,
            currentPp = 10,
            targetPattern = BattleMoveTargetPattern.SIDE,
            effects = BattleMoveEffectsView(
                BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                buildList {
                    if (id == "matblock") {
                        add(BattleMoveEffectView(BattleMoveEffectKind.PROTECT_USER, BattleMoveEffectTarget.USER))
                    }
                    add(
                        BattleMoveEffectView(
                            BattleMoveEffectKind.SIDE_CONDITION,
                            BattleMoveEffectTarget.USER_SIDE,
                            valueId = id,
                        ),
                    )
                },
                scriptedBehavior = true,
                mechanicFlags = if (id == "matblock") {
                    setOf("stalling_move", "stall_counter_advance")
                } else {
                    setOf("stall_counter_advance")
                },
            ),
        ),
    )

    private fun statusAttack(id: String, targetSlot: Int = 1) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = 0,
        moveSlot = 0,
        moveId = id,
        targets = listOf(BattleTargetSlot(BattleSide.ALLY, targetSlot)),
        moveDetails = BattleMoveCandidateView(
            typeId = "normal",
            damageCategory = BattleMoveDamageCategory.STATUS,
            power = 0.0,
            accuracy = 100.0,
            priority = 0,
            currentPp = 10,
            targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
            effects = BattleMoveEffectsView(
                BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                listOf(
                    BattleMoveEffectView(
                        BattleMoveEffectKind.STAT_STAGE,
                        BattleMoveEffectTarget.SELECTED_TARGET,
                        probability = 1.0,
                        statStages = mapOf("speed" to -2),
                    ),
                ),
                scriptedBehavior = false,
                mechanicFlags = setOf("protect"),
            ),
        ),
    )

    private fun attack(
        id: String,
        priority: Int,
        pattern: BattleMoveTargetPattern,
        breaksProtection: Boolean = false,
    ) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = 0,
        moveSlot = 0,
        moveId = id,
        targets = if (pattern == BattleMoveTargetPattern.SELECTED_OPPONENT) {
            listOf(BattleTargetSlot(BattleSide.ALLY, 1))
        } else {
            emptyList()
        },
        moveDetails = BattleMoveCandidateView(
            typeId = "rock",
            damageCategory = BattleMoveDamageCategory.PHYSICAL,
            power = 75.0,
            accuracy = 100.0,
            priority = priority,
            currentPp = 10,
            targetPattern = pattern,
            effects = BattleMoveEffectsView(
                BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                if (breaksProtection) {
                    listOf(
                        BattleMoveEffectView(
                            BattleMoveEffectKind.BREAKS_PROTECTION,
                            BattleMoveEffectTarget.SELECTED_TARGET,
                        ),
                    )
                } else {
                    emptyList()
                },
                scriptedBehavior = false,
                mechanicFlags = setOf("protect"),
            ),
        ),
    )

    private fun wait(id: String, slot: Int, side: BattleSide) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = slot,
        moveSlot = 0,
        moveId = id,
        targets = listOf(BattleTargetSlot(side, slot)),
        moveDetails = BattleMoveCandidateView(
            typeId = "normal",
            damageCategory = BattleMoveDamageCategory.STATUS,
            power = 0.0,
            accuracy = 100.0,
            priority = 0,
            currentPp = 10,
            targetPattern = BattleMoveTargetPattern.SELF,
            effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, emptyList(), false),
        ),
    )

    private fun joint(id: String, vararg actions: BattleActionCandidate) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.COMPOSITE,
        componentActionIds = actions.map { it.actionId },
        componentActions = actions.toList(),
    )

    private fun state(attackerAbility: String? = null) = BattleStateView(
        battleId = BATTLE,
        format = BattleFormat.DOUBLE,
        turn = 1,
        pokemon = listOf(
            pokemon(GUARD_USER, BattleSide.ALLY, 0, 100),
            pokemon(PARTNER, BattleSide.ALLY, 1, 90),
            pokemon(ATTACKER, BattleSide.OPPONENT, 0, 80, attackerAbility),
            pokemon(OTHER_FOE, BattleSide.OPPONENT, 1, 70),
        ),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = BattleSide.entries.associateWith { 2 },
        observedEvents = emptyList(),
        inferences = emptyList(),
    )

    private fun pokemon(
        id: UUID,
        side: BattleSide,
        slot: Int,
        speed: Int,
        ability: String? = null,
    ) = BattlePokemonStateView(
        battlePokemonId = id,
        side = side,
        activeSlot = slot,
        speciesId = "showdown:probe",
        formId = null,
        level = 50,
        hpFraction = 1.0,
        statusId = null,
        statStages = emptyMap(),
        knownMoveIds = emptySet(),
        knownAbilityId = ability,
        knownHeldItemId = null,
        fainted = false,
        knownTypeIds = setOf("normal"),
        combatStats = if (side == BattleSide.ALLY) {
            BattleCombatStatRangesView.exact(160, 100, 100, 100, 100, speed)
        } else {
            BattleCombatStatRangesView(
                BattleIntegerRange(160, 160),
                BattleIntegerRange(100, 100),
                BattleIntegerRange(100, 100),
                BattleIntegerRange(100, 100),
                BattleIntegerRange(100, 100),
                BattleIntegerRange(speed, speed),
                BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
            )
        },
    )

    private fun hp(outcome: PublicTurnProjection, id: UUID) =
        outcome.state.pokemon.single { it.battlePokemonId == id }.hpFraction

    private companion object {
        val BATTLE: UUID = UUID.fromString("00000000-0000-0000-0000-00000000b500")
        val GUARD_USER: UUID = UUID.fromString("00000000-0000-0000-0000-00000000b501")
        val PARTNER: UUID = UUID.fromString("00000000-0000-0000-0000-00000000b502")
        val ATTACKER: UUID = UUID.fromString("00000000-0000-0000-0000-00000000b503")
        val OTHER_FOE: UUID = UUID.fromString("00000000-0000-0000-0000-00000000b504")
    }
}
