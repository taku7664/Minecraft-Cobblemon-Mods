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
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class LocalNativeStatusValueTest {
    private val worker = EngineBranchWorker()

    @org.junit.jupiter.api.BeforeAll
    fun engine() {
        jbro.cobblemon.mcc.betterai.search.LocalEngineStopProbe.lease = object : jbro.cobblemon.mcc.betterai.search.LocalEngineStopProbe.Lease {
            override fun <T> withWorker(deadlineNanos: Long, action: (jbro.cobblemon.mcc.betterai.simulation.NativeBranchWorker) -> T): T? = action(worker)
        }
    }
    private val planner = NativeInitialProductWorldPlanner(worldLimit = { _, _ -> LocalNativeStatusValue.WORLDS })
    private val evaluator = NativeInitialProductDecisionEvaluator(planner::plan, planner::planMidBattle, planner::planLive,
        searchWorlds = NativeInformationSetSearch(lease = { _, action -> action(worker) })::search)
    private val profile = BattleTrainerProfile(skillLevel = 5, personality = BattleTrainerProfile.champion().personality,
        difficulty = BattleDifficultyProfiles.BOSS)

    @Test
    fun `Haze that only wipes this side's boosts goes under every failing action, and wiping a big boost is worth it`() {
        // Only Milotic boosted: Haze takes its stages and gives nothing, whatever Ferrothorn does. It goes under the
        // level of a move that fails publicly, so it never wins a position where everything else is bad too.
        val selfBoosted = haze { mon(it, BattleSide.ALLY) { p -> p.copyState(statStages = mapOf("spa" to 2, "spd" to 2)) } }
        val foeBoosted = haze { mon(it, BattleSide.OPPONENT) { p -> p.copyState(statStages = mapOf("attack" to 6)) } }
        println("NATIVE STATUS haze: self boosted $selfBoosted foe +6 $foeBoosted")
        assertEquals("priced", selfBoosted.status)
        assertTrue("haze" in selfBoosted.selfHarm, selfBoosted.toString())
        assertTrue(selfBoosted.hazeValue < jbro.cobblemon.mcc.betterai.policy.LocalBattleActionPolicy.PUBLICLY_INERT_TIER_ADJUSTMENT, selfBoosted.toString())
        assertTrue("haze" !in foeBoosted.selfHarm, foeBoosted.toString())
        assertTrue(foeBoosted.gap > 0.0, "Haze on a +6 opponent: $foeBoosted")
    }

    @Test
    fun `Haze costs less when the stage it takes from this side is one the matchup does not use`() {
        // Ferrothorn attacks physically, so Milotic's Special Defence stages are worth little here; its Special Attack
        // ones are what Haze really takes. Neither case is self-harm: Ferrothorn's Attack stages go too.
        val both = reading(mapOf("spa" to 2), mapOf("attack" to 2))
        val ourUseless = reading(mapOf("spd" to 2), mapOf("attack" to 2))
        println("NATIVE STATUS haze both=$both ourUseless=$ourUseless")
        for (case in listOf(both, ourUseless)) assertTrue("haze" !in case.selfHarm, case.toString())
        assertTrue(ourUseless.gap > both.gap, "losing a useless Special Defence costs less: $ourUseless vs $both")
    }

    /**
     * Known gap (2026-10-12): the engine answers with the opponent's worst reply for this side, here Ferrothorn
     * switching out, which drops its stages anyway. So wiping its Attack stages is worth no more than wiping Special
     * Attack ones it never uses (one turn: -34.9 both), and two turns rank them the wrong way (-69 vs -56). Fixing it
     * means weighing the opponent's replies by their predicted likelihood instead of taking the worst.
     */
    @org.junit.jupiter.api.Disabled("Known gap: worst-case replies hide what wiping the opponent's stages is worth")
    @Test
    fun `wiping an opponent stage it uses is worth more than one it does not`() {
        val used = reading(mapOf("spa" to 2), mapOf("attack" to 2))
        val unused = reading(mapOf("spa" to 2), mapOf("special_attack" to 2))
        assertTrue(unused.gap < used.gap, "$unused vs $used")
    }

    private fun reading(ally: Map<String, Int>, foe: Map<String, Int>) = haze { c ->
        mon(mon(c, BattleSide.ALLY) { p -> p.copyState(statStages = ally) }, BattleSide.OPPONENT) { p -> p.copyState(statStages = foe) }
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

    private data class HazeReading(val status: String, val hazeValue: Double, val gap: Double, val selfHarm: Set<String>, val plies: Int)

    /** Haze's final value, its gap to Ice Beam, and the engine's reading. */
    private fun haze(maxPlies: Int = 2, twist: (BattleDecisionContext) -> BattleDecisionContext): HazeReading {
        val raw = java.util.zip.GZIPInputStream(requireNotNull(javaClass.getResourceAsStream("/betterai/snapshots/milotic-ferrothorn-switch-in.json.gz")))
            .use { it.readBytes().toString(Charsets.UTF_8) }.let(AiTestDecisionSnapshot::fromJson).context
        val twisted = twist(raw).copy(deadlineEpochMillis = System.currentTimeMillis() + 60_000L)
        val tier = BattleTrainerTier.BOSS
        val bound = twisted.copy(publicActionCatalog = LocalStatusMoveBinder.bindCatalog(twisted.state, twisted.publicActionCatalog, tier,
            LocalOpponentMoveUsage.forFormat(twisted.state.format)))
        val context = PublicBattleTacticalCalculator.calculate(LocalOpponentStatAssumption.applyToPublicState(bound, tier), observedBoard = true)
        val ranked = LocalBattleActionPolicy.rank(context, null, profile)
        val result = requireNotNull(LocalNativeStatusValue.adjustments(ranked, context, profile, LocalDecisionTuning.CURRENT,
            { c, p, t, b, r -> evaluator.evaluate(c, p, t, b, rules = r) }, maxPlies = maxPlies))
        fun final(moveId: String) = ranked.first { it.outcome.candidate.moveId == moveId && it.outcome.candidate.mechanic == null }
            .let { it.comparisonValue + (result.adjustments[it.outcome.candidate.actionId] ?: 0.0) }
        return HazeReading(result.status, final("haze"), final("haze") - final("icebeam"), result.selfHarm, result.plies)
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
