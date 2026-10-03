package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.evaluation.LocalOnePlySwitchPositionValue
import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.betterai.policy.LocalBattleActionOutcome
import jbro.cobblemon.mcc.betterai.policy.LocalBattleActionRank
import jbro.cobblemon.mcc.betterai.search.LocalRecursiveLookaheadEvaluator
import jbro.cobblemon.mcc.betterai.state.LocalSwitchStateProjector
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

/** Separates the next public attack choice from material and rank value already owned by this turn. */
class LocalOnePlySwitchPositionTest {
    @Test
    fun `one-ply doubles search distinguishes the public attack options of otherwise identical switch-ins`() {
        val source = switchContext(strongAttack = true)
        val entered = LocalSwitchStateProjector.project(source.state, BattleSide.ALLY,
            source.candidates.first().componentActions.first())
        val damage = PublicBattleTacticalCalculator.conservativeDamageRollFractions(attack(),
            source.copy(state = entered), BattleSide.ALLY)
        assertTrue(requireNotNull(damage).average() > 0.0, "The incoming Tackle is publicly calculable")
        val gains = gains(source)
        assertTrue(gains.getValue("strong") > gains.getValue("weak") + 1e-9,
            "Both turns preserve the same HP, types, ranks and speed; their known next attack options differ")
    }

    @Test
    fun `equivalent public switch options do not acquire an identity or candidate-order bonus`() {
        val source = switchContext(strongAttack = false)
        val first = gains(source)
        val reversed = gains(source.copy(candidates = source.candidates.reversed()))
        assertEquals(first.getValue("strong"), first.getValue("weak"), 1e-9)
        assertEquals(first, reversed)
    }

    @Test
    fun `body damage without an active change does not receive another leaf pressure price`() {
        val source = ordinaryContext(setup = false)
        assertEquals(gains(source, leafPressure = 0.0).values.single(),
            gains(source, leafPressure = 0.3).values.single(), 1e-9)
    }

    @Test
    fun `rank pressure and persistent rank value without an active change retain their immediate owner`() {
        val source = ordinaryContext(setup = true)
        val immediate = gains(source, leafPressure = 0.0, persistentStages = 0.25).values.single()
        assertTrue(immediate > 0.0, "The declared boost already has a calculable immediate rank value")
        assertEquals(immediate, gains(source, leafPressure = 0.3, persistentStages = 0.25).values.single(), 1e-9)
    }

    @Test
    fun `an opponent switch to unrevealed moves cannot be read as confirmed loss of retaliation`() {
        assertEquals(0.0, opponentSwitchDelta(null), 1e-9,
            "The missing incoming move catalog is unknown evidence, rather than a known harmless set")
    }

    @Test
    fun `one revealed harmless move cannot certify an incomplete incoming set as harmless`() {
        assertEquals(0.0, opponentSwitchDelta(listOf(splash(0)), complete = false), 1e-9,
            "Revealed Splash does not rule out another, unrevealed attack")
    }

    @Test
    fun `an exhausted known attack in a complete set remains evidence rather than missing moves`() {
        val exhausted = BattleActionCandidate("exhausted:0", BattleActionKind.USE_MOVE, actorSlot = 0,
            moveSlot = 0, moveId = "tackle", targets = listOf(BattleTargetSlot(BattleSide.ALLY, 0)),
            moveDetails = BattleMoveCandidateView("normal", BattleMoveDamageCategory.PHYSICAL, 80.0, 100.0, 0, 0))
        // Splash still has PP, so this set does not trigger forced Struggle.
        assertTrue(opponentSwitchDelta(listOf(exhausted, splash(0)), complete = true) > 0.0)
    }

    @Test
    fun `a complete move set cannot price a switch position whose incoming combat ranges are absent`() {
        assertEquals(0.0, opponentSwitchDelta(listOf(splash(0)), complete = true, knownStats = false), 1e-9,
            "The incoming stats are unknown; a null damage calculation must not become evidence of zero pressure")
    }

    @Test
    fun `a departing transformed move pool cannot remain available in the next position`() {
        val original = switchContext(strongAttack = true)
        val former = original.state.pokemon.single { it.side == BattleSide.ALLY && it.activeSlot == 0 }
        val source = original.copy(publicActionCatalog = BattlePublicActionCatalogView(
            original.publicActionCatalog.entries.map {
                if (it.battlePokemonId == former.battlePokemonId) entry(former, listOf(attack())) else it
            }, originalEntries = listOf(entry(former, listOf(splash(0))))))
        val after = LocalSwitchStateProjector.project(source.state, BattleSide.ALLY,
            source.candidates.first().componentActions.first())
        val delta = LocalOnePlySwitchPositionValue.delta(source.state, after, source,
            LocalProjectedActionCalculationCache(), LocalDecisionTuning.CURRENT, { true })
        assertTrue(delta > 0.0,
            "The incoming known Tackle improves on original Splash; departure has removed the copied Tackle")
        assertEquals("tackle", source.publicActionCatalog.forPokemon(former.battlePokemonId).single().moveId,
            "The next-position read must not change the live source catalog")
    }

    @Test
    fun `an unchanged partially known partner does not erase a calculable switch comparison`() {
        val original = switchContext(strongAttack = true)
        val partner = original.state.pokemon.single { it.side == BattleSide.OPPONENT && it.activeSlot == 1 }
        val source = original.copy(publicActionCatalog = BattlePublicActionCatalogView(
            original.publicActionCatalog.entries.map {
                if (it.battlePokemonId == partner.battlePokemonId) entry(partner, listOf(splash(1)), complete = false) else it
            }))
        val values = source.candidates.associate { candidate ->
            val after = LocalSwitchStateProjector.project(source.state, BattleSide.ALLY,
                candidate.componentActions.first())
            candidate.actionId to LocalOnePlySwitchPositionValue.delta(source.state, after, source,
                LocalProjectedActionCalculationCache(), LocalDecisionTuning.CURRENT, { true })
        }
        assertTrue(values.getValue("strong") > values.getValue("weak"),
            "Only changed actors require a complete comparison; the same partial partner is present in both frames")
    }

    private fun opponentSwitchDelta(incomingMoves: List<BattleActionCandidate>?, complete: Boolean = false,
        knownStats: Boolean = true): Double {
        val original = ordinaryContext(setup = false)
        val former = original.state.pokemon.single { it.side == BattleSide.OPPONENT && it.activeSlot == 0 }
        val incoming = mon(7, BattleSide.OPPONENT, null).let { if (knownStats) it else it.copyState(combatStats = null) }
        val source = original.copy(state = original.state.copyState(pokemon = original.state.pokemon + incoming),
            publicActionCatalog = BattlePublicActionCatalogView(original.publicActionCatalog.entries.map {
                if (it.battlePokemonId == former.battlePokemonId) entry(former, listOf(attack())) else it
            } + incomingMoves?.let { listOf(entry(incoming, it, complete)) }.orEmpty()))
        val after = LocalSwitchStateProjector.project(source.state, BattleSide.OPPONENT,
            BattleActionCandidate("unrevealed:switch", BattleActionKind.SWITCH, actorSlot = 0,
                switchPokemonId = incoming.battlePokemonId))
        return LocalOnePlySwitchPositionValue.delta(source.state, after, source,
            LocalProjectedActionCalculationCache(), LocalDecisionTuning.CURRENT, { true })
    }

    private fun gains(source: BattleDecisionContext, leafPressure: Double = 0.3,
        persistentStages: Double = 0.0): Map<String, Double> {
        val result = LocalRecursiveLookaheadEvaluator.evaluate(source.candidates.map(::rank), source,
            BattleTrainerProfile.boss(), LocalDecisionTuning.CURRENT.copy(
                lookaheadMoveHypotheses = false, lookaheadLinearCoverage = true, lookaheadCoverageFloor = 1.0,
                leafPressureWeight = leafPressure, leafPersistentStageValue = persistentStages),
            clockMillis = { 0L }, moveUsageForFormat = { null })
        assertEquals(1, result.depthCompleted)
        assertFalse(result.truncated)
        assertFalse(result.publicResponseIncomplete)
        return result.ranked.associate { it.outcome.candidate.actionId to it.lookaheadUtility }
    }

    private fun switchContext(strongAttack: Boolean): BattleDecisionContext {
        val pokemon = listOf(mon(1, BattleSide.ALLY, 0), mon(2, BattleSide.ALLY, 1),
            mon(3, BattleSide.ALLY, null), mon(4, BattleSide.ALLY, null),
            mon(5, BattleSide.OPPONENT, 0), mon(6, BattleSide.OPPONENT, 1))
        val candidates = listOf(3L to "strong", 4L to "weak").map { (id, name) ->
            joint(name, BattleActionCandidate("switch:$id", BattleActionKind.SWITCH, actorSlot = 0,
                switchPokemonId = UUID(0, id)), splash(1))
        }
        return context(pokemon, candidates, pokemon.map { member -> entry(member,
            if (member.battlePokemonId == UUID(0, 3) && strongAttack) listOf(attack()) else listOf(splash(member.activeSlot ?: 0))) })
    }

    private fun ordinaryContext(setup: Boolean): BattleDecisionContext {
        val pokemon = listOf(mon(1, BattleSide.ALLY, 0), mon(2, BattleSide.ALLY, 1),
            mon(5, BattleSide.OPPONENT, 0), mon(6, BattleSide.OPPONENT, 1))
        val boost = BattleActionCandidate("swordsdance:0", BattleActionKind.USE_MOVE, actorSlot = 0,
            moveSlot = 1, moveId = "swordsdance", moveDetails = BattleMoveCandidateView("normal",
                BattleMoveDamageCategory.STATUS, 0.0, 100.0, 0, 10, BattleMoveTargetPattern.SELF,
                BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, listOf(BattleMoveEffectView(
                    BattleMoveEffectKind.STAT_STAGE, BattleMoveEffectTarget.USER, probability = 1.0,
                    statStages = mapOf("attack" to 2))), scriptedBehavior = false)))
        val selected = if (setup) boost else attack()
        val candidates = listOf(joint("ordinary", selected, splash(1)))
        val entries = pokemon.map { member -> entry(member,
            if (member.battlePokemonId == UUID(0, 1)) listOf(attack(), boost) else listOf(splash(member.activeSlot ?: 0))) }
        return context(pokemon, candidates, entries)
    }

    private fun context(pokemon: List<BattlePokemonStateView>, candidates: List<BattleActionCandidate>,
        entries: List<BattlePokemonActionCatalogView>) = BattleDecisionContext(UUID(0, 90),
        BattleStateView(UUID(0, 91), BattleFormat.DOUBLE, 3, pokemon, BattleFieldStateView.empty(),
            BattleSide.entries.associateWith { side -> pokemon.count { it.side == side } }, emptyList(), emptyList()),
        candidates, Long.MAX_VALUE, publicActionCatalog = BattlePublicActionCatalogView(entries))

    private fun mon(n: Long, side: BattleSide, slot: Int?) = BattlePokemonStateView(UUID(0, n), side, slot,
        "test", null, 50, 1.0, null, emptyMap(), emptySet(), null, "", false, setOf("normal"),
        if (side == BattleSide.ALLY) BattleCombatStatRangesView.exact(300, 100, 100, 100, 100, 200)
        else BattleCombatStatRangesView(BattleIntegerRange(299, 301), BattleIntegerRange(99, 101),
            BattleIntegerRange(99, 101), BattleIntegerRange(99, 101), BattleIntegerRange(99, 101),
            BattleIntegerRange(99, 101), BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE))

    private fun entry(pokemon: BattlePokemonStateView, actions: List<BattleActionCandidate>, complete: Boolean = true) =
        BattlePokemonActionCatalogView(pokemon.battlePokemonId, actions.map { BattlePublicMoveOptionView(
            requireNotNull(it.moveId), requireNotNull(it.moveDetails), if (pokemon.side == BattleSide.ALLY)
                BattlePublicMoveKnowledge.EXACT_OWN else BattlePublicMoveKnowledge.PUBLICLY_REVEALED) }, moveSetComplete = complete)

    private fun attack() = BattleActionCandidate("tackle:0", BattleActionKind.USE_MOVE, actorSlot = 0,
        moveSlot = 0, moveId = "tackle", targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
        moveDetails = BattleMoveCandidateView("normal", BattleMoveDamageCategory.PHYSICAL, 80.0, 100.0, 0, 10))

    private fun splash(slot: Int) = BattleActionCandidate("splash:$slot", BattleActionKind.USE_MOVE,
        actorSlot = slot, moveSlot = 0, moveId = "splash", moveDetails = BattleMoveCandidateView("normal",
            BattleMoveDamageCategory.STATUS, 0.0, 100.0, 0, 10, BattleMoveTargetPattern.SELF))

    private fun joint(id: String, vararg parts: BattleActionCandidate) = BattleActionCandidate(id, BattleActionKind.COMPOSITE,
        componentActionIds = parts.map { it.actionId }, componentActions = parts.toList())

    private fun rank(candidate: BattleActionCandidate) = LocalBattleActionRank(LocalBattleActionOutcome(candidate,
        0.0, 0.0, 0, 0, false, false, null, null, null, null), decisionTier = 3, comparisonValue = 30_000.0)
}
