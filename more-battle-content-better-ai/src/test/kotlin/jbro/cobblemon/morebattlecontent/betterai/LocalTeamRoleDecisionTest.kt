package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.brain.LocalTacticalBrain
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalBoardMaterial
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.morebattlecontent.betterai.outcome.PublicSingleTurnProjector
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** A synthetic tuning situation with explicit public bench knowledge, not a native win-rate test. */
class LocalTeamRoleDecisionTest {
    @Test
    fun `one turn material scoring cannot distinguish equal sacrifices by future role`() {
        val source = fixture()
        val calculated = PublicBattleTacticalCalculator.calculate(source)
        val enemyAttack = PublicFutureActionFactory.actions(source.state, BattleSide.OPPONENT,
            source.publicActionCatalog).single { it.kind == BattleActionKind.USE_MOVE }
        val losses = calculated.candidates.map { candidate ->
            val outcomes = PublicSingleTurnProjector.project(source.state, candidate, enemyAttack, calculated)
            assertTrue(outcomes.isNotEmpty())
            assertTrue(outcomes.all { outcome -> outcome.state.pokemon.single {
                it.battlePokemonId == candidate.switchPokemonId
            }.fainted }, "either reserve is lost if the revealed fighting attack is used")
            outcomes.map { LocalBoardMaterial.evaluate(it.state) }.toSet().single()
        }
        assertEquals(losses[0], losses[1])

        val current = trace(source, LocalDecisionTuning.CURRENT)
        val team = trace(source, LocalDecisionTuning.CURRENT.copy(id = "current_team_coverage", leafTeamCoverageWeight = 0.25))
        // Fatal exploratory switches are excluded: the tied first candidate is the sole fallback.
        assertSoleSelection(current, "sacrifice_answer")
        assertSoleSelection(team, "sacrifice_answer")
        assertEquals(current.ranked.map { it.comparisonValue }, team.ranked.map { it.comparisonValue })
        assertEquals(current.ranked.associate { it.outcome.candidate.actionId to it.outcome.tacticalUtility },
            team.ranked.associate { it.outcome.candidate.actionId to it.outcome.tacticalUtility })
    }

    @Test
    fun `two turn search exposes team role contribution after forced replacement`() {
        val current = trace(fixture(), LocalDecisionTuning.CURRENT, depth = 2)
        val team = trace(fixture(), LocalDecisionTuning.CURRENT.copy(
            id = "current_team_coverage", leafTeamCoverageWeight = 0.25), depth = 2)
        fun scores(trace: LocalDecisionTraceSelector.Trace) = trace.ranked.associate {
            it.outcome.candidate.actionId to it.comparisonValue
        }
        val before = scores(current)
        val after = scores(team)
        println("TEAM_ROLE_DEPTH2 before=$before after=$after")
        assertTrue(after.getValue("sacrifice_redundant") - after.getValue("sacrifice_answer") >
            before.getValue("sacrifice_redundant") - before.getValue("sacrifice_answer"))
        assertSoleSelection(current, "sacrifice_answer")
        assertSoleSelection(team, "sacrifice_redundant")
        assertEquals(current.ranked.associate { it.outcome.candidate.actionId to it.outcome.tacticalUtility },
            team.ranked.associate { it.outcome.candidate.actionId to it.outcome.tacticalUtility })
    }

    @Test
    fun `zero foresight does not receive the future team coverage preference`() {
        val current = trace(fixture(), LocalDecisionTuning.CURRENT, depth = 2, foresightWeight = 0.0)
        val team = trace(fixture(), LocalDecisionTuning.CURRENT.copy(
            leafTeamCoverageWeight = 0.25), depth = 2, foresightWeight = 0.0)
        assertEquals(current.ranked.map { it.outcome.candidate.actionId },
            team.ranked.map { it.outcome.candidate.actionId })
        current.ranked.zip(team.ranked).forEach { (before, after) ->
            assertEquals(before.comparisonValue, after.comparisonValue, 1e-9)
        }
        assertSoleSelection(current, "sacrifice_answer")
        assertSoleSelection(team, "sacrifice_answer")
    }

    private fun assertSoleSelection(trace: LocalDecisionTraceSelector.Trace, actionId: String) {
        assertEquals(actionId, trace.selection.rank.outcome.candidate.actionId)
        assertEquals(1, trace.selection.shortlistSize)
        assertEquals(1.0, trace.selection.probability)
    }

    private fun trace(source: BattleDecisionContext, tuning: LocalDecisionTuning, depth: Int = 1,
        foresightWeight: Double? = null): LocalDecisionTraceSelector.Trace {
        val observer = LocalDecisionTraceSelector(choiceSeedOverride = 0)
        val brain = LocalTacticalBrain(actionSelector = observer, tuning = tuning)
        val boss = BattleTrainerProfile.balanced(5)
        val profile = boss.copy(difficulty = boss.difficulty.copy(lookaheadPlies = depth,
            foresightWeight = foresightWeight ?: boss.difficulty.foresightWeight))
        val session = brain.openSession(BattleBrainOpenContext(source.state.battleId, source.state.format, trainerProfile = profile))
        try {
            val decision = brain.decide(session, source).toCompletableFuture().get()
            assertTrue("lookahead_turns_$depth" in decision.tags)
            assertFalse("lookahead_truncated" in decision.tags)
            return requireNotNull(observer.latest)
        } finally {
            brain.closeSession(session, BattleBrainCloseResult(BattleBrainCloseOutcome.CANCELLED, 1))
        }
    }

    private fun fixture(): BattleDecisionContext {
        val pokemon = listOf(mon(1, BattleSide.ALLY, 0, "rock"),
            mon(2, BattleSide.ALLY, null, "dark"), mon(3, BattleSide.ALLY, null, "normal"),
            mon(4, BattleSide.OPPONENT, 0, "normal"), mon(5, BattleSide.OPPONENT, null, "ghost"))
        val state = BattleStateView(UUID(0, 920), BattleFormat.SINGLE, 1, pokemon, BattleFieldStateView.empty(),
            mapOf(BattleSide.ALLY to 3, BattleSide.OPPONENT to 2), emptyList(), emptyList())
        val catalog = BattlePublicActionCatalogView(pokemon.map { actor ->
            val type = when (actor.battlePokemonId.leastSignificantBits) { 2L -> "dark"; 4L -> "fighting"; 5L -> "psychic"; else -> "normal" }
            BattlePokemonActionCatalogView(actor.battlePokemonId, listOf(BattlePublicMoveOptionView("probe_$type",
                BattleMoveCandidateView(typeId = type, damageCategory = BattleMoveDamageCategory.PHYSICAL,
                    power = 200.0, accuracy = 100.0, priority = 0,
                    currentPp = if (actor.battlePokemonId == UUID(0, 1)) 0 else 8),
                if (actor.side == BattleSide.ALLY) BattlePublicMoveKnowledge.EXACT_OWN
                else BattlePublicMoveKnowledge.PUBLICLY_REVEALED)), moveSetComplete = true)
        })
        // Controlled subset of voluntary switches, not the complete native legal action set.
        return BattleDecisionContext(UUID(0, 921), state, listOf(
            BattleActionCandidate("sacrifice_answer", BattleActionKind.SWITCH, actorSlot = 0, switchPokemonId = UUID(0, 2)),
            BattleActionCandidate("sacrifice_redundant", BattleActionKind.SWITCH, actorSlot = 0, switchPokemonId = UUID(0, 3))),
            Long.MAX_VALUE, publicActionCatalog = catalog)
    }

    private fun mon(id: Long, side: BattleSide, slot: Int?, type: String) = BattlePokemonStateView(
        UUID(0, id), side, slot, "fixture:team_decision", null, 50, 1.0, null, emptyMap(), emptySet(),
        null, null, false, knownTypeIds = setOf(type), combatStats = BattleCombatStatRangesView(
            maxHp = BattleIntegerRange(100, 100), attack = BattleIntegerRange(200, 200),
            defence = BattleIntegerRange(100, 100), specialAttack = BattleIntegerRange(200, 200),
            specialDefence = BattleIntegerRange(100, 100), speed = BattleIntegerRange(100, 100),
            knowledge = if (side == BattleSide.ALLY) BattleCombatStatKnowledge.EXACT_OWN else BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE))
}
