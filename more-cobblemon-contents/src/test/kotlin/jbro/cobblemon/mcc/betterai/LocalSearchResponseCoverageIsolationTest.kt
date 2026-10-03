package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.policy.LocalBattleActionOutcome
import jbro.cobblemon.mcc.betterai.policy.LocalBattleActionRank
import jbro.cobblemon.mcc.betterai.search.LocalRecursiveLookaheadEvaluator
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalSearchResponseCoverageIsolationTest {
    @Test
    fun `reweighing cached root replies preserves each candidate's own future coverage`() {
        val kill = move("kill", 0, 1_000.0)
        val poke = move("poke", 0, 1.0)
        val state = BattleStateView(UUID(0, 99), BattleFormat.SINGLE, 1, listOf(
            mon(1, BattleSide.ALLY, 0, speed = 200),
            mon(2, BattleSide.OPPONENT, 0, trapped = true),
            mon(3, BattleSide.OPPONENT, null)), BattleFieldStateView.empty(),
            mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 2), emptyList(), emptyList())
        val catalog = BattlePublicActionCatalogView(listOf(
            entry(state.pokemon[0], listOf(kill, poke)),
            entry(state.pokemon[1], listOf(splash("splash", 0), splash("celebrate", 0, "celebrate")))))
        val source = BattleDecisionContext(UUID(0, 100), state, listOf(kill, poke), Long.MAX_VALUE,
            publicActionCatalog = catalog)
        val boss = BattleTrainerProfile.boss()
        val profile = boss.copy(difficulty = boss.difficulty.copy(lookaheadPlies = 2))
        fun evaluate(mix: Double) = LocalRecursiveLookaheadEvaluator.evaluate(listOf(rank(kill), rank(poke)), source,
            profile, LocalDecisionTuning.CURRENT.copy(simultaneousResponseWeight = mix), clockMillis = { 0L },
            moveUsageForFormat = { null })
        val reference = evaluate(0.0)
        val mixed = evaluate(0.5)
        for (result in listOf(reference, mixed)) {
            assertEquals(2, result.depthCompleted)
            assertFalse(result.truncated)
        }
        val known = reference.responseCoverageByAction.getValue("poke")
        val replacement = reference.responseCoverageByAction.getValue("kill")
        assertTrue(known.future > replacement.future, "Only the knockout reaches the unseen replacement's moves")
        assertEquals(reference.responseCoverageByAction, mixed.responseCoverageByAction)
    }

    @Test
    fun `two unknown opposing slots reserve twice the uncertainty of one unknown slot`() {
        fun evaluate(knownFirst: Boolean): Double {
            val pokemon = listOf(mon(1, BattleSide.ALLY, 0), mon(2, BattleSide.ALLY, 1),
                mon(3, BattleSide.OPPONENT, 0), mon(4, BattleSide.OPPONENT, 1))
            val state = BattleStateView(UUID(0, 99), BattleFormat.DOUBLE, 1, pokemon, BattleFieldStateView.empty(),
                BattleSide.entries.associateWith { 2 }, emptyList(), emptyList())
            val parts = listOf(splash("own0", 0), splash("own1", 1))
            val action = BattleActionCandidate("own", BattleActionKind.COMPOSITE,
                componentActionIds = parts.map { it.actionId }, componentActions = parts)
            val entries = listOf(entry(pokemon[0], listOf(parts[0])), entry(pokemon[1], listOf(parts[1]))) +
                if (knownFirst) listOf(entry(pokemon[2], listOf(splash("known", 0)))) else emptyList()
            val source = BattleDecisionContext(UUID(0, 100), state, listOf(action), Long.MAX_VALUE,
                publicActionCatalog = BattlePublicActionCatalogView(entries))
            val boss = BattleTrainerProfile.boss()
            val result = LocalRecursiveLookaheadEvaluator.evaluate(listOf(rank(action)), source,
                boss.copy(difficulty = boss.difficulty.copy(lookaheadPlies = 1)), LocalDecisionTuning.CURRENT.copy(
                    lookaheadLinearCoverage = true, lookaheadCoverageFloor = 1.0, lookaheadMoveHypotheses = false),
                clockMillis = { 0L }, moveUsageForFormat = { null })
            assertEquals(1, result.depthCompleted)
            assertFalse(result.truncated)
            return result.ranked.single().lookaheadUtility
        }
        assertEquals(20.0, evaluate(true) - evaluate(false), 1e-9)
    }

    private fun entry(pokemon: BattlePokemonStateView, moves: List<BattleActionCandidate>) = BattlePokemonActionCatalogView(
        pokemon.battlePokemonId, moves.map { BattlePublicMoveOptionView(requireNotNull(it.moveId), requireNotNull(it.moveDetails),
            if (pokemon.side == BattleSide.ALLY) BattlePublicMoveKnowledge.EXACT_OWN else BattlePublicMoveKnowledge.PUBLICLY_REVEALED) },
        moveSetComplete = true)

    private fun mon(n: Long, side: BattleSide, slot: Int?, speed: Int = 100, trapped: Boolean = false) =
        BattlePokemonStateView(UUID(0, n), side, slot, "test", null, 50, 1.0, null, emptyMap(), emptySet(), null, "", false,
            setOf("normal"), BattleCombatStatRangesView(BattleIntegerRange(200, 200), BattleIntegerRange(100, 100),
                BattleIntegerRange(100, 100), BattleIntegerRange(100, 100), BattleIntegerRange(100, 100), BattleIntegerRange(speed, speed),
                if (side == BattleSide.ALLY) BattleCombatStatKnowledge.EXACT_OWN else BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE),
            actionConstraints = BattlePokemonActionConstraintView(false, null, trapped, false))

    private fun move(id: String, slot: Int, power: Double) = BattleActionCandidate(id, BattleActionKind.USE_MOVE,
        actorSlot = slot, moveSlot = 0, moveId = id, targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
        moveDetails = BattleMoveCandidateView("normal", BattleMoveDamageCategory.PHYSICAL, power, 100.0, 0, 10))

    private fun splash(id: String, slot: Int, moveId: String = "splash") = BattleActionCandidate(id, BattleActionKind.USE_MOVE,
        actorSlot = slot, moveSlot = 0, moveId = moveId, moveDetails = BattleMoveCandidateView("normal",
            BattleMoveDamageCategory.STATUS, 0.0, 100.0, 0, 10, targetPattern = BattleMoveTargetPattern.SELF))

    private fun rank(candidate: BattleActionCandidate) = LocalBattleActionRank(LocalBattleActionOutcome(candidate,
        0.0, 0.0, 0, 1, false, false, null, null, null, null), decisionTier = 3, comparisonValue = 30_000.0)
}
