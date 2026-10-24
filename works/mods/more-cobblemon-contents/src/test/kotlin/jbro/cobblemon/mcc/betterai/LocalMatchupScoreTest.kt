package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.betterai.matchup.LocalKnockoutProfile
import jbro.cobblemon.mcc.betterai.matchup.LocalAceScore
import jbro.cobblemon.mcc.betterai.matchup.LocalGimmickReserve
import jbro.cobblemon.mcc.betterai.matchup.LocalMatchupScoreCalculator
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.betterai.matchup.LocalOpponentIntentPredictor
import jbro.cobblemon.mcc.betterai.matchup.LocalPublicFailureTriage
import jbro.cobblemon.mcc.betterai.matchup.StopScore
import jbro.cobblemon.mcc.betterai.matchup.StopToolKind
import jbro.cobblemon.mcc.betterai.matchup.LocalSetupGate
import jbro.cobblemon.mcc.betterai.matchup.LocalStatusMoveTriage
import jbro.cobblemon.mcc.betterai.matchup.LocalSwitchRules
import jbro.cobblemon.mcc.betterai.matchup.MatchupSpeedField
import jbro.cobblemon.mcc.betterai.matchup.StatusMoveMatchupScore
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalMatchupScoreTest {
    @Test
    fun `low HP discounts positive setup gain smoothly without changing its HP cost`() {
        val base = context(allySpeed = 150, foeSpeed = 100, allyPower = 40.0, foePower = 0.01,
            allySetup = mapOf("atk" to 2), secondFoe = true)
        for ((hp, safety) in listOf(1.0 to 1.0, 0.75 to 0.99609375, 0.5 to 0.9375, 0.25 to 0.68359375, 0.1 to 0.3439, 0.01 to 0.03940399)) {
            val board = base.copy(state = base.state.derive(pokemon = base.state.pokemon.map {
                if (it.battlePokemonId == ALLY) it.copyState(hpFraction = hp) else it }))
            val internals = jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals
            internals.begin()
            val reading: LocalSetupGate.Reading
            val trace: Map<String, Double>
            try {
                reading = LocalSetupGate.score(setupAction(board), board, LocalMatchupScoreCalculator.calculate(board), 200.0)!!
                trace = internals.take().getValue("setup:${setupAction(board).actionId}")
            } finally { internals.clear() }
            val gain = (reading.pressure + reading.speed) * reading.survival * reading.holds
            assertTrue(gain > 0.0, "hp=$hp: $reading")
            assertEquals(hp, reading.hpRatio, 1e-12)
            assertEquals(safety, reading.hpSafety, 1e-12)
            assertEquals((gain * safety - reading.hpCost) * reading.effectChance, reading.value, 1e-9)
            assertEquals(gain * safety, trace.getValue("guardedGain"), 1e-9)
            assertEquals(hp, trace.getValue("hpRatio"), 1e-12)
        }
        val drumDetails = BattleMoveCandidateView("normal", BattleMoveDamageCategory.STATUS, 0.0, 100.0, 0, 10,
            BattleMoveTargetPattern.SELF, effects = BattleShowdownMoveEffects.resolve("bellydrum"))
        val drum = BattlePublicMoveOptionView("bellydrum", drumDetails, BattlePublicMoveKnowledge.EXACT_OWN)
        val built = context(150, 100, allyPower = 40.0, foePower = 0.01, allyExtra = listOf(drum))
        val board = built.copy(state = built.state.derive(pokemon = built.state.pokemon.map {
            if (it.battlePokemonId == ALLY) it.copyState(hpFraction = 0.75) else it }))
        val action = BattleActionCandidate("bellydrum", BattleActionKind.USE_MOVE, 0, 1, "bellydrum", moveDetails = drumDetails)
        val reading = LocalSetupGate.score(action, board, LocalMatchupScoreCalculator.calculate(board), 200.0)!!
        val gain = (reading.pressure + reading.speed) * reading.survival * reading.holds
        assertEquals(50.0, reading.hpCost, 1e-9)
        assertEquals(0.99609375, reading.hpSafety, 1e-12)
        assertEquals(((if (gain > 0.0) gain * reading.hpSafety else gain) - 50.0) * reading.effectChance, reading.value, 1e-9)
        val insufficient = board.copy(state = board.state.derive(pokemon = board.state.pokemon.map {
            if (it.battlePokemonId == ALLY) it.copyState(hpFraction = 0.25) else it }))
        val failed = LocalSetupGate.score(action, insufficient, LocalMatchupScoreCalculator.calculate(insufficient), 200.0)!!
        assertEquals(0.0, failed.value, 1e-12)
        assertEquals(0.25, failed.hpRatio, 1e-12)
        assertEquals(0.68359375, failed.hpSafety, 1e-12)
    }


    @Test
    fun `Max history preserves inferred attacks and intent probabilities before and during Dynamax`() {
        fun board(withHistory: Boolean, dynamax: Boolean): BattleDecisionContext {
            val maxMoves = if (!withHistory) emptyList() else listOf("maxflare", "maxquake", "gmaxwildfire", "maxguard").map {
                BattlePublicMoveOptionView(it, BattleMoveCandidateView("fire", BattleMoveDamageCategory.SPECIAL,
                    10.0, 100.0, 0, 8), BattlePublicMoveKnowledge.PUBLICLY_REVEALED)
            }
            val base = context(allySpeed = 100, foeSpeed = 120, foeExtra = maxMoves)
            val flame = BattleMoveCandidateView("fire", BattleMoveDamageCategory.SPECIAL, 90.0, 100.0, 0, 8)
            val catalog = base.publicActionCatalog.withOpponentMoveInferences(listOf(BattleOpponentMoveInferenceView(FOE,
                listOf(BattleOpponentMoveSlotView(0, "flamethrower", BattleOpponentMoveGroup.COVERAGE_ATTACK,
                    BattleOpponentMoveKnowledge.CONFIRMED, BattleOpponentMoveSource.DIFFICULTY_SET_READ, flame)))))
            return base.copy(publicActionCatalog = catalog, state = base.state.derive(pokemon = base.state.pokemon.map {
                if (it.battlePokemonId == FOE && dynamax) jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax.start(it) else it
            }))
        }
        fun predict(context: BattleDecisionContext) = LocalOpponentIntentPredictor.predict(context,
            LocalMatchupScoreCalculator.calculate(context)).single().options
        for (dynamax in listOf(false, true)) {
            val expected = predict(board(false, dynamax))
            val actual = predict(board(true, dynamax))
            assertTrue(actual.any { it.moveId == "flamethrower" && it.probability > 0.0 })
            assertEquals(expected, actual)
        }
    }


    @Test
    fun `rank transfer is read from move data rather than assumed for every pivot`() {
        assertTrue("passes_stat_stages" in BattleShowdownMoveEffects.flags("batonpass"))
        assertFalse("passes_stat_stages" in BattleShowdownMoveEffects.flags("uturn"))
        assertFalse("passes_stat_stages" in BattleShowdownMoveEffects.flags("shedtail"))
    }

    @Test
    fun `switch rank value prices useful boosts and clearing drops without a price per stage`() {
        val base = context(150, 100, allyPower = 40.0, foePower = 20.0, secondFoe = true)
        fun loss(stages: Map<String, Int>): Double {
            val c = base.copy(state = base.state.derive(pokemon = base.state.pokemon.map {
                if (it.battlePokemonId == ALLY) it.copyState(statStages = stages) else it }))
            return jbro.cobblemon.mcc.betterai.matchup.LocalSwitchRankValue.loss(c, c.state.pokemon.first { it.battlePokemonId == ALLY })
        }
        assertEquals(0.0, loss(emptyMap()), 1e-9)
        assertEquals(0.0, loss(mapOf("special_attack" to 4)), 1e-9)
        assertTrue(loss(mapOf("attack" to 2)) > 0.0)
        assertTrue(loss(mapOf("attack" to -2)) < 0.0)
    }

    @Test
    fun `the current foe rank advantage is not charged again as reserve value`() {
        val base = context(150, 100, allyPower = 40.0, foePower = 20.0)
        val c = base.copy(state = base.state.derive(pokemon = base.state.pokemon.map {
            if (it.battlePokemonId == ALLY) it.copyState(statStages = mapOf("attack" to 4)) else it }))
        assertEquals(0.0, jbro.cobblemon.mcc.betterai.matchup.LocalSwitchRankValue.loss(c,
            c.state.pokemon.first { it.battlePokemonId == ALLY }), 1e-9)
    }

    @Test
    fun `a pivot keeps a negative exchange instead of flooring the loss away`() {
        val c = context(150, 100, allyPower = 300.0, benchPower = 40.0, foePower = 65.0, allyExtra = listOf(uturn()))
        val scores = LocalMatchupScoreCalculator.calculate(c)
        val j = LocalSwitchRules.judge(listOf(pivotAction(c), switchAction()), c, scores)
        assertTrue(j.adjustments.getValue("pivot") < 0.0)
    }

    @Test
    fun `hard switch and pivot use the same rank loss and record the whole exchange arithmetic`() {
        val base = context(150, 100, allyPower = 40.0, benchPower = 80.0, foePower = 20.0,
            secondFoe = true, allyExtra = listOf(uturn()))
        val c = base.copy(state = base.state.derive(pokemon = base.state.pokemon.map {
            if (it.battlePokemonId == ALLY) it.copyState(statStages = mapOf("attack" to 2)) else it }))
        val scores = LocalMatchupScoreCalculator.calculate(c)
        val internals = jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals
        internals.begin()
        try {
            val j = LocalSwitchRules.judge(listOf(attackAction(), pivotAction(c), switchAction()), c, scores)
            val readings = internals.take()
            val hard = readings.getValue("switch:switch")
            val pivot = readings.getValue("pivot:pivot")
            assertTrue(hard.getValue("rankLoss") > 0.0)
            assertEquals(hard.getValue("rankLoss"), pivot.getValue("rankLoss"), 1e-9)
            assertEquals(hard.getValue("switchGoesOn") - hard.getValue("stayGoesOn") + hard.getValue("kept") -
                hard.getValue("reentryHpLoss") - hard.getValue("rankLoss") / 100.0, hard.getValue("gain"), 1e-9)
            assertEquals((pivot.getValue("switchValue") - pivot.getValue("stayValue")) * 100.0 - pivot.getValue("rankLoss"),
                j.adjustments.getValue("pivot"), 1e-9)
        } finally { internals.clear() }
    }

    @Test
    fun `virtual entry reuse retains hazard and ability results and the callers decision fields`() {
        val base = context(150, 100, rocksOnAllySide = true, secondFoe = true)
        val bench = base.state.pokemon.first { it.battlePokemonId == BENCH }
        val cache = jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache()
        val first = requireNotNull(jbro.cobblemon.mcc.betterai.matchup.LocalMatchupPosition.enter(base, bench, cache))
        val source = base.copy(deadlineEpochMillis = 987654321L,
            candidates = listOf(BattleActionCandidate("another", BattleActionKind.WAIT)))
        val repeated = requireNotNull(jbro.cobblemon.mcc.betterai.matchup.LocalMatchupPosition.enter(source, bench, cache))
        assertSame(first.state, repeated.state)
        assertEquals(source.candidates, repeated.candidates)
        assertEquals(source.deadlineEpochMillis, repeated.deadlineEpochMillis)
        assertEquals(1, cache.matchupEntriesBuilt)
        assertEquals(1, cache.matchupEntryHits)
        val variants = listOf(
            base.copy(state = base.state.copyState(pokemon = base.state.pokemon.map {
                if (it.battlePokemonId == BENCH) it.copyState(hpFraction = 0.4, knownAbilityId = "intimidate") else it })),
            context(150, 100, rocksOnAllySide = false, secondFoe = true),
        )
        val keys = jbro.cobblemon.mcc.betterai.mechanics.LocalBattleStateFingerprint()
        for (variant in variants) {
            val member = variant.state.pokemon.first { it.battlePokemonId == BENCH }
            val fresh = requireNotNull(jbro.cobblemon.mcc.betterai.matchup.LocalMatchupPosition.enter(variant, member,
                jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache()))
            val reused = requireNotNull(jbro.cobblemon.mcc.betterai.matchup.LocalMatchupPosition.enter(variant, member, cache))
            assertEquals(keys.of(fresh.state), keys.of(reused.state))
            assertEquals(fresh.publicActionCatalog.entries, reused.publicActionCatalog.entries)
        }
        val trapped = base.copy(state = base.state.copyState(pokemon = base.state.pokemon.map {
            if (it.battlePokemonId == ALLY) it.copyState(actionConstraints = it.actionConstraints.copy(trapped = true)) else it }))
        assertNull(jbro.cobblemon.mcc.betterai.matchup.LocalMatchupPosition.enter(trapped, bench, cache))
        assertNull(jbro.cobblemon.mcc.betterai.matchup.LocalMatchupPosition.enter(trapped, bench, cache))
    }

    @Test
    fun `complete move reuse keeps HP ability field catalog and tuning inputs distinct`() {
        val cache = jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache()
        val base = context(150, 100, allyExtra = listOf(BattlePublicMoveOptionView("quickattack",
            BattleMoveCandidateView(typeId = "normal", damageCategory = BattleMoveDamageCategory.PHYSICAL,
                power = 40.0, accuracy = 100.0, priority = 1, currentPp = 10), BattlePublicMoveKnowledge.EXACT_OWN)))
        fun read(c: BattleDecisionContext, memo: jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache = cache) =
            LocalMatchupScoreCalculator.moveMatchups(c, ALLY, FOE, memo).map { it.score }
        val expected = read(base)
        assertEquals(expected, read(base.copy(state = base.state.copyState())))
        assertEquals(1, cache.matchupMoveListHits)
        assertEquals(1, cache.matchupMoveListsBuilt)
        val variants = listOf(
            base.copy(state = base.state.copyState(pokemon = base.state.pokemon.map {
                if (it.battlePokemonId == FOE) it.copyState(hpFraction = 0.21) else it })),
            base.copy(state = base.state.copyState(pokemon = base.state.pokemon.map {
                if (it.battlePokemonId == FOE) it.copyState(knownAbilityId = "sturdy") else it })),
            base.copy(state = base.state.copyState(pokemon = base.state.pokemon.map {
                if (it.battlePokemonId == ALLY) it.copyState(statStages = mapOf("atk" to -2)) else it })),
            base.copy(state = base.state.derive(field = BattleFieldStateView(BattleTimedEffectView("raindance", 5), null, emptyList(), emptyList(),
                mapOf(BattleSide.ALLY to emptyList(), BattleSide.OPPONENT to emptyList())))),
            base.copy(publicActionCatalog = BattlePublicActionCatalogView(emptyList())),
        )
        for (variant in variants) {
            val fresh = jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache()
            assertEquals(read(variant, fresh), read(variant))
        }
        assertEquals(1 + variants.size, cache.matchupMoveListsBuilt)
        val tuning = jbro.cobblemon.mcc.betterai.evaluation.LocalActiveTuning.current()
        jbro.cobblemon.mcc.betterai.evaluation.LocalActiveTuning.with(tuning.copy(simpleFixedHpReactions = true)) {
            assertEquals(expected, read(base))
        }
        assertEquals(2 + variants.size, cache.matchupMoveListsBuilt)
        jbro.cobblemon.mcc.betterai.evaluation.LocalActiveTuning.with(tuning.copy(evolvingMatchups = true)) {
            read(base)
            read(base)
        }
        assertEquals(2 + variants.size, cache.matchupMoveListsBuilt)
    }

    @Test
    fun `current pair requests exactly preserve full table exchanges moves and worths`() {
        for (recovery in listOf(false, true)) {
            val context = context(allySpeed = 150, foeSpeed = 100, rocksOnAllySide = true,
                secondFoe = true, allySetup = mapOf("atk" to 2))
            val full = LocalMatchupScoreCalculator.calculate(context, recovery = recovery)
            val pairs = LocalMatchupScoreCalculator.currentPairs(context, recovery = recovery)
            assertTrue(pairs.complete)
            assertFalse(pairs is jbro.cobblemon.mcc.betterai.matchup.MatchupScores)
            for (ally in context.state.pokemon.filter { it.side == BattleSide.ALLY })
                for (foe in context.state.pokemon.filter { it.side == BattleSide.OPPONENT }) {
                    assertEquals(full.pokemon(ally.battlePokemonId, foe.battlePokemonId), pairs.pokemon(ally.battlePokemonId, foe.battlePokemonId))
                    assertEquals(full.pokemon(foe.battlePokemonId, ally.battlePokemonId), pairs.pokemon(foe.battlePokemonId, ally.battlePokemonId))
                    assertEquals(full.moves(ally.battlePokemonId, foe.battlePokemonId), pairs.moves(ally.battlePokemonId, foe.battlePokemonId))
                    assertNull(pairs.pokemon(ally.battlePokemonId, foe.battlePokemonId, MatchupSpeedField.TRICK_ROOM_TOGGLED))
                }
            val worth = jbro.cobblemon.mcc.betterai.matchup.LocalKnockoutWorth
            assertEquals(worth.read(context.state, full, 1.0), worth.read(context.state, pairs, 1.0))
            val expected = worth.read(context.state, full, 200.0)
            jbro.cobblemon.mcc.betterai.calculation.LocalDecisionCalculationScope.with { scope ->
                assertEquals(expected, worth.read(context.state, full, 200.0))
                assertEquals(worth.read(context.state, pairs, 1.0).standings, worth.standings(context.state, full))
                assertEquals(expected.worths, worth.worths(context.state, full, 200.0))
                assertEquals(2, scope.hits)
                assertEquals(2, scope.misses)
            }
            val subset = LocalMatchupScoreCalculator.currentPairs(context, allyIds = setOf(BENCH), opponentIds = setOf(FOE))
            assertEquals(full.pokemon(BENCH, FOE), subset.pokemon(BENCH, FOE))
            assertNull(subset.pokemon(ALLY, FOE))
            val ace = LocalAceScore.calculate(context)
            val freshMatchup = LocalAceScore.matchup(context, emptyMap())
            jbro.cobblemon.mcc.betterai.calculation.LocalDecisionCalculationScope.with { scope ->
                assertEquals(ace, LocalAceScore.calculate(context))
                assertEquals(freshMatchup, LocalAceScore.matchup(context, emptyMap()))
                assertEquals(2, scope.hits)
                assertEquals(2, scope.misses)
            }
        }
    }

    @Test
    fun `a knockout profile counts uses to the knockout, misses included`() {
        val sure = LocalKnockoutProfile.of(List(16) { 0.6 }, 1.0, 1.0, 6)
        assertEquals(listOf(1.0, 1.0, 0.0), sure.survival.take(3))
        assertEquals(2.0, sure.expectedUses, 1e-9)

        val coinFlip = LocalKnockoutProfile.of(List(16) { 0.6 }, 0.5, 1.0, 6)
        // Two landed uses knock out; within two uses that is one chance in four.
        assertEquals(0.75, coinFlip.survival[2], 1e-9)
        // Standing after one use: the miss (nothing taken) and the landed 0.6, equally likely.
        assertEquals(0.3, coinFlip.damageWhileStanding[1], 1e-9)

        val nothing = LocalKnockoutProfile.of(List(16) { 0.0 }, 1.0, 1.0, 6)
        assertTrue(nothing.expectedUses.isInfinite())
    }

    @Test
    fun `an attack weakened by its own landings reads each landing at its stage`() {
        // Draco Meteor-like: 0.4 of the bar, then 0.2, then 0.1 for every later landing. Repeating the first roll knocks
        // out on the third use; the weakened one does not until the sixth.
        val repeated = LocalKnockoutProfile.of(List(16) { 0.4 }, 1.0, 1.0, 6)
        val weakened = LocalKnockoutProfile.byHits(listOf(List(16) { 0.4 }, List(16) { 0.2 }, List(16) { 0.1 }), 1.0, 1.0, 6)
        assertEquals(0.0, repeated.survival[3], 1e-9)
        assertEquals(1.0, weakened.survival[5], 1e-9)
        assertEquals(0.7, weakened.damageWhileStanding[3], 1e-6)
        assertTrue(weakened.expectedUses > repeated.expectedUses)
        // A miss lowers nothing: at half accuracy, standing after two uses with 0.6 taken means both landed (1/4).
        val missing = LocalKnockoutProfile.byHits(listOf(List(16) { 0.4 }, List(16) { 0.2 }), 0.5, 1.0, 6)
        assertEquals(0.25 * 0.6 + 0.5 * 0.4, missing.damageWhileStanding[2], 1e-6)
        // Past the window each case goes on from its own stage: after one use, the miss still needs a 0.5 landing (2 uses)
        // and then two at 0.25 (4 uses); the landing needs two at 0.25 (4 uses): 1 + 0.5 * 6 + 0.5 * 4 (Codex review).
        val tail = LocalKnockoutProfile.byHits(listOf(List(16) { 0.5 }, List(16) { 0.25 }), 0.5, 1.0, 1)
        assertEquals(6.0, tail.expectedUses, 1e-6)
    }

    @Test
    fun `a roll one point short of the remaining HP is not a knockout`() {
        val profile = LocalKnockoutProfile.of(List(15) { 0.4999 } + 0.5, 1.0, 0.5, 6)
        assertEquals(1.0 / 16, 1.0 - profile.survival[1], 1e-9)
    }

    @Test
    fun `the faster one-hit knockout wins the exchange with its HP intact, and the mirror is its negation`() {
        val context = context(allySpeed = 150, foeSpeed = 100)
        val scores = LocalMatchupScoreCalculator.calculate(context)
        val matchup = scores.pokemon(ALLY, FOE)!!
        assertEquals(1.0, matchup.subjectMovesFirstProbability, 1e-9)
        assertEquals(1.0, matchup.winProbability, 1e-9)
        assertEquals(1.0, matchup.subjectRemainingHpOnWin, 1e-9)
        assertEquals(1.0, matchup.score, 1e-9)
        assertEquals(1.0, matchup.subjectMove!!.score, 1e-9)
        val mirror = scores.pokemon(FOE, ALLY)!!
        assertEquals(-1.0, mirror.score, 1e-9)
        assertEquals(0.0, mirror.winProbability, 1e-9)
    }

    @Test
    fun `trick room turns the same exchange around`() {
        val scores = LocalMatchupScoreCalculator.calculate(context(allySpeed = 150, foeSpeed = 100))
        val reversed = scores.pokemon(ALLY, FOE, MatchupSpeedField.TRICK_ROOM_TOGGLED)!!
        assertEquals(0.0, reversed.subjectMovesFirstProbability, 1e-9)
        assertEquals(0.0, reversed.winProbability, 1e-9)
        assertEquals(-1.0, reversed.score, 1e-9)
    }

    @Test
    fun `a bench Pokemon is scored as it would enter, hazards included`() {
        val context = context(allySpeed = 150, foeSpeed = 100, rocksOnAllySide = true)
        val benched = LocalMatchupScoreCalculator.calculate(context).pokemon(BENCH, FOE)!!
        // Stealth Rock takes an eighth of a neutral Pokemon's HP on entry, rounded down to whole HP (18 of 150), and that
        // is what it keeps on a win.
        assertEquals(1.0 - 18.0 / 150.0, benched.subjectRemainingHpOnWin, 1e-6)
    }

    @Test
    fun `a slower Pokemon that needs two hits against one that needs one loses outright`() {
        val scores = LocalMatchupScoreCalculator.calculate(context(allySpeed = 100, foeSpeed = 150, allyPower = 40.0))
        val matchup = scores.pokemon(ALLY, FOE)!!
        assertTrue(matchup.subjectMove!!.expectedHitsToKnockout > 1.0)
        assertEquals(0.0, matchup.winProbability, 1e-9)
        assertTrue(matchup.opponentRemainingHpOnLoss >= 0.99) { "the foe moved first and was never hit: $matchup" }
        assertTrue(matchup.score < -0.9)
    }

    @Test
    fun `dragon dance makes a sweeper of a Pokemon that loses the plain exchange`() {
        // Both sides need three hits; the foe is faster. One Dragon Dance costs a hit, then two hits win it first.
        val scores = LocalMatchupScoreCalculator.calculate(context(allySpeed = 100, foeSpeed = 120, allyPower = 65.0,
            foePower = 65.0, allySetup = mapOf("atk" to 1, "spe" to 1)))
        assertEquals(0.0, scores.pokemon(ALLY, FOE)!!.winProbability, 1e-9)
        val sweeper = scores.sweeps.getValue(ALLY)
        assertEquals(0.0, sweeper.naturalSweep, 1e-9)
        assertEquals("dragondance", sweeper.setupMoveId)
        assertEquals(1.0, sweeper.setupSafety, 1e-9)
        assertEquals(1.0, sweeper.boostedSweep, 1e-9)
        assertEquals(1.0, sweeper.score, 1e-9)
    }

    @Test
    fun `a setup turn that gets the user knocked out is worth nothing`() {
        // The foe two-hits and moves first: after one Swords Dance the user falls before it swings.
        val scores = LocalMatchupScoreCalculator.calculate(context(allySpeed = 100, foeSpeed = 120, allyPower = 65.0,
            foePower = 90.0, allySetup = mapOf("atk" to 2)))
        val sweeper = scores.sweeps.getValue(ALLY)
        assertEquals(0.0, sweeper.boostedSweep, 1e-9)
        assertEquals(0.0, sweeper.score, 1e-9)
    }

    @Test
    fun `the exchange takes the priority move that moves first over the stronger slow one`() {
        // The foe is faster and two-hits. The slow move two-hits too but never lands its second hit; the
        // weaker priority move needs a lucky two hits, and that is the only way to win.
        val quick = BattlePublicMoveOptionView("quick", BattleMoveCandidateView(typeId = "normal",
            damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 80.0, accuracy = 100.0, priority = 1, currentPp = 8),
            BattlePublicMoveKnowledge.EXACT_OWN)
        val scores = LocalMatchupScoreCalculator.calculate(context(allySpeed = 100, foeSpeed = 150, allyPower = 90.0,
            foePower = 90.0, allyExtra = listOf(quick)))
        val matchup = scores.pokemon(ALLY, FOE)!!
        assertEquals("probe", scores.moves(ALLY, FOE).first().moveId)
        assertEquals("quick", matchup.subjectMove!!.moveId)
        assertEquals(1.0, matchup.subjectMovesFirstProbability, 1e-9)
        assertTrue(matchup.winProbability > 0.0)
    }

    @Test
    fun `doubles bench Pokemon are scored too`() {
        val scores = LocalMatchupScoreCalculator.calculate(context(allySpeed = 150, foeSpeed = 100, format = BattleFormat.DOUBLE))
        assertNotNull(scores.pokemon(BENCH, FOE))
        assertNotNull(scores.sweeps[BENCH])
    }

    @Test
    fun `the matchup table does not name Haze as a stopper`() {
        val haze = BattlePublicMoveOptionView("haze", BattleMoveCandidateView(typeId = "ice",
            damageCategory = BattleMoveDamageCategory.STATUS, power = 0.0, accuracy = 100.0, priority = 0, currentPp = 8,
            targetPattern = BattleMoveTargetPattern.ALL_ACTIVE), BattlePublicMoveKnowledge.EXACT_OWN)
        // Unboosted, the faster ally wins a three-hit race; after one Dragon Dance the foe outspeeds and two-hits.
        val scores = LocalMatchupScoreCalculator.calculate(context(allySpeed = 100, foeSpeed = 90, allyPower = 65.0,
            foePower = 65.0, foeSetup = mapOf("atk" to 1, "spe" to 1), allyExtra = listOf(haze)))
        assertEquals(1.0, scores.pokemon(ALLY, FOE)!!.winProbability, 1e-9)
        assertEquals("dragondance", scores.sweeps.getValue(FOE).setupMoveId)
        val anti = scores.stops.getValue(ALLY)
        assertEquals(FOE, anti.sweeperId)
        assertEquals(0.0, anti.tools.single { it.kind == StopToolKind.OUTLASTS }.value, 1e-9)
        // Haze's effect lives in a script, not in its data: the matchup table no longer names it. What it does to a
        // boosted sweeper is played out in the engine where it decides anything (LocalEngineStopProbeTest).
        assertTrue(anti.tools.none { it.moveId == "haze" }, anti.tools.toString())
    }

    @Test
    fun `a focus sash guarantees the first action and counts as a stopper`() {
        val scores = LocalMatchupScoreCalculator.calculate(context(allySpeed = 100, foeSpeed = 90, allyPower = 65.0,
            foePower = 65.0, foeSetup = mapOf("atk" to 1, "spe" to 1), allyItem = "focussash"))
        val anti = scores.stops.getValue(ALLY)
        assertEquals("focussash", anti.oneTimeSurvival)
        assertEquals(1.0, anti.actsBeforeKnockout, 1e-9)
        assertEquals(StopScore.ONE_TIME_SURVIVAL_BONUS, anti.score, 1e-9)
    }

    @Test
    fun `the setup gate lets the dragon dance through when it wins and nothing stops it`() {
        val context = context(allySpeed = 100, foeSpeed = 120, allyPower = 65.0, foePower = 65.0,
            allySetup = mapOf("atk" to 1, "spe" to 1))
        val verdict = LocalSetupGate.evaluate(setupAction(context), context, LocalMatchupScoreCalculator.calculate(context))!!
        assertTrue(verdict.passes) { verdict.failures.toString() }
    }

    @Test
    fun `a setup can gain pressure without passing a win threshold`() {
        // Slower and weaker: after +2 Attack, with the setup turn's hit paid, it still loses to the foe in front.
        val context = context(allySpeed = 100, foeSpeed = 120, allyPower = 65.0, foePower = 90.0, allySetup = mapOf("atk" to 2))
        val reading = LocalSetupGate.score(setupAction(context), context, LocalMatchupScoreCalculator.calculate(context), knockoutMaterialScore = 200.0)!!
        assertTrue(reading.pressure > 0.0) { reading.toString() }
        assertTrue(reading.value > 0.0 && reading.value < 200.0) { reading.toString() }
    }

    @Test
    fun `a setup is worth its pressure and order change times the usable chance`() {
        val context = context(allySpeed = 100, foeSpeed = 120, allyPower = 65.0, foePower = 65.0, allySetup = mapOf("atk" to 1, "spe" to 1))
        val reading = LocalSetupGate.score(setupAction(context), context, LocalMatchupScoreCalculator.calculate(context), knockoutMaterialScore = 200.0)!!
        assertEquals((reading.pressure + reading.speed) * reading.survival * reading.holds * reading.effectChance,
            reading.value, 1e-9)
        assertTrue(reading.value > 0.0 && reading.value < 200.0) { reading.toString() }
    }

    @Test
    fun `a raise that changes no fight left is worth nothing, however well its user already does`() {
        // Faster and knocking out in one hit, with no foe behind: another raise changes nothing, so the attack goes first.
        // Read as a win alone, a Scizor kept Swords Dancing to +6 (dev server, 2026-10-20).
        val context = context(allySpeed = 150, foeSpeed = 100, allyPower = 300.0, foePower = 65.0, allySetup = mapOf("atk" to 2))
        val reading = LocalSetupGate.score(setupAction(context), context, LocalMatchupScoreCalculator.calculate(context), knockoutMaterialScore = 200.0)!!
        assertEquals(0.0, reading.value, 1e-9)
    }

    @Test
    fun `all setup stats use their changed outcomes and saturated boosts gain nothing`() {
        for (stages in listOf(mapOf("atk" to 2), mapOf("def" to 2), mapOf("spe" to 2),
            mapOf("atk" to 1, "spe" to 1), mapOf("def" to 1, "spd" to 1))) {
            val context = context(allySpeed = 100, foeSpeed = 120, allyPower = 65.0, foePower = 65.0, allySetup = stages)
            val action = setupAction(context)
            val reading = LocalSetupGate.score(action, context, LocalMatchupScoreCalculator.calculate(context), 200.0)!!
            if (stages.keys == setOf("spe")) assertEquals(0.0, reading.value, 1e-9, "no knockout is decided by order")
            else assertTrue(reading.value > 0.0, "$stages: $reading")
            val capped = context.copy(state = context.state.copyState(pokemon = context.state.pokemon.map {
                if (it.battlePokemonId == ALLY) it.copyState(statStages = mapOf("attack" to 6, "defense" to 6,
                    "specialattack" to 6, "specialdefense" to 6, "speed" to 6)) else it }))
            assertEquals(0.0, LocalSetupGate.score(action, capped, LocalMatchupScoreCalculator.calculate(capped), 200.0)!!.value, 1e-9)
        }
    }

    @Test
    fun `named physical special defensive and mixed setup moves use the common marginal formula`() {
        for (id in listOf("swordsdance", "nastyplot", "calmmind", "quiverdance", "bulkup", "irondefense", "agility", "shellsmash", "bellydrum")) {
            val details = BattleMoveCandidateView("normal", BattleMoveDamageCategory.STATUS, 0.0, 100.0, 0, 10,
                BattleMoveTargetPattern.SELF, effects = BattleShowdownMoveEffects.resolve(id))
            val option = BattlePublicMoveOptionView(id, details, BattlePublicMoveKnowledge.EXACT_OWN)
            val physical = id in setOf("swordsdance", "bulkup", "irondefense", "agility", "shellsmash", "bellydrum")
            val category = if (physical) BattleMoveDamageCategory.PHYSICAL else BattleMoveDamageCategory.SPECIAL
            val built = context(100, 120, allyPower = if (id == "agility") 300.0 else 65.0, foePower = 40.0,
                foeCategory = category, allyExtra = listOf(option))
            val context = built.copy(publicActionCatalog = BattlePublicActionCatalogView(built.publicActionCatalog.entries.map { entry ->
                if (entry.battlePokemonId != ALLY) entry else BattlePokemonActionCatalogView(ALLY, entry.moves.map {
                    if (it.moveId == "probe") it.copy(details = it.details.copy(damageCategory = category)) else it }, true) }))
            val action = BattleActionCandidate(id, BattleActionKind.USE_MOVE, 0, 1, id, moveDetails = details)
            val reading = LocalSetupGate.score(action, context, LocalMatchupScoreCalculator.calculate(context), 200.0)!!
            assertTrue(reading.pressure > 0.0 || reading.speed > 0.0, "$id: $reading")
            assertEquals(((reading.pressure + reading.speed) * reading.survival * reading.holds - reading.hpCost) *
                reading.effectChance, reading.value, 1e-9)
            if (id == "bellydrum") assertEquals(50.0, reading.hpCost, 1e-9)
        }
    }

    @Test
    fun `a setup prices remaining foes without a sweep multiplier`() {
        val context = context(allySpeed = 150, foeSpeed = 100, allyPower = 65.0, foePower = 40.0,
            allySetup = mapOf("atk" to 2), secondFoe = true)
        val action = setupAction(context)
        val reading = LocalSetupGate.score(action, context, LocalMatchupScoreCalculator.calculate(context), 200.0)!!
        val copied = LocalSetupGate.score(action, context, LocalMatchupScoreCalculator.calculate(context), 200.0, copyRisk = 0.5)!!
        assertEquals(reading.value * 0.5, copied.value, 1e-9)
        assertTrue(reading.value > 0.0, reading.toString())
    }

    @Test
    fun `an opponent locked into Outrage is expected to use it and cannot switch`() {
        val outrage = BattlePublicMoveOptionView("outrage", BattleMoveCandidateView(typeId = "dragon",
            damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 120.0, accuracy = 100.0, priority = 0, currentPp = 8),
            BattlePublicMoveKnowledge.PUBLICLY_REVEALED)
        fun intent(volatiles: Set<String>) = context(allySpeed = 100, foeSpeed = 120, allyPower = 65.0, foePower = 300.0,
            foeExtra = listOf(outrage), secondFoe = true).let { built ->
            val board = built.copy(state = built.state.copyState(pokemon = built.state.pokemon.map {
                if (it.battlePokemonId == FOE) it.copyState(knownVolatileEffectIds = volatiles) else it }))
            LocalOpponentIntentPredictor.predict(board, LocalMatchupScoreCalculator.calculate(board), scoredSwitches = true).single()
        }
        val free = intent(emptySet()).byAction()
        assertTrue((free["move:probe"] ?: 0.0) > 0.0) { free.toString() }
        val locked = intent(setOf("rampagepublic:outrage:1")).byAction()
        assertEquals(1.0, locked["move:outrage"] ?: 0.0, 1e-9) { locked.toString() }
        assertTrue(locked.keys.none { it.startsWith("switch:") }) { locked.toString() }
    }

    @Test
    fun `haze trades a turn for the boost and does not stop the setup`() {
        val context = context(allySpeed = 100, foeSpeed = 120, allyPower = 65.0, foePower = 65.0,
            allySetup = mapOf("atk" to 1, "spe" to 1), foeExtra = listOf(statusMove("haze", BattleMoveTargetPattern.ALL_ACTIVE)))
        val verdict = LocalSetupGate.evaluate(setupAction(context), context, LocalMatchupScoreCalculator.calculate(context))!!
        assertTrue(verdict.failures.none { it.startsWith("stopper:") }) { verdict.failures.toString() }
    }

    @Test
    fun `the ace score is the fresh-board sweep and does not move with the HP left`() {
        val full = context(allySpeed = 100, foeSpeed = 120, allyPower = 65.0, foePower = 65.0, allySetup = mapOf("atk" to 1, "spe" to 1))
        val worn = full.copy(state = full.state.copyState(pokemon = full.state.pokemon.map {
            if (it.battlePokemonId == ALLY) it.copyState(hpFraction = 0.3) else it
        }))
        val fresh = LocalAceScore.calculate(full)
        assertEquals(fresh.getValue(ALLY), LocalAceScore.calculate(worn).getValue(ALLY), 1e-9)
        assertTrue(LocalMatchupScoreCalculator.calculate(worn).sweeps.getValue(ALLY).score < fresh.getValue(ALLY))
    }

    @Test
    fun `a mechanic is kept for a better ace and costs the ace nothing`() {
        val context = context(allySpeed = 100, foeSpeed = 120)
        val tera = BattleActionCandidate("tera", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0, moveId = "probe",
            mechanic = BattleMechanicCandidate("tera", null, null))
        fun cost(ally: Double, bench: Double) =
            LocalGimmickReserve.adjustments(listOf(tera), context, mapOf(ALLY to ally, BENCH to bench))["tera"] ?: 0.0
        assertEquals(-LocalGimmickReserve.RESERVE_SCALE * 0.6, cost(ally = 0.2, bench = 0.8), 1e-9)
        assertEquals(0.0, cost(ally = 0.8, bench = 0.2), 1e-9)
    }

    private fun statusMove(id: String, pattern: BattleMoveTargetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT) =
        BattlePublicMoveOptionView(id, BattleMoveCandidateView(typeId = "normal", damageCategory = BattleMoveDamageCategory.STATUS,
            power = 0.0, accuracy = 100.0, priority = 0, currentPp = 8, targetPattern = pattern), BattlePublicMoveKnowledge.PUBLICLY_REVEALED)

    @Test
    fun `a haze user on the bench does not stop the setup until it is in front of it`() {
        val haze = BattlePublicMoveOptionView("haze", BattleMoveCandidateView(typeId = "ice",
            damageCategory = BattleMoveDamageCategory.STATUS, power = 0.0, accuracy = 100.0, priority = 0, currentPp = 8,
            targetPattern = BattleMoveTargetPattern.ALL_ACTIVE), BattlePublicMoveKnowledge.PUBLICLY_REVEALED)
        val context = context(allySpeed = 100, foeSpeed = 120, allyPower = 65.0, foePower = 65.0,
            allySetup = mapOf("atk" to 1, "spe" to 1), secondFoe = true, benchFoeExtra = listOf(haze))
        val verdict = LocalSetupGate.evaluate(setupAction(context), context, LocalMatchupScoreCalculator.calculate(context))!!
        assertTrue(verdict.failures.none { it.startsWith("stopper:") }) { verdict.failures.toString() }
    }

    @Test
    fun `an attack that raises its user's stats is not a setup move for the gate`() {
        val context = context(allySpeed = 100, foeSpeed = 120)
        val flameCharge = BattleActionCandidate("flamecharge", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 1, moveId = "flamecharge",
            moveDetails = BattleMoveCandidateView(typeId = "fire", damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 50.0,
                accuracy = 100.0, priority = 0, currentPp = 8, effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                    listOf(BattleMoveEffectView(BattleMoveEffectKind.STAT_STAGE, BattleMoveEffectTarget.USER, statStages = mapOf("spe" to 1))), false)))
        assertFalse(LocalSetupGate.raisesOwnStats(flameCharge))
        assertNull(LocalSetupGate.evaluate(flameCharge, context, LocalMatchupScoreCalculator.calculate(context)))
    }

    @Test
    fun `will-o-wisp is worth its turn against a physical attacker and not against a special one`() {
        val wisp = BattlePublicMoveOptionView("willowisp", BattleMoveCandidateView(typeId = "fire",
            damageCategory = BattleMoveDamageCategory.STATUS, power = 0.0, accuracy = 100.0, priority = 0, currentPp = 8,
            effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, listOf(BattleMoveEffectView(
                BattleMoveEffectKind.STATUS, BattleMoveEffectTarget.SELECTED_TARGET, valueId = "brn")), false)),
            BattlePublicMoveKnowledge.EXACT_OWN)
        // The faster foe three-hits the ally, which three-hits it back and loses the race. Burned, the physical
        // foe needs more hits than the ally has left even after the turn spent on the burn.
        fun burn(category: BattleMoveDamageCategory) = LocalMatchupScoreCalculator.calculate(context(allySpeed = 100, foeSpeed = 120,
            allyPower = 65.0, foePower = 65.0, foeCategory = category, allyExtra = listOf(wisp))).statusMoves(ALLY, FOE).single()
        val physical = burn(BattleMoveDamageCategory.PHYSICAL)
        val special = burn(BattleMoveDamageCategory.SPECIAL)
        assertEquals("willowisp", physical.moveId)
        assertTrue(physical.score > 0.0) { physical.toString() }
        assertTrue(special.score < 0.0) { special.toString() }
    }

    @Test
    fun `heals in a row that still ended lower make a losing loop`() {
        val recover = BattlePublicMoveOptionView("recover", BattleMoveCandidateView(typeId = "normal",
            damageCategory = BattleMoveDamageCategory.STATUS, power = 0.0, accuracy = 100.0, priority = 0, currentPp = 8,
            targetPattern = BattleMoveTargetPattern.SELF,
            effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, listOf(BattleMoveEffectView(
                BattleMoveEffectKind.HEAL_FRACTION, BattleMoveEffectTarget.USER,
                fractionRange = BattleFractionRange(0.5, 0.5))), false)),
            BattlePublicMoveKnowledge.EXACT_OWN)
        var sequence = 0L
        fun turn(turn: Int, move: String, net: Double) = listOf(
            BattleObservedEventView(sequence++, turn, BattleObservedEventKind.MOVE_USED, ALLY, listOf(ALLY), move),
            BattleObservedEventView(sequence++, turn, BattleObservedEventKind.HP_CHANGED, ALLY, hpFractionDelta = net))
        fun streak(vararg turns: List<BattleObservedEventView>): Int {
            val context = context(allySpeed = 100, foeSpeed = 100, allyExtra = listOf(recover), events = turns.flatMap { it }, turn = 4)
            return jbro.cobblemon.mcc.betterai.evaluation.LocalRecoveryLoop.failedStreak(ALLY, context)
        }
        // Turns 1-3: an attack, then two heals that still lost HP.
        assertEquals(2, streak(turn(1, "probe", -0.3), turn(2, "recover", -0.1), turn(3, "recover", -0.05)))
        // The heal that kept up breaks the run.
        assertEquals(0, streak(turn(2, "recover", -0.1), turn(3, "recover", 0.1)))
        assertEquals(0, streak(turn(3, "probe", -0.2)))
    }

    @Test
    fun `a slow climb on heals counts as losing and a real recovery does not`() {
        val roost = BattlePublicMoveOptionView("roost", BattleMoveCandidateView(typeId = "flying",
            damageCategory = BattleMoveDamageCategory.STATUS, power = 0.0, accuracy = 100.0, priority = 0, currentPp = 8,
            targetPattern = BattleMoveTargetPattern.SELF,
            effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, listOf(BattleMoveEffectView(
                BattleMoveEffectKind.HEAL_FRACTION, BattleMoveEffectTarget.USER,
                fractionRange = BattleFractionRange(0.5, 0.5))), false)),
            BattlePublicMoveKnowledge.EXACT_OWN)
        var sequence = 0L
        fun turn(turn: Int, net: Double) = listOf(
            BattleObservedEventView(sequence++, turn, BattleObservedEventKind.MOVE_USED, ALLY, listOf(ALLY), "roost"),
            BattleObservedEventView(sequence++, turn, BattleObservedEventKind.HP_CHANGED, ALLY, hpFractionDelta = net))
        fun streak(hpNow: Double, vararg turns: List<BattleObservedEventView>): Int {
            val context = context(allySpeed = 100, foeSpeed = 100, allyExtra = listOf(roost), events = turns.flatMap { it }, turn = 4)
            val hurt = context.copy(state = context.state.copyState(pokemon = context.state.pokemon.map {
                if (it.battlePokemonId == ALLY) it.copyState(hpFraction = hpNow) else it
            }))
            return jbro.cobblemon.mcc.betterai.evaluation.LocalRecoveryLoop.failedStreak(ALLY, hurt)
        }
        // 30% to 45% to 60% to 65%: a 50% heal that keeps under half of it every turn.
        assertEquals(3, streak(0.65, turn(1, 0.15), turn(2, 0.15), turn(3, 0.05)))
        // 45% to 90%: the heal stuck.
        assertEquals(0, streak(0.9, turn(3, 0.45)))
        // 90% to 100%: all it could restore, though only a tenth.
        assertEquals(0, streak(1.0, turn(3, 0.1)))
    }

    @Test
    fun `a wall that heals off the hit is never worn down by it`() {
        val recover = BattlePublicMoveOptionView("recover", BattleMoveCandidateView(typeId = "normal",
            damageCategory = BattleMoveDamageCategory.STATUS, power = 0.0, accuracy = 100.0, priority = 0, currentPp = 8,
            targetPattern = BattleMoveTargetPattern.SELF,
            effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, listOf(BattleMoveEffectView(
                BattleMoveEffectKind.HEAL_FRACTION, BattleMoveEffectTarget.USER,
                fractionRange = BattleFractionRange(0.5, 0.5))), false)),
            BattlePublicMoveKnowledge.PUBLICLY_REVEALED)
        fun matchup(power: Double, foeExtra: List<BattlePublicMoveOptionView>, recovery: Boolean = true) =
            LocalMatchupScoreCalculator.calculate(context(allySpeed = 120, foeSpeed = 100, allyPower = power, foePower = 40.0,
                foeExtra = foeExtra), recovery = recovery).pokemon(ALLY, FOE)!!
        // The shipped reading leaves healing out.
        assertEquals(matchup(40.0, emptyList()).winProbability,
            LocalMatchupScoreCalculator.calculate(context(allySpeed = 120, foeSpeed = 100, allyPower = 40.0, foePower = 40.0,
                foeExtra = listOf(recover))).pokemon(ALLY, FOE)!!.winProbability, 1e-9)
        // A weak attack wins the plain race by speed and loses to the heal.
        val plain = matchup(40.0, emptyList())
        val walled = matchup(40.0, listOf(recover))
        assertTrue(plain.winProbability > 0.5) { plain.toString() }
        assertTrue(walled.winProbability < 0.5) { walled.toString() }
        assertEquals(plain.winProbability, matchup(40.0, listOf(recover), recovery = false).winProbability, 1e-9)
        // A hit bigger than the heal still breaks it.
        val strong = matchup(300.0, listOf(recover))
        assertEquals(matchup(300.0, emptyList()).winProbability, strong.winProbability, 1e-9)
    }

    @Test
    fun `an opponent that moved before a faster ally reads at least that fast`() {
        // Its public range (100) could not have beaten 150 to the move: something unseen (a Scarf, a weather speed ability)
        // stands behind the order seen last turn.
        fun order(foeFirst: Boolean) = listOf(FOE, ALLY).let { if (foeFirst) it else it.reversed() }.mapIndexed { i, id ->
            BattleObservedEventView(i + 1L, 1, BattleObservedEventKind.ACTION_ORDER, id, publicValueId = "tackle", baseMovePriority = 0)
        }
        fun first(events: List<BattleObservedEventView>) = LocalMatchupScoreCalculator.calculate(context(allySpeed = 150, foeSpeed = 100,
            events = events, turn = 2)).pokemon(ALLY, FOE)!!.subjectMovesFirstProbability
        assertEquals(1.0, first(emptyList()), 1e-9)
        assertEquals(1.0, first(order(foeFirst = false)), 1e-9)
        assertEquals(0.0, first(order(foeFirst = true)), 1e-9)
        // Seen within Trick Room's five turns, the order may be the room's.
        val room = BattleObservedEventView(0, 1, BattleObservedEventKind.MOVE_USED, FOE, publicValueId = "trickroom")
        assertEquals(1.0, first(listOf(room) + order(foeFirst = true)), 1e-9)
    }

    @Test
    fun `a badly poisoned Pokemon the other cannot hurt still falls to the poison`() {
        // Neither attack does damage: a stand-off the faster foe takes, until it is badly poisoned and the poison alone
        // empties its bar.
        fun win(status: String?) = LocalMatchupScoreCalculator.calculate(context(allySpeed = 100, foeSpeed = 120,
            allyPower = 0.0, foePower = 0.0, foeStatus = status)).pokemon(ALLY, FOE)!!.winProbability
        val even = win(null)
        val poisoned = win("tox")
        assertTrue(poisoned > even + 0.3) { "$even $poisoned" }
    }

    @Test
    fun `a status move that only spends the turn is ruled out, in singles only`() {
        val wisp = BattlePublicMoveOptionView("willowisp", BattleMoveCandidateView(typeId = "fire",
            damageCategory = BattleMoveDamageCategory.STATUS, power = 0.0, accuracy = 100.0, priority = 0, currentPp = 8,
            effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, listOf(BattleMoveEffectView(
                BattleMoveEffectKind.STATUS, BattleMoveEffectTarget.SELECTED_TARGET, valueId = "brn")), false)),
            BattlePublicMoveKnowledge.EXACT_OWN)
        val action = BattleActionCandidate("wisp", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 2, moveId = "willowisp",
            moveDetails = wisp.details)
        fun wasted(category: BattleMoveDamageCategory, format: BattleFormat = BattleFormat.SINGLE): Boolean {
            val context = context(allySpeed = 100, foeSpeed = 120, allyPower = 65.0, foePower = 65.0, foeCategory = category,
                allyExtra = listOf(wisp), format = format)
            return LocalStatusMoveTriage.wasted(action, context, LocalMatchupScoreCalculator.calculate(context))
        }
        assertTrue(wasted(BattleMoveDamageCategory.SPECIAL))
        assertFalse(wasted(BattleMoveDamageCategory.PHYSICAL))
        // A doubles move without a declared target is left to the search.
        assertFalse(wasted(BattleMoveDamageCategory.SPECIAL, BattleFormat.DOUBLE))
    }

    @Test
    fun `a doubles burn counts what it does for the partner and a special target still wastes it`() {
        val action = BattleActionCandidate("wisp", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 2, moveId = "willowisp",
            targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)), moveDetails = wisp().details)
        val partnerAttack = BattleActionCandidate("partner", BattleActionKind.USE_MOVE, actorSlot = 1, moveSlot = 0, moveId = "probe")
        val turn = BattleActionCandidate("turn", BattleActionKind.COMPOSITE, componentActionIds = listOf("wisp", "partner"),
            componentActions = listOf(action, partnerAttack))
        fun judged(category: BattleMoveDamageCategory): Pair<StatusMoveMatchupScore, Boolean> {
            val context = context(allySpeed = 100, foeSpeed = 120, allyPower = 65.0, foePower = 65.0, foeCategory = category,
                allyExtra = listOf(wisp()), format = BattleFormat.DOUBLE, benchActive = true)
            val scores = LocalMatchupScoreCalculator.calculate(context)
            return scores.statusMoves(ALLY, FOE).single { it.moveId == "willowisp" } to LocalStatusMoveTriage.wasted(turn, context, scores)
        }
        val (physical, physicalWasted) = judged(BattleMoveDamageCategory.PHYSICAL)
        assertTrue(physical.partnerGain > 0.0) { physical.toString() }
        assertFalse(physicalWasted)
        val (special, specialWasted) = judged(BattleMoveDamageCategory.SPECIAL)
        // On a special attacker only the burn's end-of-turn sixteenth is left of it (2026-10-20): far under the physical case.
        assertTrue(special.partnerGain < physical.partnerGain / 2.0) { "$special vs $physical" }
        assertTrue(specialWasted) { special.toString() }
    }

    @Test
    fun `a burn on a target that already has a status publicly fails and is ruled out`() {
        val action = BattleActionCandidate("wisp", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 2, moveId = "willowisp",
            moveDetails = wisp().details)
        val attack = attackAction()
        fun fails(status: String?) = context(allySpeed = 100, foeSpeed = 120, allyExtra = listOf(wisp()), foeStatus = status)
            .let { LocalPublicFailureTriage.fails(action, it) to LocalPublicFailureTriage.fails(attack, it) }
        assertEquals(true to false, fails("par"))
        assertEquals(false to false, fails(null))
    }

    @Test
    fun `a doubles spread move is scored against both opponents it hits`() {
        val slide = BattlePublicMoveOptionView("rockslide", BattleMoveCandidateView(typeId = "rock",
            damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 75.0, accuracy = 100.0, priority = 0, currentPp = 8,
            targetPattern = BattleMoveTargetPattern.ALL_OPPONENTS), BattlePublicMoveKnowledge.EXACT_OWN)
        val scores = LocalMatchupScoreCalculator.calculate(context(allySpeed = 100, foeSpeed = 120, allyExtra = listOf(slide),
            format = BattleFormat.DOUBLE, secondFoe = true, secondFoeActive = true))
        val primary = scores.moves(ALLY, FOE).single { it.moveId == "rockslide" }
        val secondary = scores.moves(ALLY, FOE2).single { it.moveId == "rockslide" }
        assertEquals(primary.maximumDamageFraction, secondary.maximumDamageFraction, 0.01)
    }

    @Test
    fun `the predicted opponent attacks when it wins and protects when it is about to fall`() {
        val protect = BattlePublicMoveOptionView("protect", BattleMoveCandidateView(typeId = "normal",
            damageCategory = BattleMoveDamageCategory.STATUS, power = 0.0, accuracy = 100.0, priority = 4, currentPp = 8,
            targetPattern = BattleMoveTargetPattern.SELF, effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                listOf(BattleMoveEffectView(BattleMoveEffectKind.PROTECT_USER, BattleMoveEffectTarget.USER)), false)),
            BattlePublicMoveKnowledge.PUBLICLY_REVEALED)
        fun top(allyPower: Double, foePower: Double, events: List<BattleObservedEventView> = emptyList()): String? {
            val context = context(allySpeed = 100, foeSpeed = 120, allyPower = allyPower, foePower = foePower,
                foeExtra = listOf(protect), events = events)
            return LocalOpponentIntentPredictor.predict(context, LocalMatchupScoreCalculator.calculate(context))
                .single().options.first().moveId
        }
        assertEquals("probe", top(allyPower = 40.0, foePower = 300.0))
        assertEquals("protect", top(allyPower = 300.0, foePower = 40.0))
        // A second Protect in a row mostly fails.
        val protectedBefore = listOf(BattleObservedEventView(1, 0, BattleObservedEventKind.MOVE_USED, FOE, emptyList(), "protect"))
        assertEquals("probe", top(allyPower = 300.0, foePower = 40.0, events = protectedBefore))
    }

    private fun wisp() = BattlePublicMoveOptionView("willowisp", BattleMoveCandidateView(typeId = "fire",
        damageCategory = BattleMoveDamageCategory.STATUS, power = 0.0, accuracy = 100.0, priority = 0, currentPp = 8,
        targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
        effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, listOf(BattleMoveEffectView(
            BattleMoveEffectKind.STATUS, BattleMoveEffectTarget.SELECTED_TARGET, valueId = "brn")), false)),
        BattlePublicMoveKnowledge.EXACT_OWN)

    @Test
    fun `parting shot declares the stat drop its callback applies`() {
        val parsed = BattleDeclarativeMoveEffects.parse(
            "const Moves = { partingshot: { num: 575, category: \"Status\", target: \"normal\", selfSwitch: true, " +
                "onHit(target, source, move) { const success = this.boost({ atk: -1, spa: -1 }, target, source); } } };",
        ).getValue("partingshot").effects
        assertTrue(parsed.any { it.kind == BattleMoveEffectKind.SWITCH_USER })
        val drop = parsed.single { it.kind == BattleMoveEffectKind.STAT_STAGE }
        assertEquals(BattleMoveEffectTarget.SELECTED_TARGET, drop.target)
        assertEquals(mapOf("atk" to -1, "spa" to -1), drop.statStages)
    }

    @Test
    fun `rest declares the full heal and the sleep its callback applies`() {
        val parsed = BattleDeclarativeMoveEffects.parse(
            "const Moves = { rest: { num: 156, category: \"Status\", target: \"self\", " +
                "onHit(target, source, move) { const result = target.setStatus('slp', source, move); this.heal(target.maxhp); } } };",
        ).getValue("rest").effects
        val heal = parsed.single { it.kind == BattleMoveEffectKind.HEAL_FRACTION }
        assertEquals(BattleMoveEffectTarget.USER, heal.target)
        assertEquals(1.0, heal.fractionRange?.minimum)
        val sleep = parsed.single { it.kind == BattleMoveEffectKind.STATUS }
        assertEquals(BattleMoveEffectTarget.USER, sleep.target)
        assertEquals("slp", sleep.valueId)
    }

    @Test
    fun `status moves are scored in doubles too`() {
        val snarl = BattlePublicMoveOptionView("partingshot", BattleMoveCandidateView(typeId = "dark",
            damageCategory = BattleMoveDamageCategory.STATUS, power = 0.0, accuracy = 100.0, priority = 0, currentPp = 8,
            effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, listOf(BattleMoveEffectView(
                BattleMoveEffectKind.STAT_STAGE, BattleMoveEffectTarget.SELECTED_TARGET, statStages = mapOf("atk" to -1, "spa" to -1))), false)),
            BattlePublicMoveKnowledge.EXACT_OWN)
        val scores = LocalMatchupScoreCalculator.calculate(context(allySpeed = 100, foeSpeed = 120, allyPower = 65.0,
            foePower = 65.0, allyExtra = listOf(snarl), format = BattleFormat.DOUBLE))
        assertEquals(listOf("partingshot"), scores.statusMoves(ALLY, FOE).map { it.moveId })
    }

    @Test
    fun `a switch-in takes the hazards and the hit meant for the Pokemon it replaces`() {
        val scores = LocalMatchupScoreCalculator.calculate(context(allySpeed = 150, foeSpeed = 100, foePower = 65.0,
            rocksOnAllySide = true))
        val switchIn = scores.switchIn(BENCH, FOE, ALLY)!!
        assertEquals("probe", switchIn.predictedMoveId)
        assertEquals(1.0, switchIn.predictedSurvival, 1e-9)
        assertTrue(switchIn.hpAfterEntry < 0.875 - 0.3) { switchIn.toString() }
        assertNotNull(switchIn.afterEntry)
    }

    @Test
    fun `the only answer to an opponent is the one worth preserving`() {
        val scores = LocalMatchupScoreCalculator.calculate(context(allySpeed = 150, foeSpeed = 100, benchPower = 40.0))
        val sole = scores.preserves.getValue(ALLY)
        assertEquals(listOf(FOE), sole.soleAnswerTo)
        assertEquals(1.0, sole.score, 1e-9)
        assertEquals(0.0, scores.preserves.getValue(BENCH).score, 1e-9)
    }

    @Test
    fun `an immune top move keeps the other predicted switch entry risks`() {
        val context = electricEntryContext()
        val scores = LocalMatchupScoreCalculator.calculate(context)
        val entry = scores.switchIn(BENCH, FOE, ALLY)!!
        assertEquals("thunderbolt", entry.predictedMoveId)
        assertEquals(1.0, entry.outcomes.getValue("thunderbolt").survival)
        assertEquals(0.1, entry.outcomes.getValue("dracometeor").survival, 1e-9)
        assertTrue(entry.predictedSurvival < 0.95, entry.toString())
        val intent = jbro.cobblemon.mcc.betterai.matchup.OpponentIntent(FOE, 0, listOf(
            jbro.cobblemon.mcc.betterai.matchup.IntentOption(jbro.cobblemon.mcc.betterai.matchup.IntentKind.ATTACK,
                "thunderbolt", ALLY, null, 1.0, 0.6),
            jbro.cobblemon.mcc.betterai.matchup.IntentOption(jbro.cobblemon.mcc.betterai.matchup.IntentKind.ATTACK,
                "dracometeor", ALLY, null, 0.9, 0.4)))
        val weighted = scores.withSwitchIntents(listOf(intent)).switchIn(BENCH, FOE, ALLY)!!
        assertEquals(0.64, weighted.predictedSurvival, 1e-9)
        assertEquals(0.6 * entry.outcomes.getValue("thunderbolt").score +
            0.4 * entry.outcomes.getValue("dracometeor").score, weighted.score, 1e-9)
        assertSame(entry.outcomes, weighted.outcomes, "changing intent probabilities must reuse the calculated outcomes")
        assertEquals(scores.pokemon(ALLY, FOE), scores.withSwitchIntents(listOf(intent)).pokemon(ALLY, FOE))
    }

    @Test
    fun `switch entry conditions out foe switching once and retains a no hit move choice`() {
        val context = electricEntryContext()
        val scores = LocalMatchupScoreCalculator.calculate(context)
        val intent = jbro.cobblemon.mcc.betterai.matchup.OpponentIntent(FOE, 0, listOf(
            jbro.cobblemon.mcc.betterai.matchup.IntentOption(jbro.cobblemon.mcc.betterai.matchup.IntentKind.ATTACK,
                "thunderbolt", ALLY, null, 1.0, 0.3),
            jbro.cobblemon.mcc.betterai.matchup.IntentOption(jbro.cobblemon.mcc.betterai.matchup.IntentKind.ATTACK,
                "dracometeor", ALLY, null, 0.9, 0.2),
            jbro.cobblemon.mcc.betterai.matchup.IntentOption(jbro.cobblemon.mcc.betterai.matchup.IntentKind.PROTECT,
                "protect", null, null, 0.0, 0.25),
            jbro.cobblemon.mcc.betterai.matchup.IntentOption(jbro.cobblemon.mcc.betterai.matchup.IntentKind.SWITCH,
                null, null, FOE2, 0.0, 0.25)))
        val weighted = scores.withSwitchIntents(listOf(intent)).switchIn(BENCH, FOE, ALLY)!!
        assertEquals(0.76, weighted.predictedSurvival, 1e-9)
        jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals.begin()
        try {
            LocalSwitchRules.judge(listOf(attackAction(), switchAction()), context, scores,
                foeSwitchChances = mapOf(FOE to 0.25), opponentIntents = listOf(intent))
            val internals = jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals.take()
            assertEquals(0.82, internals.getValue("switch:switch").getValue("survival"), 1e-9)
            val branches = internals.filterKeys { it.startsWith("switchHit:switch:") }.values
            assertEquals(1.0, branches.sumOf { it.getValue("probability") }, 1e-9)
            assertEquals(0.76, branches.sumOf { it.getValue("probability") * it.getValue("survival") }, 1e-9)
        } finally {
            jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals.take()
        }
    }

    private fun electricEntryContext(): BattleDecisionContext {
        val base = context(allySpeed = 80, foeSpeed = 120, allyPower = 40.0)
        val state = base.state.derive(pokemon = base.state.pokemon.map {
            when (it.battlePokemonId) {
                ALLY -> it.copyState(knownTypeIds = setOf("water", "flying"), knownBaseStabTypeIds = setOf("water", "flying"))
                BENCH -> it.copyState(knownTypeIds = setOf("electric", "fighting"),
                    knownBaseStabTypeIds = setOf("electric", "fighting"), knownAbilityId = "voltabsorb")
                else -> it
            }
        })
        fun move(id: String, type: String, power: Double, accuracy: Double) = BattlePublicMoveOptionView(id,
            BattleMoveCandidateView(typeId = type, damageCategory = BattleMoveDamageCategory.SPECIAL,
                power = power, accuracy = accuracy, priority = 0, currentPp = 8), BattlePublicMoveKnowledge.PUBLICLY_REVEALED)
        return base.copy(state = state, publicActionCatalog = BattlePublicActionCatalogView(
            base.publicActionCatalog.entries.map { entry -> if (entry.battlePokemonId != FOE) entry else
                BattlePokemonActionCatalogView(FOE, listOf(move("thunderbolt", "electric", 300.0, 100.0),
                    move("dracometeor", "dragon", 600.0, 90.0)), moveSetComplete = true) }))
    }

    @Test
    fun `free entry opportunity cost is charged once inside the switch gain`() {
        val base = context(allySpeed = 80, foeSpeed = 150, allyPower = 40.0, benchPower = 300.0)
        val c = base.copy(state = base.state.derive(pokemon = base.state.pokemon.map {
            when (it.battlePokemonId) {
                ALLY -> it.copyState(hpFraction = 0.05)
                BENCH -> it.copyState(combatStats = it.combatStats!!.copy(speed = BattleIntegerRange(200, 200)))
                else -> it
            }
        }))
        val scores = LocalMatchupScoreCalculator.calculate(c)
        jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals.begin()
        try {
            val judgement = LocalSwitchRules.judge(listOf(attackAction(), switchAction()), c, scores)
            val values = jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals.take().getValue("switch:switch")
            assertTrue(values.getValue("gain") < 0.0)
            assertTrue((scores.preserves[BENCH]?.score ?: 0.0) >= LocalSwitchRules.WORTH_KEEPING)
            assertEquals(values.getValue("gain") * 100,
                (judgement.adjustments["switch"] ?: 0.0) + (judgement.uncreditedGains["switch"] ?: 0.0), 1e-9)
        } finally { jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals.clear() }
    }

    @Test
    fun `switch entry carries integer absorb healing into the next exchange and internals`() {
        val base = electricEntryContext()
        val state = base.state.derive(pokemon = base.state.pokemon.map {
            if (it.battlePokemonId != BENCH) it else it.copyState(hpFraction = 45.0 / 146,
                combatStats = it.combatStats!!.copy(maxHp = BattleIntegerRange(146, 146)))
        })
        val c = base.copy(state = state)
        val scores = LocalMatchupScoreCalculator.calculate(c)
        val entry = scores.switchIn(BENCH, FOE, ALLY)!!
        val hit = entry.outcomes.getValue("thunderbolt")
        assertEquals(81.0 / 146, hit.hpAfterEntry, 1e-12)
        assertEquals(36.0 / 146, hit.absorptionHealing, 1e-12)
        assertTrue(hit.absorptionActivated)
        assertEquals(1.0, hit.survival)
        assertNotEquals(entry.outcomes.getValue(null).afterEntry, hit.afterEntry)
        jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals.begin()
        try {
            LocalSwitchRules.judge(listOf(attackAction(), BattleActionCandidate("switch", BattleActionKind.SWITCH,
                actorSlot = 0, switchPokemonId = BENCH)), c, scores)
            val recorded = jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals.take().filterKeys {
                it.startsWith("switchHit:") && it.endsWith(":thunderbolt")
            }.values.single()
            assertEquals(36.0 / 146, recorded.getValue("absorptionHealing"), 1e-12)
            assertEquals(81.0 / 146, recorded.getValue("hpAfterHit"), 1e-12)
        } finally { jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals.clear() }
    }

    @Test
    fun `all TryHit absorption reactions share suppression healblock and base HP rules`() {
        val base = electricEntryContext()
        val actor = base.state.pokemon.first { it.battlePokemonId == FOE }
        val bench = base.state.pokemon.first { it.battlePokemonId == BENCH }
        fun reaction(ability: String, type: String, hp: Double = 45.0 / 146,
            volatiles: Set<String> = emptySet(), actorAbility: String? = null, item: String? = null): BattleStateView? {
            val target = bench.copyState(activeSlot = 0, hpFraction = hp, knownAbilityId = ability,
                knownHeldItemId = item, knownVolatileEffectIds = volatiles,
                combatStats = bench.combatStats!!.copy(maxHp = BattleIntegerRange(146, 146)))
            val source = actor.copyState(knownAbilityId = actorAbility)
            val state = base.state.derive(pokemon = listOf(source, target))
            val move = BattleActionCandidate("absorb", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0, moveId = "probe",
                moveDetails = BattleMoveCandidateView(typeId = type, damageCategory = BattleMoveDamageCategory.SPECIAL,
                    power = 100.0, accuracy = 1.0, priority = 0, currentPp = 10,
                    effects = if (ability == "windrider") BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                        emptyList(), false, mechanicFlags = setOf("wind")) else null))
            return jbro.cobblemon.mcc.betterai.mechanics.LocalMoveAbsorption.apply(state, move, source, target)
        }
        fun target(state: BattleStateView?) = state!!.pokemon.first { it.battlePokemonId == BENCH }
        for ((ability, type) in listOf("voltabsorb" to "electric", "waterabsorb" to "water",
            "dryskin" to "water", "eartheater" to "ground")) {
            assertEquals(81.0 / 146, target(reaction(ability, type)).hpFraction, 1e-12, ability)
            assertEquals(1.0, target(reaction(ability, type, hp = 140.0 / 146)).hpFraction, ability)
            assertEquals(45.0 / 146, target(reaction(ability, type, volatiles = setOf("healblock"))).hpFraction, ability)
            assertEquals(126.0 / 292, target(reaction(ability, type, volatiles = setOf("dynamax"))).hpFraction, 1e-12, ability)
            assertNull(reaction(ability, type, actorAbility = "moldbreaker"), ability)
            assertNull(reaction(ability, type, volatiles = setOf("gastroacid")), ability)
            assertNotNull(reaction(ability, type, actorAbility = "moldbreaker", item = "abilityshield"), ability)
            assertNull(reaction(ability, "normal"), ability)
        }
        for ((ability, type, stages) in listOf(
            Triple("sapsipper", "grass", mapOf("attack" to 1)),
            Triple("windrider", "flying", mapOf("attack" to 1)),
            Triple("motordrive", "electric", mapOf("speed" to 1)),
            Triple("lightningrod", "electric", mapOf("special_attack" to 1)),
            Triple("stormdrain", "water", mapOf("special_attack" to 1)),
            Triple("wellbakedbody", "fire", mapOf("defence" to 2)))) {
            assertEquals(stages, target(reaction(ability, type)).statStages, ability)
            assertNull(reaction(ability, type, actorAbility = "moldbreaker"), ability)
        }
        assertTrue("flashfire" in target(reaction("flashfire", "fire")).knownVolatileEffectIds)
        assertNull(reaction("levitate", "ground"))
    }

    @Test
    fun `switch entry does not reuse an unboosted exchange for an absorbing stat boost`() {
        val base = electricEntryContext()
        val c = base.copy(state = base.state.derive(pokemon = base.state.pokemon.map {
            if (it.battlePokemonId != BENCH) it else it.copyState(knownAbilityId = "lightningrod")
        }), publicActionCatalog = BattlePublicActionCatalogView(base.publicActionCatalog.entries.map {
            if (it.battlePokemonId != BENCH) it else BattlePokemonActionCatalogView(BENCH,
                listOf(BattlePublicMoveOptionView("watergun", BattleMoveCandidateView(typeId = "water",
                    damageCategory = BattleMoveDamageCategory.SPECIAL, power = 80.0, accuracy = 100.0,
                    priority = 0, currentPp = 20), BattlePublicMoveKnowledge.PUBLICLY_REVEALED)), moveSetComplete = true)
        }))
        val entry = LocalMatchupScoreCalculator.calculate(c).switchIn(BENCH, FOE, ALLY)!!
        assertEquals(mapOf("special_attack" to 1), entry.outcomes.getValue("thunderbolt").absorptionStages)
        assertTrue(entry.outcomes.getValue("thunderbolt").absorptionActivated)
        assertEquals(0.0, entry.outcomes.getValue("thunderbolt").absorptionHealing)
        assertNotEquals(entry.outcomes.getValue(null).afterEntry, entry.outcomes.getValue("thunderbolt").afterEntry)
    }

    @Test
    fun `a switch-in that the predicted hit knocks out is ruled out`() {
        // The faster ally knocks the foe out first; the foe knocks out anything that walks in.
        val context = context(allySpeed = 150, foeSpeed = 100)
        val judgement = LocalSwitchRules.judge(listOf(attackAction(), switchAction()), context, LocalMatchupScoreCalculator.calculate(context))
        assertEquals(LocalSwitchRules.SWITCH_IN_DIES, judgement.exclusions["switch"])
    }

    @Test
    fun `a pivot that goes first into a hit nobody behind it survives is ruled out`() {
        val uTurn = BattleActionCandidate("uturn", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 2, moveId = "uturn",
            moveDetails = BattleMoveCandidateView(typeId = "bug", damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 70.0,
                accuracy = 100.0, priority = 0, currentPp = 8, effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                    listOf(BattleMoveEffectView(BattleMoveEffectKind.SWITCH_USER, BattleMoveEffectTarget.USER)), false)))
        fun exclusion(allySpeed: Int, foeSpeed: Int): String? {
            val context = context(allySpeed = allySpeed, foeSpeed = foeSpeed)
            return LocalSwitchRules.judge(listOf(attackAction(), uTurn), context, LocalMatchupScoreCalculator.calculate(context))
                .exclusions["uturn"]
        }
        // Faster, the U-turn brings the bench in under the foe's knockout.
        assertEquals(LocalSwitchRules.PIVOT_SWITCH_IN_DIES, exclusion(allySpeed = 150, foeSpeed = 100))
        // Slower, the foe has already hit the user and the bench walks in free.
        assertNull(exclusion(allySpeed = 50, foeSpeed = 100))
    }

    @Test
    fun `a switch that wins what staying loses is credited by the difference`() {
        // Staying, the weak ally loses a race it moves first in; the bench's one-hit knockout wins it after the hit.
        val context = context(allySpeed = 150, foeSpeed = 100, allyPower = 40.0, benchPower = 300.0, foePower = 65.0)
        val scores = LocalMatchupScoreCalculator.calculate(context)
        val judgement = LocalSwitchRules.judge(listOf(attackAction(), switchAction()), context, scores)
        val gain = scores.switchIn(BENCH, FOE, ALLY)!!.score - scores.pokemon(ALLY, FOE)!!.score
        assertTrue(gain >= LocalSwitchRules.SWITCH_MARGIN) { "gain $gain" }
        assertEquals(gain * LocalSwitchRules.SCORE_SCALE, judgement.adjustments.getValue("switch"), 1e-6)
        assertNull(judgement.exclusions["switch"])
    }

    @Test
    fun `a worthless Pokemon may take the knockout meant for the only answer`() {
        // Only the ally beats the benched foe; the active foe is about to knock it out. The bench beats nobody.
        val context = context(allySpeed = 100, foeSpeed = 150, allyPower = 300.0, benchPower = 40.0, foePower = 300.0,
            secondFoe = true)
        val scores = LocalMatchupScoreCalculator.calculate(context)
        assertEquals(listOf(FOE2), scores.preserves.getValue(ALLY).soleAnswerTo)
        assertEquals(0.0, scores.preserves.getValue(BENCH).score, 1e-9)
        val judgement = LocalSwitchRules.judge(listOf(attackAction(), switchAction()), context, scores)
        assertNull(judgement.exclusions["switch"]) { "the sacrifice dies on entry by design" }
        assertTrue(judgement.adjustments.getValue("switch") > 0.0)
    }

    @Test
    fun `a slow pivot brings the bench in free and is credited above the hard switch`() {
        // The slower weak ally loses staying. After the foe's hit on it, the bench walks in without one and wins.
        val context = context(allySpeed = 100, foeSpeed = 150, allyPower = 40.0, benchPower = 300.0, foePower = 65.0,
            allyExtra = listOf(uturn()))
        val scores = LocalMatchupScoreCalculator.calculate(context)
        val judgement = LocalSwitchRules.judge(listOf(pivotAction(context), switchAction()), context, scores)
        val free = scores.pokemon(BENCH, FOE)!!.score - scores.pokemon(ALLY, FOE)!!.score
        assertEquals(free * LocalSwitchRules.SCORE_SCALE, judgement.adjustments.getValue("pivot"), 1e-6)
        assertTrue(judgement.adjustments.getValue("pivot") > (judgement.adjustments["switch"] ?: 0.0)) { judgement.toString() }
    }

    @Test
    fun `a mid-turn replacement is never ruled out for the hit it would take`() {
        // A pivot's replacement: the leaving ally still stands, and the foe knocks out anything that walks in.
        for (foeMoved in listOf(true, false)) {
            val context = context(allySpeed = 150, foeSpeed = 100, foeMovedThisTurn = foeMoved)
            val judgement = LocalSwitchRules.judge(listOf(switchAction()), context, LocalMatchupScoreCalculator.calculate(context))
            assertTrue(judgement.exclusions.isEmpty()) { "foe moved $foeMoved: $judgement" }
        }
    }

    private fun uturn() = BattlePublicMoveOptionView("uturn", BattleMoveCandidateView(typeId = "bug",
        damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 20.0, accuracy = 100.0, priority = 0, currentPp = 8,
        effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, listOf(BattleMoveEffectView(
            BattleMoveEffectKind.SWITCH_USER, BattleMoveEffectTarget.USER)), false)), BattlePublicMoveKnowledge.EXACT_OWN)

    private fun pivotAction(context: BattleDecisionContext): BattleActionCandidate {
        val option = context.publicActionCatalog.forPokemon(ALLY).first { it.moveId == "uturn" }
        return BattleActionCandidate("pivot", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 2, moveId = option.moveId, moveDetails = option.details)
    }

    private fun attackAction() = BattleActionCandidate("attack", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0, moveId = "probe")

    private fun switchAction() = BattleActionCandidate("switch", BattleActionKind.SWITCH, actorSlot = 0, switchPokemonId = BENCH)

    private fun setupAction(context: BattleDecisionContext): BattleActionCandidate {
        val option = context.publicActionCatalog.forPokemon(ALLY).first { it.details.damageCategory == BattleMoveDamageCategory.STATUS }
        return BattleActionCandidate("setup", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 1, moveId = option.moveId, moveDetails = option.details)
    }

    @Test
    fun `entry hazards kill before an attack and cannot be avoided by an opponent switching`() {
        val original = context(150, 100, allyPower = 65.0, foePower = 40.0, rocksOnAllySide = true)
        val board = original.state.derive(pokemon = original.state.pokemon.map {
            when (it.battlePokemonId) {
                ALLY -> it.copyState(hpFraction = 0.5)
                BENCH -> it.copyState(hpFraction = 0.04)
                else -> it
            }
        })
        val attack = BattleActionCandidate("move 1", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
            moveId = "probe", moveDetails = original.publicActionCatalog.forPokemon(ALLY).first().details)
        val switch = BattleActionCandidate("switch 2", BattleActionKind.SWITCH, actorSlot = 0, switchPokemonId = BENCH)
        val c = original.copy(state = board, candidates = listOf(attack, switch))
        val scores = LocalMatchupScoreCalculator.calculate(c)
        val entry = scores.switchIn(BENCH, FOE, ALLY)!!
        assertTrue(entry.entryFaints)
        assertEquals(0.0, entry.predictedSurvival)
        assertEquals(0.0, entry.hpAfterEntry)
        assertEquals(-1.0, entry.score)
        val originalScore = scores.pokemon(ALLY, FOE)!!.score
        val originalStanding = jbro.cobblemon.mcc.betterai.matchup.LocalKnockoutWorth.standings(c.state, scores).getValue(ALLY)
        for (chance in listOf(0.0, 1.0)) {
            jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals.begin()
            try {
                LocalSwitchRules.judge(c.candidates, c, scores, foeSwitchChances = mapOf(FOE to chance))
                val internals = jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals.take().getValue("switch:switch 2")
                assertEquals(0.0, internals.getValue("survival"))
                assertEquals(1.0, internals.getValue("returns"))
                assertEquals(0.5 - 18.0 / 150.0, internals.getValue("keptHp"), 1e-9)
                assertEquals(18.0 / 150.0, internals.getValue("reentryHpLoss"), 1e-9)
                assertEquals(12.0, internals.getValue("reentryCost"), 1e-9)
                assertEquals(originalScore, internals.getValue("freeAfterIncoming"), 1e-9)
                assertEquals(originalStanding, internals.getValue("standingKept"), 1e-9)
                assertEquals(internals.getValue("switchGoesOn") - internals.getValue("stayGoesOn") + internals.getValue("kept") - 18.0 / 150.0,
                    internals.getValue("gain"), 1e-9)
            } finally {
                jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals.clear()
            }
        }
    }

    @Test
    fun `reentry HP cost respects boots and cannot exceed the saved Pokemon remaining HP`() {
        for ((hp, item, loss) in listOf(Triple(0.5, "heavydutyboots", 0.0), Triple(0.05, null, 0.05))) {
            val original = context(150, 100, allyPower = 65.0, foePower = 40.0, rocksOnAllySide = true, allyItem = item)
            val c = original.copy(state = original.state.derive(pokemon = original.state.pokemon.map {
                when (it.battlePokemonId) {
                    ALLY -> it.copyState(hpFraction = hp)
                    BENCH -> it.copyState(hpFraction = 0.04)
                    else -> it
                }
            }), candidates = listOf(attackAction(), switchAction()))
            val scores = LocalMatchupScoreCalculator.calculate(c)
            jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals.begin()
            try {
                LocalSwitchRules.judge(c.candidates, c, scores)
                val internals = jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals.take().getValue("switch:switch")
                assertEquals(1.0, internals.getValue("entryFaints"))
                assertEquals(loss, internals.getValue("reentryHpLoss"), 1e-9)
                assertEquals(hp - loss, internals.getValue("keptHp"), 1e-9)
            } finally {
                jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals.clear()
            }
        }
    }

    @Test
    fun `a faster heal blocking hit scales only the heal by its probability and accuracy`() {
        val (c, heal) = healingContext(100, 150, accuracy = 80.0)
        val scores = LocalMatchupScoreCalculator.calculate(c)
        val baseline = jbro.cobblemon.mcc.betterai.matchup.LocalHealValue.values(listOf(heal), c, scores).getValue(heal.actionId)
        assertTrue(baseline > 0.0)
        val intent = healingIntent(0.4)
        val blocked = jbro.cobblemon.mcc.betterai.matchup.LocalHealValue.values(listOf(heal), c, scores, intent).getValue(heal.actionId)
        assertEquals(baseline * (1.0 - 0.4 * 0.8), blocked, 1e-7)
    }

    @Test
    fun `a heal before the blocking hit keeps its return`() {
        val (c, heal) = healingContext(150, 100)
        val scores = LocalMatchupScoreCalculator.calculate(c)
        val baseline = jbro.cobblemon.mcc.betterai.matchup.LocalHealValue.values(listOf(heal), c, scores).getValue(heal.actionId)
        assertEquals(baseline, jbro.cobblemon.mcc.betterai.matchup.LocalHealValue.values(listOf(heal), c, scores, healingIntent(1.0)).getValue(heal.actionId), 1e-9)
    }

    @Test
    fun `an existing heal block removes the heal return`() {
        val (base, heal) = healingContext(150, 100)
        val c = base.copy(state = base.state.derive(pokemon = base.state.pokemon.map {
            if (it.battlePokemonId == ALLY) it.copyState(knownVolatileEffectIds = setOf("healblock", "healblockturns:1")) else it
        }))
        assertEquals(0.0, jbro.cobblemon.mcc.betterai.matchup.LocalHealValue.values(listOf(heal), c,
            LocalMatchupScoreCalculator.calculate(c)).getValue(heal.actionId))
    }

    @Test
    fun `a sound immunity or secondary shield prevents psychic noise from blocking recovery`() {
        for ((ability, item) in listOf("soundproof" to null, "shielddust" to null, null to "covertcloak")) {
            val (base, heal) = healingContext(100, 150)
            val c = base.copy(state = base.state.derive(pokemon = base.state.pokemon.map {
                if (it.battlePokemonId == ALLY) it.copyState(knownAbilityId = ability, knownHeldItemId = item) else it
            }))
            val scores = LocalMatchupScoreCalculator.calculate(c)
            val baseline = jbro.cobblemon.mcc.betterai.matchup.LocalHealValue.values(listOf(heal), c, scores).getValue(heal.actionId)
            assertEquals(baseline, jbro.cobblemon.mcc.betterai.matchup.LocalHealValue.values(listOf(heal), c, scores, healingIntent(1.0)).getValue(heal.actionId), 1e-9)
        }
    }

    private fun healingIntent(chance: Double) = listOf(jbro.cobblemon.mcc.betterai.matchup.OpponentIntent(FOE, 0,
        listOf(jbro.cobblemon.mcc.betterai.matchup.IntentOption(jbro.cobblemon.mcc.betterai.matchup.IntentKind.ATTACK,
            "psychicnoise", ALLY, null, 0.0, chance))))

    private fun healingContext(allySpeed: Int, foeSpeed: Int, accuracy: Double = 100.0): Pair<BattleDecisionContext, BattleActionCandidate> {
        val noise = BattlePublicMoveOptionView("psychicnoise", BattleMoveCandidateView(typeId = "psychic",
            damageCategory = BattleMoveDamageCategory.SPECIAL, power = 30.0, accuracy = accuracy, priority = 0, currentPp = 10,
            effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                listOf(BattleMoveEffectView(BattleMoveEffectKind.VOLATILE_STATUS, BattleMoveEffectTarget.SELECTED_TARGET,
                    probability = 1.0, valueId = "healblock")), false, mechanicFlags = setOf("sound", "bypasssub"))),
            BattlePublicMoveKnowledge.PUBLICLY_REVEALED)
        val heal = BattleActionCandidate("heal", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0, moveId = "recover",
            moveDetails = BattleMoveCandidateView(typeId = "normal", damageCategory = BattleMoveDamageCategory.STATUS,
                power = 0.0, accuracy = 100.0, priority = 0, currentPp = 10, targetPattern = BattleMoveTargetPattern.SELF),
            facts = BattleCandidateFactsView(baseAccuracyProbability = 1.0, selfHealingFractionRange = BattleFractionRange(0.5, 0.5)))
        val base = context(allySpeed, foeSpeed, allyPower = 60.0, foePower = 30.0, foeExtra = listOf(noise))
        return base.copy(state = base.state.derive(pokemon = base.state.pokemon.map {
            if (it.battlePokemonId == ALLY) it.copyState(hpFraction = 0.5) else it
        }), candidates = listOf(heal)) to heal
    }

    private fun context(
        allySpeed: Int,
        foeSpeed: Int,
        allyPower: Double = 300.0,
        foePower: Double = 300.0,
        rocksOnAllySide: Boolean = false,
        allySetup: Map<String, Int>? = null,
        allyExtra: List<BattlePublicMoveOptionView> = emptyList(),
        foeSetup: Map<String, Int>? = null,
        foeExtra: List<BattlePublicMoveOptionView> = emptyList(),
        allyItem: String? = null,
        benchPower: Double? = null,
        foeCategory: BattleMoveDamageCategory = BattleMoveDamageCategory.PHYSICAL,
        /** A benched opponent, slow and three-hitting, that only a strong ally beats. */
        secondFoe: Boolean = false,
        format: BattleFormat = BattleFormat.SINGLE,
        foeMovedThisTurn: Boolean = false,
        /** The bench Pokemon stands beside the ally, in doubles. */
        benchActive: Boolean = false,
        foeStatus: String? = null,
        /** The second opponent stands beside the first, in doubles. */
        secondFoeActive: Boolean = false,
        events: List<BattleObservedEventView> = emptyList(),
        /** Moves for the benched second opponent only, in place of [foeExtra]. */
        benchFoeExtra: List<BattlePublicMoveOptionView>? = null,
        turn: Int = 1,
    ): BattleDecisionContext {
        val field = if (!rocksOnAllySide) BattleFieldStateView.empty() else BattleFieldStateView(null, null, emptyList(), emptyList(),
            mapOf(BattleSide.ALLY to listOf(BattleTimedEffectView("stealthrock", null)), BattleSide.OPPONENT to emptyList()))
        val observed = events + if (!foeMovedThisTurn) emptyList() else
            listOf(BattleObservedEventView(9, 1, BattleObservedEventKind.MOVE_USED, FOE, listOf(ALLY), "probe"))
        val state = BattleStateView(UUID(0, 918), format, turn,
            listOf(pokemon(ALLY, BattleSide.ALLY, 0, allySpeed, allyItem), pokemon(BENCH, BattleSide.ALLY, if (benchActive) 1 else null, allySpeed),
                pokemon(FOE, BattleSide.OPPONENT, 0, foeSpeed, status = foeStatus)) + listOfNotNull(if (secondFoe) pokemon(FOE2, BattleSide.OPPONENT, if (secondFoeActive) 1 else null, 50) else null),
            field, mapOf(BattleSide.ALLY to 2, BattleSide.OPPONENT to if (secondFoe) 2 else 1), observed, emptyList())
        return BattleDecisionContext(UUID(0, 919), state, listOf(BattleActionCandidate("wait", BattleActionKind.WAIT)), Long.MAX_VALUE,
            publicActionCatalog = BattlePublicActionCatalogView(state.pokemon.map { pokemon ->
                val power = when (pokemon.battlePokemonId) { BENCH -> benchPower ?: allyPower; FOE -> foePower; FOE2 -> 65.0; else -> allyPower }
                val category = if (pokemon.side == BattleSide.ALLY) BattleMoveDamageCategory.PHYSICAL else foeCategory
                val knowledge = if (pokemon.side == BattleSide.ALLY) BattlePublicMoveKnowledge.EXACT_OWN else BattlePublicMoveKnowledge.PUBLICLY_REVEALED
                val attack = BattlePublicMoveOptionView("probe", BattleMoveCandidateView(typeId = "normal",
                    damageCategory = category, power = power, accuracy = 100.0, priority = 0, currentPp = 8), knowledge)
                val setup = (if (pokemon.side == BattleSide.ALLY) allySetup else foeSetup)?.let { stages ->
                    BattlePublicMoveOptionView(if (stages.size > 1) "dragondance" else "swordsdance", BattleMoveCandidateView(typeId = "normal",
                        damageCategory = BattleMoveDamageCategory.STATUS, power = 0.0, accuracy = 100.0, priority = 0, currentPp = 8,
                        targetPattern = BattleMoveTargetPattern.SELF,
                        effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, listOf(BattleMoveEffectView(
                            BattleMoveEffectKind.STAT_STAGE, BattleMoveEffectTarget.USER, statStages = stages)), false)), knowledge)
                }
                val extra = when {
                    pokemon.side == BattleSide.ALLY -> allyExtra
                    pokemon.battlePokemonId == FOE2 && benchFoeExtra != null -> benchFoeExtra
                    else -> foeExtra
                }
                BattlePokemonActionCatalogView(pokemon.battlePokemonId, listOfNotNull(attack, setup) + extra, moveSetComplete = true)
            }))
    }

    private fun pokemon(id: UUID, side: BattleSide, slot: Int?, speed: Int, item: String? = null, status: String? = null) =
        BattlePokemonStateView(id, side, slot, "fixture:matchup", null, 50, 1.0, status, emptyMap(), emptySet(), null, item, false,
            knownTypeIds = setOf("normal"),
            combatStats = BattleCombatStatRangesView(maxHp = BattleIntegerRange(150, 150),
                attack = BattleIntegerRange(150, 150), defence = BattleIntegerRange(100, 100),
                specialAttack = BattleIntegerRange(100, 100), specialDefence = BattleIntegerRange(100, 100),
                speed = BattleIntegerRange(speed, speed), knowledge = if (side == BattleSide.ALLY)
                    BattleCombatStatKnowledge.EXACT_OWN else BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE))

    private companion object {
        val ALLY = UUID(0, 1)
        val BENCH = UUID(0, 2)
        val FOE = UUID(0, 3)
        val FOE2 = UUID(0, 4)
    }
}
