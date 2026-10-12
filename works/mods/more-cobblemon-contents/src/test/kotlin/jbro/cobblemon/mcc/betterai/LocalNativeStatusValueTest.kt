package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.brain.AiTestDecisionSnapshot
import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.mcc.betterai.search.LocalNativeStatusValue
import jbro.cobblemon.mcc.betterai.search.NativeInformationSetSearch
import jbro.cobblemon.mcc.betterai.search.NativeInitialProductDecisionEvaluator
import jbro.cobblemon.mcc.betterai.simulation.EngineBranchWorker
import jbro.cobblemon.mcc.betterai.simulation.LocalOpponentStatAssumption
import jbro.cobblemon.mcc.betterai.simulation.NativeInitialProductWorldPlanner
import jbro.cobblemon.mcc.betterai.state.LocalOpponentMoveUsage
import jbro.cobblemon.mcc.betterai.state.LocalStatusMoveBinder
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveTargetPattern
import jbro.cobblemon.mcc.internal.ai.BattleDifficultyProfiles
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleTrainerProfile
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Status moves without an evaluator, priced by the engine's turn: Milotic's Haze in front of Ferrothorn, a recorded
 * Boss position. The generic value gave Haze 20 whoever held the stages.
 */
class LocalNativeStatusValueTest {
    private val worker = EngineBranchWorker()
    private val planner = NativeInitialProductWorldPlanner(worldLimit = { _, _ -> LocalNativeStatusValue.WORLDS })
    private val evaluator = NativeInitialProductDecisionEvaluator(planner::plan, planner::planMidBattle, planner::planLive,
        searchWorlds = NativeInformationSetSearch(lease = { _, action -> action(worker) })::search)
    private val profile = BattleTrainerProfile(skillLevel = 5, personality = BattleTrainerProfile.champion().personality,
        difficulty = BattleDifficultyProfiles.BOSS)

    @Test
    fun `Haze is worth less than an attack when it wipes this side's boosts and more when it wipes a big boost`() {
        val selfBoosted = haze { mon(it, BattleSide.ALLY) { p -> p.copyState(statStages = mapOf("spa" to 2, "spd" to 2)) } }
        val foeBoosted = haze { mon(it, BattleSide.OPPONENT) { p -> p.copyState(statStages = mapOf("attack" to 6)) } }
        println("NATIVE STATUS haze-attack gap: self boosted ${selfBoosted.second} foe +6 ${foeBoosted.second}")
        assertEquals("priced", selfBoosted.first)
        assertEquals("priced", foeBoosted.first)
        assertTrue(selfBoosted.second < -10.0, "Haze on its own boosts: ${selfBoosted.second}")
        assertTrue(foeBoosted.second > 0.0, "Haze on a +6 opponent: ${foeBoosted.second}")
    }

    @Test
    fun `Yawn's sleep shows only when the engine plays the turn after it`() {
        // A recorded live battle (burned Milotic at 45% in front of an unstatused Ferrothorn) with Haze swapped for Yawn.
        // In its own turn Yawn only makes the target drowsy; it falls asleep at the end of the next one.
        val text = java.util.zip.GZIPInputStream(requireNotNull(javaClass.getResourceAsStream("/betterai/snapshots/milotic-losing-recover-loop.json.gz")))
            .use { it.readBytes().toString(Charsets.UTF_8) }
            .replace("{\\\"move\\\":\\\"Haze\\\",\\\"id\\\":\\\"haze\\\",\\\"pp\\\":30,\\\"maxpp\\\":30,\\\"target\\\":\\\"all\\\"",
                "{\\\"move\\\":\\\"Yawn\\\",\\\"id\\\":\\\"yawn\\\",\\\"pp\\\":16,\\\"maxpp\\\":16,\\\"target\\\":\\\"normal\\\"")
            .replace("Haze", "Yawn").replace("\"haze\"", "\"yawn\"")
        val raw = AiTestDecisionSnapshot.fromJson(text).context
        val sub = raw.candidates.single { it.moveId == "yawn" }
        val aimed = raw.candidates.first { it.moveId == "scald" }
        val swapped = raw.copy(candidates = raw.candidates.map {
            if (it !== sub) it else BattleActionCandidate(sub.actionId, sub.kind, sub.actorSlot, sub.moveSlot, "yawn", aimed.targets,
                moveDetails = requireNotNull(sub.moveDetails).copy(typeId = "Normal", currentPp = 16,
                    targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT, effects = MOVES.getValue("yawn")))
        })
        fun gap(maxPlies: Int): Pair<Int, Double> {
            val (context, ranked) = prepared(swapped)
            val result = requireNotNull(LocalNativeStatusValue.adjustments(ranked, context, profile, LocalDecisionTuning.CURRENT,
                { c, p, t, b, r -> evaluator.evaluate(c, p, t, b, rules = r) }, maxPlies = maxPlies))
            assertEquals("priced", result.status)
            val values = ranked.associate { it.outcome.candidate.actionId to it.comparisonValue + (result.adjustments[it.outcome.candidate.actionId] ?: 0.0) }
            val best = ranked.filter { it.outcome.candidate.actionId != sub.actionId && it.decisionTier > 1 }.maxOf { values.getValue(it.outcome.candidate.actionId) }
            return result.plies to values.getValue(sub.actionId) - best
        }
        val oneTurn = gap(1)
        val twoTurns = gap(2)
        println("NATIVE STATUS yawn gap to the best other action: one turn $oneTurn two turns $twoTurns")
        assertEquals(1, oneTurn.first)
        assertEquals(2, twoTurns.first)
        assertTrue(twoTurns.second > oneTurn.second, "the sleep must count: $oneTurn vs $twoTurns")
    }

    /** Haze's final value minus Ice Beam's, and the engine's status. */
    private fun haze(twist: (BattleDecisionContext) -> BattleDecisionContext): Pair<String, Double> {
        val raw = java.util.zip.GZIPInputStream(requireNotNull(javaClass.getResourceAsStream("/betterai/snapshots/milotic-ferrothorn-switch-in.json.gz")))
            .use { it.readBytes().toString(Charsets.UTF_8) }.let(AiTestDecisionSnapshot::fromJson).context
        val twisted = twist(raw).copy(deadlineEpochMillis = System.currentTimeMillis() + 60_000L)
        val tier = BattleTrainerTier.BOSS
        val bound = twisted.copy(publicActionCatalog = LocalStatusMoveBinder.bindCatalog(twisted.state, twisted.publicActionCatalog, tier,
            LocalOpponentMoveUsage.forFormat(twisted.state.format)))
        val context = PublicBattleTacticalCalculator.calculate(LocalOpponentStatAssumption.applyToPublicState(bound, tier), observedBoard = true)
        val ranked = LocalBattleActionPolicy.rank(context, null, profile)
        val result = requireNotNull(LocalNativeStatusValue.adjustments(ranked, context, profile, LocalDecisionTuning.CURRENT,
            { c, p, t, b, r -> evaluator.evaluate(c, p, t, b, rules = r) }))
        fun final(moveId: String) = ranked.first { it.outcome.candidate.moveId == moveId && it.outcome.candidate.mechanic == null }
            .let { it.comparisonValue + (result.adjustments[it.outcome.candidate.actionId] ?: 0.0) }
        return result.status to final("haze") - final("icebeam")
    }

    private fun prepared(raw: BattleDecisionContext): Pair<BattleDecisionContext, List<jbro.cobblemon.mcc.betterai.policy.LocalBattleActionRank>> {
        val twisted = raw.copy(deadlineEpochMillis = System.currentTimeMillis() + 60_000L)
        val tier = BattleTrainerTier.BOSS
        val bound = twisted.copy(publicActionCatalog = LocalStatusMoveBinder.bindCatalog(twisted.state, twisted.publicActionCatalog, tier,
            LocalOpponentMoveUsage.forFormat(twisted.state.format)))
        val context = PublicBattleTacticalCalculator.calculate(LocalOpponentStatAssumption.applyToPublicState(bound, tier), observedBoard = true)
        return context to LocalBattleActionPolicy.rank(context, null, profile)
    }

    private companion object {
        val MOVES: Map<String, jbro.cobblemon.mcc.internal.ai.BattleMoveEffectsView> by lazy {
            java.util.zip.ZipInputStream(requireNotNull(LocalNativeStatusValueTest::class.java.getResourceAsStream("/data/cobblemon/showdown.zip"))).use { zip ->
                generateSequence { zip.nextEntry }.first { it.name == "data/moves.js" }
                jbro.cobblemon.mcc.internal.ai.BattleDeclarativeMoveEffects.parse(zip.readBytes().toString(Charsets.UTF_8))
            }
        }
    }

    private fun mon(c: BattleDecisionContext, side: BattleSide, map: (BattlePokemonStateView) -> BattlePokemonStateView) =
        c.copy(state = c.state.derive(pokemon = c.state.pokemon.map { if (it.side == side && it.activeSlot == 0) map(it) else it }))
}
