package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalBoardMaterial
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalTeamMatchupCoverage
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalLookaheadStateEvaluator
import jbro.cobblemon.morebattlecontent.betterai.state.LocalSwitchStateProjector
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Controlled tuning cases, not native battle outcomes or proof of an optimal team-role formula. */
class LocalTeamRoleEvaluationTest {
    @Test
    fun `missing opponent attack evidence does not spend calculations on virtual entries`() {
        val state = board(answerSurvives = true)
        val cache = LocalProjectedActionCalculationCache()
        var budgetChecks = 0
        assertEquals(0.0, LocalTeamMatchupCoverage.evaluate(state, context(state, includeFoeMoves = false),
            cache, { budgetChecks++; true }, LocalDecisionTuning.CURRENT))
        assertEquals(0, cache.calculationsPerformed)
        assertEquals(0, budgetChecks)
    }

    @Test
    fun `coverage is bounded mirrors a plain singles board and ignores doubles`() {
        val ordinary = board(answerSurvives = true)
        val mirrored = board(answerSurvives = true, mirror = true)
        assertTrue(coverage(ordinary) in -1.0..1.0)
        assertEquals(-coverage(ordinary), coverage(mirrored), 1e-9)
        assertEquals(0.0, coverage(board(answerSurvives = true, format = BattleFormat.DOUBLE)))
        for (invalid in listOf(-0.1, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { LocalDecisionTuning.CURRENT.copy(leafTeamCoverageWeight = invalid) }
        }
    }

    @Test
    fun `a reserve that faints on public entry hazards is not an available answer`() {
        val clear = board(answerSurvives = true, answerHp = 0.125)
        val spikes = BattleFieldStateView(null, null, emptyList(), emptyList(),
            BattleSide.entries.associateWith { side -> if (side == BattleSide.ALLY)
                listOf(BattleTimedEffectView("spikes", null, 3)) else emptyList() })
        val blocked = board(answerSurvives = true, answerHp = 0.125, field = spikes)
        assertTrue(coverage(clear) > coverage(blocked))
        assertEquals(coverage(board(answerSurvives = false)), coverage(blocked), 1e-9)
        assertEquals(0.125, blocked.pokemon.single { it.battlePokemonId == ANSWER }.hpFraction)
        assertFalse(blocked.pokemon.single { it.battlePokemonId == ANSWER }.fainted)
    }

    private fun coverage(state: BattleStateView) = LocalTeamMatchupCoverage.evaluate(state, context(state),
        LocalProjectedActionCalculationCache(), { true }, LocalDecisionTuning.CURRENT)

    @Test
    fun `budget exhaustion never returns a partially evaluated team advantage`() {
        val state = board(answerSurvives = true)
        for (limit in 0..10) {
            var calls = 0
            val value = LocalTeamMatchupCoverage.evaluate(state, context(state),
                LocalProjectedActionCalculationCache(), { calls++ < limit }, LocalDecisionTuning.CURRENT)
            if (calls > limit) assertEquals(0.0, value, "budget $limit was exhausted")
        }
    }

    @Test
    fun `team coverage respects current PP missing evidence and reusable calculation cache`() {
        val state = board(answerSurvives = true)
        val cache = LocalProjectedActionCalculationCache()
        val source = context(state)
        val covered = LocalTeamMatchupCoverage.evaluate(state, source, cache, { true }, LocalDecisionTuning.CURRENT)
        assertEquals(0.5, covered, 1e-9)
        val calculations = cache.calculationsPerformed
        assertEquals(covered, LocalTeamMatchupCoverage.evaluate(state, source, cache, { true }, LocalDecisionTuning.CURRENT))
        assertEquals(calculations, cache.calculationsPerformed)
        val emptyPp = LocalTeamMatchupCoverage.evaluate(state, context(state, answerPp = 0), cache,
            { true }, LocalDecisionTuning.CURRENT)
        assertTrue(covered > emptyPp)
        assertEquals(-0.5, emptyPp, 1e-9)
        assertEquals(0.0, LocalTeamMatchupCoverage.evaluate(state, context(state, includeFoeMoves = false), cache,
            { true }, LocalDecisionTuning.CURRENT))
        assertEquals(8, source.publicActionCatalog.forPokemon(ANSWER).single().details.currentPp)
        assertNull(state.pokemon.single { it.battlePokemonId == ANSWER }.activeSlot)
    }

    @Test
    fun `experimental team coverage values the sole answer without changing base material`() {
        val answerAlive = board(answerSurvives = true)
        val answerLost = board(answerSurvives = false)
        val experimental = LocalDecisionTuning.CURRENT.copy(leafTeamCoverageWeight = 0.25)
        assertEquals(0.0, LocalDecisionTuning.CURRENT.leafTeamCoverageWeight)
        assertTrue(LocalLookaheadStateEvaluator.evaluate(answerAlive, context(answerAlive), tuning = experimental) >
            LocalLookaheadStateEvaluator.evaluate(answerLost, context(answerLost), tuning = experimental))
    }

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

    private fun context(state: BattleStateView, answerPp: Int = 8, includeFoeMoves: Boolean = true) = BattleDecisionContext(UUID(0, 919), state,
        listOf(BattleActionCandidate("wait", BattleActionKind.WAIT)), Long.MAX_VALUE,
        publicActionCatalog = BattlePublicActionCatalogView(state.pokemon.filter {
            includeFoeMoves || it.side != BattleSide.OPPONENT
        }.map { pokemon ->
            val type = when (pokemon.battlePokemonId) { ANSWER -> "dark"; FOE -> "psychic"; else -> "normal" }
            BattlePokemonActionCatalogView(pokemon.battlePokemonId, listOf(BattlePublicMoveOptionView(
                "probe_$type", BattleMoveCandidateView(typeId = type,
                    damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 200.0,
                    accuracy = 100.0, priority = 0, currentPp = if (pokemon.battlePokemonId == ANSWER) answerPp else 8),
                if (pokemon.side == BattleSide.ALLY) BattlePublicMoveKnowledge.EXACT_OWN
                else BattlePublicMoveKnowledge.PUBLICLY_REVEALED)), moveSetComplete = true)
        }))

    private fun board(answerSurvives: Boolean, answerHp: Double = 1.0,
        field: BattleFieldStateView = BattleFieldStateView.empty(), format: BattleFormat = BattleFormat.SINGLE,
        mirror: Boolean = false): BattleStateView {
        val ownSide = if (mirror) BattleSide.OPPONENT else BattleSide.ALLY
        val foeSide = if (mirror) BattleSide.ALLY else BattleSide.OPPONENT
        return BattleStateView(UUID(0, 918), format, 1,
            listOf(pokemon(ACTIVE, ownSide, 0, "fighting"),
                pokemon(ANSWER, ownSide, null, "dark", alive = answerSurvives, hp = answerHp),
                pokemon(REDUNDANT, ownSide, null, "fighting", alive = !answerSurvives),
                pokemon(FOE, foeSide, 0, "ghost")), field,
            mapOf(ownSide to 2, foeSide to 1), emptyList(), emptyList())
    }

    private fun pokemon(id: UUID, side: BattleSide, slot: Int?, type: String, alive: Boolean = true, hp: Double = 1.0) =
        BattlePokemonStateView(id, side, slot, "fixture:role", null, 50, if (alive) hp else 0.0,
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
