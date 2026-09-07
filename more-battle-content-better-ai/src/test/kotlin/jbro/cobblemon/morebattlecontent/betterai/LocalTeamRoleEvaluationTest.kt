package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalBoardMaterial
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalLookaheadStateEvaluator
import jbro.cobblemon.morebattlecontent.betterai.state.LocalSwitchStateProjector
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Controlled tuning cases, not native battle outcomes or proof of an optimal team-role formula. */
class LocalTeamRoleEvaluationTest {
    @Test
    fun `current leaf cannot distinguish losing the sole public answer from losing a redundant teammate`() {
        val answerAlive = board(answerSurvives = true)
        val answerLost = board(answerSurvives = false)
        // Both leaves have identical active Pokemon, HP totals and surviving team sizes.
        assertEquals(LocalBoardMaterial.evaluate(answerAlive), LocalBoardMaterial.evaluate(answerLost))
        assertEquals(LocalLookaheadStateEvaluator.evaluate(answerAlive, context(answerAlive)),
            LocalLookaheadStateEvaluator.evaluate(answerLost, context(answerLost)), 1e-9)

        // Establish why the bench identities matter using the existing public damage calculator:
        // only the dark teammate damages the ghost and is immune to its sole revealed attack.
        val answerEntered = enter(answerAlive, ANSWER)
        val redundantEntered = enter(answerLost, REDUNDANT)
        assertTrue(pressure(answerEntered, BattleSide.ALLY) > 1.0)
        assertEquals(0.0, pressure(answerEntered, BattleSide.OPPONENT), 1e-9)
        assertEquals(0.0, pressure(redundantEntered, BattleSide.ALLY), 1e-9)
        assertTrue(pressure(redundantEntered, BattleSide.OPPONENT) > 1.0)
        assertTrue(LocalLookaheadStateEvaluator.evaluate(answerEntered, context(answerEntered)) >
            LocalLookaheadStateEvaluator.evaluate(redundantEntered, context(redundantEntered)))
        // Projection is hypothetical: the original leaves still have their original active slots.
        assertNull(answerAlive.pokemon.single { it.battlePokemonId == ANSWER }.activeSlot)
        assertNull(answerLost.pokemon.single { it.battlePokemonId == REDUNDANT }.activeSlot)
    }

    private fun pressure(state: BattleStateView, side: BattleSide) =
        LocalLookaheadStateEvaluator.attackPressure(state, side, context(state))

    private fun enter(state: BattleStateView, pokemon: UUID) = LocalSwitchStateProjector.project(
        state, BattleSide.ALLY, BattleActionCandidate("enter:$pokemon", BattleActionKind.SWITCH,
            actorSlot = 0, switchPokemonId = pokemon))

    private fun context(state: BattleStateView) = BattleDecisionContext(UUID(0, 919), state,
        listOf(BattleActionCandidate("wait", BattleActionKind.WAIT)), Long.MAX_VALUE,
        publicActionCatalog = BattlePublicActionCatalogView(state.pokemon.map { pokemon ->
            val type = when (pokemon.battlePokemonId) { ANSWER -> "dark"; FOE -> "psychic"; else -> "normal" }
            BattlePokemonActionCatalogView(pokemon.battlePokemonId, listOf(BattlePublicMoveOptionView(
                "probe_$type", BattleMoveCandidateView(typeId = type,
                    damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 200.0,
                    accuracy = 100.0, priority = 0, currentPp = 8),
                if (pokemon.side == BattleSide.ALLY) BattlePublicMoveKnowledge.EXACT_OWN
                else BattlePublicMoveKnowledge.PUBLICLY_REVEALED)), moveSetComplete = true)
        }))

    private fun board(answerSurvives: Boolean) = BattleStateView(UUID(0, 918), BattleFormat.SINGLE, 1,
        listOf(pokemon(ACTIVE, BattleSide.ALLY, 0, "fighting"),
            pokemon(ANSWER, BattleSide.ALLY, null, "dark", alive = answerSurvives),
            pokemon(REDUNDANT, BattleSide.ALLY, null, "fighting", alive = !answerSurvives),
            pokemon(FOE, BattleSide.OPPONENT, 0, "ghost")), BattleFieldStateView.empty(),
        mapOf(BattleSide.ALLY to 2, BattleSide.OPPONENT to 1), emptyList(), emptyList())

    private fun pokemon(id: UUID, side: BattleSide, slot: Int?, type: String, alive: Boolean = true) =
        BattlePokemonStateView(id, side, slot, "fixture:role", null, 50, if (alive) 1.0 else 0.0,
            null, emptyMap(), emptySet(), null, null, !alive, knownTypeIds = setOf(type),
            combatStats = BattleCombatStatRangesView(maxHp = BattleIntegerRange(100, 100),
                attack = BattleIntegerRange(200, 200), defence = BattleIntegerRange(100, 100),
                specialAttack = BattleIntegerRange(200, 200), specialDefence = BattleIntegerRange(100, 100),
                speed = BattleIntegerRange(100, 100), knowledge = if (side == BattleSide.ALLY)
                    BattleCombatStatKnowledge.EXACT_OWN else BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE))

    private companion object {
        val ACTIVE = UUID(0, 1)
        val ANSWER = UUID(0, 2)
        val REDUNDANT = UUID(0, 3)
        val FOE = UUID(0, 4)
    }
}
