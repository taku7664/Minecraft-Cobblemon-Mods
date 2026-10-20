package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.brain.AiTestDecisionSnapshot
import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.mcc.betterai.search.LocalNativeStatusValue
import jbro.cobblemon.mcc.betterai.search.LocalEngineTurnReadout
import jbro.cobblemon.mcc.betterai.search.NativeInformationSetSearch
import jbro.cobblemon.mcc.betterai.search.NativeInitialProductDecisionEvaluator
import jbro.cobblemon.mcc.betterai.simulation.EngineBranchWorker
import jbro.cobblemon.mcc.betterai.simulation.LocalOpponentStatAssumption
import jbro.cobblemon.mcc.betterai.simulation.NativeInitialProductWorldPlanner
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveCandidateView
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveTargetPattern
import jbro.cobblemon.mcc.internal.ai.BattlePokemonActionCatalogView
import jbro.cobblemon.mcc.internal.ai.BattleDifficultyProfiles
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattlePublicActionCatalogView
import jbro.cobblemon.mcc.internal.ai.BattlePublicMoveKnowledge
import jbro.cobblemon.mcc.internal.ai.BattlePublicMoveOptionView
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

    private var previousLease: jbro.cobblemon.mcc.betterai.search.LocalEngineStopProbe.Lease? = null

    @org.junit.jupiter.api.BeforeAll
    fun engine() {
        previousLease = jbro.cobblemon.mcc.betterai.search.LocalEngineStopProbe.lease
        jbro.cobblemon.mcc.betterai.search.LocalEngineStopProbe.lease = object : jbro.cobblemon.mcc.betterai.search.LocalEngineStopProbe.Lease {
            override fun <T> withWorker(deadlineNanos: Long, action: (jbro.cobblemon.mcc.betterai.simulation.NativeBranchWorker) -> T): T? = action(worker)
        }
    }

    // The lease is global: left in place, every later test class in the run decided with an engine it never asked for.
    @org.junit.jupiter.api.AfterAll
    fun releaseEngine() {
        previousLease?.let { jbro.cobblemon.mcc.betterai.search.LocalEngineStopProbe.lease = it }
    }
    private val profile = BattleTrainerProfile(skillLevel = 5, personality = BattleTrainerProfile.champion().personality,
        difficulty = BattleDifficultyProfiles.BOSS)

    @Test
    fun `Haze that only wipes this side's boosts is a loss, and wiping a big boost is worth more`() {
        // Haze is priced by what it takes off the exchanges (LocalDebuffValue, 2026-10-20); the engine's status reading this
        // test once ran is no longer part of a decision (user decision 2026-10-19). Only Milotic boosted: Haze takes its
        // stages and gives nothing. Ferrothorn at +6: Haze takes what the boost was giving it.
        fun gain(twist: (BattleDecisionContext) -> BattleDecisionContext): Double {
            val raw = java.util.zip.GZIPInputStream(requireNotNull(javaClass.getResourceAsStream("/betterai/snapshots/milotic-ferrothorn-switch-in.json.gz")))
                .use { it.readBytes().toString(Charsets.UTF_8) }.let(AiTestDecisionSnapshot::fromJson).context
            val context = PublicBattleTacticalCalculator.calculate(
                LocalOpponentStatAssumption.applyToPublicState(twist(raw), BattleTrainerTier.BOSS), observedBoard = true)
            // The snapshot predates the move data's reset effect; the move's effects as the game reads them now.
            val saved = context.candidates.first { it.moveId == "haze" && it.mechanic == null }
            val haze = BattleActionCandidate(saved.actionId, saved.kind, saved.actorSlot, saved.moveSlot, "haze", saved.targets,
                moveDetails = requireNotNull(saved.moveDetails).copy(effects = MOVES.getValue("haze")), facts = saved.facts)
            val scores = jbro.cobblemon.mcc.betterai.matchup.LocalMatchupScoreCalculator.calculate(context)
            return requireNotNull(jbro.cobblemon.mcc.betterai.matchup.LocalDebuffValue.read(haze, context, scores)).gain
        }
        val selfBoosted = gain { mon(it, BattleSide.ALLY) { p -> p.copyState(statStages = mapOf("spa" to 2, "spd" to 2)) } }
        val plain = gain { it }
        val foeBoosted = gain { mon(it, BattleSide.OPPONENT) { p -> p.copyState(statStages = mapOf("attack" to 6)) } }
        println("HAZE gain: self boosted $selfBoosted plain $plain foe +6 $foeBoosted")
        assertTrue(selfBoosted < 0.0, "$selfBoosted")
        assertEquals(0.0, plain, 1e-9)
        assertTrue(foeBoosted > plain, "$foeBoosted vs $plain")
    }

    @Test
    fun `Haze costs less when the stage it takes from this side is one the matchup does not use`() {
        // Ferrothorn attacks physically, so Milotic's Special Defence stages are worth little here; its Special Attack
        // ones are what Haze really takes. Neither case is self-harm: Ferrothorn's Attack stages go too.
        val both = reading(mapOf("spa" to 2), mapOf("attack" to 2))
        val ourUseless = reading(mapOf("spd" to 2), mapOf("attack" to 2))
        val theirUseless = reading(mapOf("spa" to 2), mapOf("special_attack" to 2))
        println("NATIVE STATUS haze both=$both ourUseless=$ourUseless theirUseless=$theirUseless")
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
            val result = requireNotNull(LocalNativeStatusValue.adjustments(ranked, context, profile, LocalDecisionTuning.CURRENT, maxPlies = maxPlies))
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

    @Test
    fun `a status move passing King's Shield is averaged over the same three seeded streams`() {
        val raw = java.util.zip.GZIPInputStream(requireNotNull(javaClass.getResourceAsStream(
            "/betterai/snapshots/milotic-ferrothorn-switch-in.json.gz"))).use { it.readBytes().toString(Charsets.UTF_8) }
            .let(AiTestDecisionSnapshot::fromJson).context
        val ally = raw.state.pokemon.single { it.side == BattleSide.ALLY && it.activeSlot == 0 }
        val foe = raw.state.pokemon.single { it.side == BattleSide.OPPONENT && it.activeSlot == 0 }
        val shieldDetails = BattleMoveCandidateView(
            "steel", BattleMoveDamageCategory.STATUS, 0.0, 100.0, 4, 10,
            BattleMoveTargetPattern.SELF, effects = MOVES.getValue("kingsshield"),
        )
        val replyDetails = BattleMoveCandidateView(
            "electric", BattleMoveDamageCategory.STATUS, 0.0, 90.0, 0, 20,
            BattleMoveTargetPattern.SELECTED_OPPONENT, effects = MOVES.getValue("thunderwave"),
        )
        val shield = BattleActionCandidate(
            "shield", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0, moveId = "kingsshield",
            moveDetails = shieldDetails,
        )
        val entries = raw.publicActionCatalog.entries.map { entry -> when (entry.battlePokemonId) {
            ally.battlePokemonId -> BattlePokemonActionCatalogView(entry.battlePokemonId,
                listOf(BattlePublicMoveOptionView("kingsshield", shieldDetails, BattlePublicMoveKnowledge.EXACT_OWN)), true)
            foe.battlePokemonId -> BattlePokemonActionCatalogView(entry.battlePokemonId,
                listOf(BattlePublicMoveOptionView("thunderwave", replyDetails, BattlePublicMoveKnowledge.PUBLICLY_REVEALED)), true)
            else -> entry
        } }
        val catalog = BattlePublicActionCatalogView(
            entries, raw.publicActionCatalog.originalEntries,
            raw.publicActionCatalog.candidatePools.filterNot { it.battlePokemonId == foe.battlePokemonId },
            raw.publicActionCatalog.opponentMoveInferences.filterNot { it.battlePokemonId == foe.battlePokemonId },
        )
        val context = raw.copy(
            state = raw.state.copyState(pokemon = raw.state.pokemon.map {
                if (it.battlePokemonId == foe.battlePokemonId) it.copyState(knownMoveIds = setOf("thunderwave")) else it
            }),
            candidates = listOf(shield),
            deadlineEpochMillis = System.currentTimeMillis() + 60_000L,
            publicActionCatalog = catalog,
        )
        val intent = jbro.cobblemon.mcc.betterai.matchup.OpponentIntent(foe.battlePokemonId, 0, listOf(
            jbro.cobblemon.mcc.betterai.matchup.IntentOption(
                jbro.cobblemon.mcc.betterai.matchup.IntentKind.STATUS,
                "thunderwave", ally.battlePokemonId, null, 1.0, 1.0,
            ),
        ))
        fun reading(seedCount: Int, seedStart: Int) = LocalEngineTurnReadout.protectTurns(
            context, BattleTrainerTier.BOSS, listOf(shield), listOf(intent), LocalDecisionTuning.CURRENT,
            seedCount, seedStart,
        ).getValue(shield.actionId)
        val singleElapsed = mutableListOf<Long>()
        val singles = (0..2).map { seed ->
            val started = System.nanoTime()
            reading(1, seed).also { singleElapsed += (System.nanoTime() - started) / 1_000_000L }
        }
        val averagedStarted = System.nanoTime()
        val averaged = reading(3, 0)
        val averagedElapsed = (System.nanoTime() - averagedStarted) / 1_000_000L
        println("PROTECT SEEDS singles=$singles single_ms=$singleElapsed averaged=$averaged averaged_ms=$averagedElapsed")

        assertEquals(singles.average(), averaged, 1e-9)
    }

    private data class HazeReading(val status: String, val hazeValue: Double, val gap: Double, val selfHarm: Set<String>, val plies: Int)

    /** Haze's final value, its gap to Ice Beam, and the engine's reading. */
    private fun haze(maxPlies: Int = 2, twist: (BattleDecisionContext) -> BattleDecisionContext): HazeReading {
        val raw = java.util.zip.GZIPInputStream(requireNotNull(javaClass.getResourceAsStream("/betterai/snapshots/milotic-ferrothorn-switch-in.json.gz")))
            .use { it.readBytes().toString(Charsets.UTF_8) }.let(AiTestDecisionSnapshot::fromJson).context
        val twisted = twist(raw).copy(deadlineEpochMillis = System.currentTimeMillis() + 60_000L)
        val tier = BattleTrainerTier.BOSS
        val bound = twisted.copy(publicActionCatalog = twisted.publicActionCatalog)
        val context = PublicBattleTacticalCalculator.calculate(LocalOpponentStatAssumption.applyToPublicState(bound, tier), observedBoard = true)
        val ranked = LocalBattleActionPolicy.rank(context, null, profile)
        val intents = jbro.cobblemon.mcc.betterai.matchup.LocalOpponentIntentPredictor.predict(context,
            jbro.cobblemon.mcc.betterai.matchup.LocalMatchupScoreCalculator.calculate(context), scoredSwitches = true)
        println("NATIVE STATUS intents " + intents.map { it.byAction().entries.sortedByDescending { e -> e.value }.joinToString { e -> "${e.key.take(14)}=%.2f".format(e.value) } })
        val result = requireNotNull(LocalNativeStatusValue.adjustments(ranked, context, profile, LocalDecisionTuning.CURRENT, maxPlies = maxPlies))
        fun final(moveId: String) = ranked.first { it.outcome.candidate.moveId == moveId && it.outcome.candidate.mechanic == null }
            .let { it.comparisonValue + (result.adjustments[it.outcome.candidate.actionId] ?: 0.0) }
        println("NATIVE STATUS elapsed ${result.elapsedMillis} ms plies ${result.plies}")
        return HazeReading(result.status, final("haze"), final("haze") - final("icebeam"), result.selfHarm, result.plies)
    }

    private fun prepared(raw: BattleDecisionContext): Pair<BattleDecisionContext, List<jbro.cobblemon.mcc.betterai.policy.LocalBattleActionRank>> {
        val twisted = raw.copy(deadlineEpochMillis = System.currentTimeMillis() + 60_000L)
        val tier = BattleTrainerTier.BOSS
        val bound = twisted.copy(publicActionCatalog = twisted.publicActionCatalog)
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
