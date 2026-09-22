package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.outcome.PublicSingleTurnProjector
import jbro.cobblemon.morebattlecontent.betterai.state.LocalDirectDamageRecipient
import jbro.cobblemon.morebattlecontent.betterai.state.PublicTurnProjection
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalAssuranceProjectionTest {
    @Test
    fun `assurance doubles after its target was damaged earlier in the turn`() {
        val assurance = project(
            state(),
            allyPair(attack("opening_hit", 0, 0, 40.0), assurance(1, 0)),
            opponentPair(),
        )
        val ordinary = project(
            state(),
            allyPair(attack("opening_hit", 0, 0, 40.0), darkAttack(1, 0)),
            opponentPair(),
        )

        assertTrue(
            damage(assurance, ASSURANCE_USER, TARGET_ZERO) >
                damage(ordinary, ASSURANCE_USER, TARGET_ZERO) * 1.8,
        )
    }

    @Test
    fun `damage to the other opposing pokemon does not boost assurance`() {
        val assurance = project(
            state(),
            allyPair(attack("opening_hit", 0, 1, 40.0), assurance(1, 0)),
            opponentPair(),
        )
        val ordinary = project(
            state(),
            allyPair(attack("opening_hit", 0, 1, 40.0), darkAttack(1, 0)),
            opponentPair(),
        )

        val assuranceDamage = damage(assurance, ASSURANCE_USER, TARGET_ZERO)
        val ordinaryDamage = damage(ordinary, ASSURANCE_USER, TARGET_ZERO)
        assertTrue(assuranceDamage > ordinaryDamage * 0.9)
        assertTrue(assuranceDamage < ordinaryDamage * 1.1)
    }

    @Test
    fun `follow me checks the actual target instead of the damaged declared target`() {
        val assurance = project(
            state(),
            allyPair(attack("opening_hit", 0, 0, 40.0, priority = 3), assurance(1, 0)),
            redirectingOpponentPair(),
        )
        val ordinary = project(
            state(),
            allyPair(attack("opening_hit", 0, 0, 40.0, priority = 3), darkAttack(1, 0)),
            redirectingOpponentPair(),
        )

        val assuranceDamage = damage(assurance, ASSURANCE_USER, TARGET_ONE)
        val ordinaryDamage = damage(ordinary, ASSURANCE_USER, TARGET_ONE)
        assertTrue(assuranceDamage > ordinaryDamage * 0.9)
        assertTrue(assuranceDamage < ordinaryDamage * 1.1)
    }

    @Test
    fun `entry hazard damage boosts assurance against the pokemon that switched in`() {
        val assurance = project(
            state(withHazardSwitch = true),
            allyPair(wait("ally_wait", 0, BattleSide.ALLY), assurance(1, 0)),
            opponentSwitchPair(),
        )
        val ordinary = project(
            state(withHazardSwitch = true),
            allyPair(wait("ally_wait", 0, BattleSide.ALLY), darkAttack(1, 0)),
            opponentSwitchPair(),
        )

        assertTrue(
            damage(assurance, ASSURANCE_USER, INCOMING_TARGET) >
                damage(ordinary, ASSURANCE_USER, INCOMING_TARGET) * 1.8,
        )
    }

    private fun project(
        initial: BattleStateView,
        ally: BattleActionCandidate,
        opponent: BattleActionCandidate,
    ): PublicTurnProjection = PublicSingleTurnProjector.project(
        initial,
        ally,
        opponent,
        BattleDecisionContext(UUID.randomUUID(), initial, listOf(ally), Long.MAX_VALUE),
    ).single()

    private fun allyPair(first: BattleActionCandidate, second: BattleActionCandidate) = joint(
        "ally_pair",
        first,
        second,
    )

    private fun opponentPair() = joint(
        "opponent_pair",
        wait("target_zero_wait", 0),
        wait("target_one_wait", 1),
    )

    private fun redirectingOpponentPair() = joint(
        "redirecting_opponent_pair",
        wait("target_zero_wait", 0),
        followMe(1),
    )

    private fun opponentSwitchPair() = joint(
        "opponent_switch_pair",
        switch("target_zero_switch", 0, INCOMING_TARGET),
        wait("target_one_wait", 1),
    )

    private fun assurance(actorSlot: Int, targetSlot: Int) = attack(
        "assurance",
        actorSlot,
        targetSlot,
        60.0,
        dynamicPower = true,
    )

    private fun darkAttack(actorSlot: Int, targetSlot: Int) = attack(
        "dark_attack",
        actorSlot,
        targetSlot,
        60.0,
    )

    private fun attack(
        id: String,
        actorSlot: Int,
        targetSlot: Int,
        power: Double,
        dynamicPower: Boolean = false,
        priority: Int = 0,
    ) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = actorSlot,
        moveSlot = 0,
        moveId = id,
        targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, targetSlot)),
        moveDetails = BattleMoveCandidateView(
            typeId = if (id == "assurance" || id == "dark_attack") "dark" else "normal",
            damageCategory = BattleMoveDamageCategory.PHYSICAL,
            power = power,
            accuracy = 100.0,
            priority = priority,
            currentPp = 10,
            targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
            effects = BattleMoveEffectsView(
                BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                emptyList(),
                scriptedBehavior = dynamicPower,
                mechanicFlags = if (dynamicPower) {
                    setOf("dynamic_base_power", "protect")
                } else {
                    setOf("protect")
                },
            ),
        ),
    )

    private fun followMe(actorSlot: Int) = BattleActionCandidate(
        actionId = "follow_me",
        kind = BattleActionKind.USE_MOVE,
        actorSlot = actorSlot,
        moveSlot = 0,
        moveId = "followme",
        targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, actorSlot)),
        moveDetails = BattleMoveCandidateView(
            typeId = "normal",
            damageCategory = BattleMoveDamageCategory.STATUS,
            power = 0.0,
            accuracy = 100.0,
            priority = 2,
            currentPp = 20,
            targetPattern = BattleMoveTargetPattern.SELF,
            effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, emptyList(), true),
        ),
    )

    private fun wait(id: String, actorSlot: Int, side: BattleSide = BattleSide.OPPONENT) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = actorSlot,
        moveSlot = 0,
        moveId = id,
        targets = listOf(BattleTargetSlot(side, actorSlot)),
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

    private fun switch(id: String, actorSlot: Int, incoming: UUID) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.SWITCH,
        actorSlot = actorSlot,
        switchPokemonId = incoming,
    )

    private fun joint(id: String, vararg actions: BattleActionCandidate) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.COMPOSITE,
        componentActionIds = actions.map { it.actionId },
        componentActions = actions.toList(),
    )

    private fun state(withHazardSwitch: Boolean = false) = BattleStateView(
        battleId = BATTLE,
        format = BattleFormat.DOUBLE,
        turn = 1,
        pokemon = listOf(
            pokemon(OPENER, BattleSide.ALLY, 0, 120),
            pokemon(ASSURANCE_USER, BattleSide.ALLY, 1, 60),
            pokemon(TARGET_ZERO, BattleSide.OPPONENT, 0, 80),
            pokemon(TARGET_ONE, BattleSide.OPPONENT, 1, 20),
        ) + listOfNotNull(
            pokemon(INCOMING_TARGET, BattleSide.OPPONENT, null, 40).takeIf { withHazardSwitch },
        ),
        field = if (withHazardSwitch) {
            BattleFieldStateView(
                weather = null,
                terrain = null,
                roomEffects = emptyList(),
                globalEffects = emptyList(),
                sideConditions = mapOf(
                    BattleSide.ALLY to emptyList(),
                    BattleSide.OPPONENT to listOf(BattleTimedEffectView("stealthrock", null, stacks = 1)),
                ),
            )
        } else {
            BattleFieldStateView.empty()
        },
        remainingPokemonBySide = mapOf(
            BattleSide.ALLY to 2,
            BattleSide.OPPONENT to if (withHazardSwitch) 3 else 2,
        ),
        observedEvents = emptyList(),
        inferences = emptyList(),
    )

    private fun pokemon(id: UUID, side: BattleSide, slot: Int?, speed: Int) = BattlePokemonStateView(
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
        knownAbilityId = null,
        knownHeldItemId = null,
        fainted = false,
        knownTypeIds = setOf("normal"),
        combatStats = if (side == BattleSide.ALLY) {
            BattleCombatStatRangesView.exact(400, 100, 100, 100, 100, speed)
        } else {
            BattleCombatStatRangesView(
                BattleIntegerRange(400, 400),
                BattleIntegerRange(100, 100),
                BattleIntegerRange(100, 100),
                BattleIntegerRange(100, 100),
                BattleIntegerRange(100, 100),
                BattleIntegerRange(speed, speed),
                BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
            )
        },
    )

    private fun damage(outcome: PublicTurnProjection, actor: UUID, target: UUID): Double =
        outcome.directDamage.amounts[LocalDirectDamageRecipient(actor, target)] ?: 0.0

    private companion object {
        val BATTLE: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c700")
        val OPENER: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c701")
        val ASSURANCE_USER: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c702")
        val TARGET_ZERO: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c703")
        val TARGET_ONE: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c704")
        val INCOMING_TARGET: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c705")
    }
}
