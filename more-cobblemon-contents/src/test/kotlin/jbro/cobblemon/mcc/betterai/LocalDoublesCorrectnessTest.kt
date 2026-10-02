package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.calculation.LocalForcedReplacementResolver
import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.evaluation.LocalTacticalScorer
import jbro.cobblemon.mcc.betterai.outcome.PublicSingleTurnProjector
import jbro.cobblemon.mcc.betterai.search.LocalExpectedMoveResponseConfidence
import jbro.cobblemon.mcc.betterai.search.LocalOpponentResponseValue
import jbro.cobblemon.mcc.betterai.search.LocalResponseValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** Doubles defects found in the 2026-10-02 review, each pinned where it was wrong. */
class LocalDoublesCorrectnessTest {
    @Test
    fun `the search applies the spread reduction to each target it projects one at a time`() {
        val doubles = expectedDamageToFirstFoe(BattleFormat.DOUBLE)
        val singles = expectedDamageToFirstFoe(BattleFormat.SINGLE)
        val ratio = doubles / singles
        assertTrue(ratio in 0.70..0.80, "a spread hit in doubles is 0.75x; projected $doubles vs $singles ($ratio)")
    }

    @Test
    fun `a spread move is not nullified for every target by its primary target's immunity`() {
        val state = state(
            BattleFormat.DOUBLE,
            listOf(
                mon(BattleSide.ALLY, 0, "normal"), mon(BattleSide.ALLY, 1, "normal"),
                mon(BattleSide.OPPONENT, 0, "flying"), mon(BattleSide.OPPONENT, 1, "normal"),
            ),
        )
        val earthquake = attack("earthquake", "ground", BattleMoveTargetPattern.ALL_ADJACENT)
        // The facts the root candidate carries are against the Flying slot: a 0x type chart.
        val calculated = PublicBattleTacticalCalculator.calculate(context(state, earthquake))
        val outcomes = PublicSingleTurnProjector.project(
            state, calculated.candidates.single(), BattleActionCandidate("wait", BattleActionKind.WAIT), calculated,
        )
        val secondFoe = state.pokemon.single { it.side == BattleSide.OPPONENT && it.activeSlot == 1 }
        val partner = state.pokemon.single { it.side == BattleSide.ALLY && it.activeSlot == 1 }
        val firstFoe = state.pokemon.single { it.side == BattleSide.OPPONENT && it.activeSlot == 0 }
        outcomes.forEach { outcome ->
            val after = outcome.stateBeforeResidual.pokemon.associateBy { it.battlePokemonId }
            assertEquals(1.0, after.getValue(firstFoe.battlePokemonId).hpFraction, 1e-9, "Flying is immune")
            assertTrue(after.getValue(secondFoe.battlePokemonId).hpFraction < 1.0, "the Normal foe is hit")
            assertTrue(after.getValue(partner.battlePokemonId).hpFraction < 1.0, "the partner is hit")
        }
    }

    @Test
    fun `the root counts the partner when it decides whether a spread move is reduced`() {
        val pokemon = listOf(
            mon(BattleSide.ALLY, 0, "normal"), mon(BattleSide.ALLY, 1, "normal"),
            mon(BattleSide.OPPONENT, 0, "normal"),
            mon(BattleSide.OPPONENT, 1, "normal", hp = 0.0, fainted = true),
        )
        val state = state(BattleFormat.DOUBLE, pokemon)
        fun maximum(pattern: BattleMoveTargetPattern) = requireNotNull(
            PublicBattleTacticalCalculator.calculate(context(state, attack("probe", "normal", pattern)))
                .candidates.single().facts?.standardDamageFractionRange,
        ).maximum
        val foeOnly = maximum(BattleMoveTargetPattern.ALL_OPPONENTS)
        val withPartner = maximum(BattleMoveTargetPattern.ALL_ADJACENT)
        val ratio = withPartner / foeOnly
        assertTrue(ratio in 0.70..0.80, "one foe and the partner are two targets: $withPartner vs $foeOnly ($ratio)")
    }

    @Test
    fun `a spread move's second target is credited no more than the HP it has left`() {
        val nearlyDown = mon(BattleSide.OPPONENT, 1, "normal", hp = 0.05)
        val state = state(
            BattleFormat.DOUBLE,
            listOf(mon(BattleSide.ALLY, 0, "normal"), mon(BattleSide.ALLY, 1, "normal"),
                mon(BattleSide.OPPONENT, 0, "normal"), nearlyDown),
        )
        val calculated = PublicBattleTacticalCalculator.calculate(
            context(state, attack("heatwave", "fire", BattleMoveTargetPattern.ALL_OPPONENTS)),
        )
        val bonus = jbro.cobblemon.mcc.betterai.evaluation.LocalTacticalSituationalEvaluator.spreadAdjustment(
            calculated.candidates.single(), 1.0, calculated,
        )
        val material = jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning.CURRENT.knockoutMaterialScore
        assertTrue(bonus <= 0.05 * 100.0 + material + 1e-9, "5% of HP and a knockout at most, was $bonus")
    }

    @Test
    fun `a Focus Sash on a spread move's second target rules out the knockout there`() {
        val sash = mon(BattleSide.OPPONENT, 1, "normal", item = "focussash")
        val state = state(
            BattleFormat.DOUBLE,
            listOf(mon(BattleSide.ALLY, 0, "normal", attack = 400), mon(BattleSide.ALLY, 1, "normal"),
                mon(BattleSide.OPPONENT, 0, "normal"), sash),
        )
        val facts = requireNotNull(PublicBattleTacticalCalculator.calculate(
            context(state, attack("explosion", "normal", BattleMoveTargetPattern.ALL_OPPONENTS, power = 250.0)),
        ).candidates.single().facts)
        val second = facts.spreadTargets.single { it.slot == 1 }
        assertEquals(BattleKnockoutAssessment.IMPOSSIBLE, second.standardKnockoutAssessment, facts.spreadTargets.toString())
    }

    @Test
    fun `an Earthquake beside the partner's Protect is not charged for the partner`() {
        val state = state(
            BattleFormat.DOUBLE,
            listOf(mon(BattleSide.ALLY, 0, "normal"), mon(BattleSide.ALLY, 1, "normal"),
                mon(BattleSide.OPPONENT, 0, "normal"), mon(BattleSide.OPPONENT, 1, "normal")),
        )
        val earthquake = attack("earthquake", "ground", BattleMoveTargetPattern.ALL_ADJACENT)
        fun score(partner: BattleActionCandidate): Double {
            val joint = BattleActionCandidate(
                "joint:${partner.actionId}", BattleActionKind.COMPOSITE,
                componentActionIds = listOf(earthquake.actionId, partner.actionId),
                componentActions = listOf(earthquake, partner),
            )
            val calculated = PublicBattleTacticalCalculator.calculate(context(state, joint))
            val scored = calculated.candidates.single()
            val parts = scored.componentActions.sumOf { LocalTacticalScorer.scoreBreakdown(it, calculated).total }
            return LocalTacticalScorer.scoreBreakdown(scored, calculated).total - parts
        }
        val besideProtect = score(protect(slot = 1))
        val besideAttack = score(attack("tackle", "normal", BattleMoveTargetPattern.SELECTED_OPPONENT,
            slot = 1, target = BattleTargetSlot(BattleSide.OPPONENT, 0)))
        assertTrue(besideProtect > besideAttack + 10.0,
            "the partner's Protect takes the collateral back: protect=$besideProtect attack=$besideAttack")
    }

    @Test
    fun `an any-target move aimed at a foe that fainted earlier in the turn hits the other foe`() {
        val doomed = mon(BattleSide.OPPONENT, 0, "normal", hp = 0.01)
        val other = mon(BattleSide.OPPONENT, 1, "normal")
        val state = state(
            BattleFormat.DOUBLE,
            listOf(mon(BattleSide.ALLY, 0, "normal", speed = 150), mon(BattleSide.ALLY, 1, "normal", speed = 50),
                doomed, other),
        )
        val finisher = attack("finisher", "normal", BattleMoveTargetPattern.SELECTED_OPPONENT, slot = 0,
            target = BattleTargetSlot(BattleSide.OPPONENT, 0))
        val pulse = attack("darkpulse", "dark", BattleMoveTargetPattern.SELECTED, slot = 1,
            target = BattleTargetSlot(BattleSide.OPPONENT, 0))
        val joint = BattleActionCandidate("joint", BattleActionKind.COMPOSITE,
            componentActionIds = listOf(finisher.actionId, pulse.actionId), componentActions = listOf(finisher, pulse))
        val calculated = PublicBattleTacticalCalculator.calculate(context(state, joint))
        val outcomes = PublicSingleTurnProjector.project(
            state, calculated.candidates.single(), BattleActionCandidate("wait", BattleActionKind.WAIT), calculated,
        )
        outcomes.forEach { outcome ->
            val after = outcome.stateBeforeResidual.pokemon.single { it.battlePokemonId == other.battlePokemonId }
            assertTrue(after.hpFraction < 1.0, "Dark Pulse is reselected onto the surviving foe")
        }
    }

    @Test
    fun `a Pokemon dragged in does not use the move of the one it replaced`() {
        val dragged = mon(BattleSide.ALLY, 0, "normal", speed = 50)
        val bench = mon(BattleSide.ALLY, null, "normal")
        val foe = mon(BattleSide.OPPONENT, 0, "normal", speed = 150)
        val state = state(
            BattleFormat.DOUBLE,
            listOf(dragged, mon(BattleSide.ALLY, 1, "normal"), bench, foe, mon(BattleSide.OPPONENT, 1, "normal")),
        )
        val ourAttack = attack("tackle", "normal", BattleMoveTargetPattern.SELECTED_OPPONENT, slot = 0,
            target = BattleTargetSlot(BattleSide.OPPONENT, 0))
        val drag = attack("drag", "dragon", BattleMoveTargetPattern.SELECTED_OPPONENT, slot = 0,
            target = BattleTargetSlot(BattleSide.ALLY, 0), priority = 1,
            effects = listOf(BattleMoveEffectView(BattleMoveEffectKind.SWITCH_TARGET, BattleMoveEffectTarget.SELECTED_TARGET)))
        val calculated = PublicBattleTacticalCalculator.calculate(context(state, ourAttack))
        val outcomes = PublicSingleTurnProjector.project(state, calculated.candidates.single(), drag, calculated)
        assertTrue(outcomes.isNotEmpty())
        outcomes.forEach { outcome ->
            val after = outcome.stateBeforeResidual.pokemon.associateBy { it.battlePokemonId }
            assertEquals(0, after.getValue(bench.battlePokemonId).activeSlot, "the bench Pokemon was dragged in")
            assertEquals(1.0, after.getValue(foe.battlePokemonId).hpFraction, 1e-9,
                "the dragged-out Pokemon's Tackle left with it")
        }
    }

    @Test
    fun `an Intimidate switching in alongside a foe's switch lowers the incoming foe`() {
        val leaving = mon(BattleSide.ALLY, 0, "normal")
        val intimidator = mon(BattleSide.ALLY, null, "normal", ability = "intimidate")
        val foeLeaving = mon(BattleSide.OPPONENT, 0, "normal")
        val foeIncoming = mon(BattleSide.OPPONENT, null, "normal")
        val state = state(
            BattleFormat.DOUBLE,
            listOf(leaving, mon(BattleSide.ALLY, 1, "normal"), intimidator,
                foeLeaving, mon(BattleSide.OPPONENT, 1, "normal"), foeIncoming),
        )
        val ourSwitch = BattleActionCandidate("switch", BattleActionKind.SWITCH, actorSlot = 0,
            switchPokemonId = intimidator.battlePokemonId)
        val theirSwitch = BattleActionCandidate("their-switch", BattleActionKind.SWITCH, actorSlot = 0,
            switchPokemonId = foeIncoming.battlePokemonId)
        val calculated = PublicBattleTacticalCalculator.calculate(context(state, ourSwitch))
        val outcomes = PublicSingleTurnProjector.project(state, calculated.candidates.single(), theirSwitch, calculated)
        outcomes.forEach { outcome ->
            val incoming = outcome.stateBeforeResidual.pokemon.single { it.battlePokemonId == foeIncoming.battlePokemonId }
            assertEquals(-1, incoming.statStages["attack"] ?: 0, "Intimidate lands on the Pokemon now facing it")
        }
    }

    @Test
    fun `two empty slots with one Pokemon seen on the bench still fill the one it can`() {
        val bench = mon(BattleSide.ALLY, null, "normal")
        val pokemon = listOf(
            mon(BattleSide.ALLY, 0, "normal", hp = 0.0, fainted = true),
            mon(BattleSide.ALLY, 1, "normal", hp = 0.0, fainted = true),
            bench,
            mon(BattleSide.OPPONENT, 0, "normal"), mon(BattleSide.OPPONENT, 1, "normal"),
        )
        val state = state(BattleFormat.DOUBLE, pokemon, remaining = mapOf(BattleSide.ALLY to 2, BattleSide.OPPONENT to 2))
        val resolution = LocalForcedReplacementResolver.resolve(state, BattleSide.ALLY,
            context(state, BattleActionCandidate("wait", BattleActionKind.WAIT)))
        assertTrue(resolution.states.isNotEmpty(), "the known bench Pokemon goes in")
        resolution.states.forEach { replaced ->
            assertTrue(replaced.pokemon.single { it.battlePokemonId == bench.battlePokemonId }.activeSlot != null)
        }
    }

    @Test
    fun `expected replies are discounted when only one opponent's moves are incomplete`() {
        val expected = BattleActionCandidate("expected_slot_zero", BattleActionKind.USE_MOVE, actorSlot = 0,
            moveSlot = 0, moveId = "expected_move", tags = setOf("expected_opponent_move"))
        val unknown = BattleActionCandidate("unknown_slot_zero", BattleActionKind.WAIT,
            tags = setOf("unknown_public_response"))
        val confirmedPartner = BattleActionCandidate("confirmed_slot_one", BattleActionKind.USE_MOVE, actorSlot = 1,
            moveSlot = 0, moveId = "confirmed_move", tags = setOf("confirmed_opponent_move"))
        val values = listOf(
            single("confirmed_best", -100.0),
            composite("expected_and_confirmed", -80.0, expected, confirmedPartner),
            composite("unknown_and_confirmed", -20.0, unknown, confirmedPartner),
        )
        // The other opponent has shown every move, so no reply leaves both slots unknown.
        val baseline = LocalExpectedMoveResponseConfidence.noResponseBaseline(values, 0.20)
        assertNull(baseline)
        val adjusted = LocalExpectedMoveResponseConfidence.adjust(values, baseline, 0.80, 1.0e-9, unknownReserve = 0.0)
        assertEquals(-68.0, adjusted.single { it.action.actionId == "expected_and_confirmed" }.value.value, 1.0e-9)
    }

    private fun single(id: String, value: Double) = LocalOpponentResponseValue(
        BattleActionCandidate(id, BattleActionKind.WAIT, tags = setOf("confirmed_opponent_move")),
        LocalResponseValue(value, 1.0, 1.0),
    )

    private fun composite(id: String, value: Double, vararg parts: BattleActionCandidate) = LocalOpponentResponseValue(
        BattleActionCandidate(id, BattleActionKind.COMPOSITE, componentActionIds = parts.map { it.actionId },
            componentActions = parts.toList()),
        LocalResponseValue(value, 1.0, 1.0),
    )

    private fun expectedDamageToFirstFoe(format: BattleFormat): Double {
        val doubles = format == BattleFormat.DOUBLE
        val pokemon = buildList {
            add(mon(BattleSide.ALLY, 0, "normal"))
            add(mon(BattleSide.OPPONENT, 0, "normal"))
            if (doubles) {
                add(mon(BattleSide.ALLY, 1, "normal"))
                add(mon(BattleSide.OPPONENT, 1, "normal"))
            }
        }
        val state = state(format, pokemon)
        val calculated = PublicBattleTacticalCalculator.calculate(
            context(state, attack("rockslide", "rock", BattleMoveTargetPattern.ALL_OPPONENTS)),
        )
        val foe = state.pokemon.single { it.side == BattleSide.OPPONENT && it.activeSlot == 0 }
        return PublicSingleTurnProjector.project(
            state, calculated.candidates.single(), BattleActionCandidate("wait", BattleActionKind.WAIT), calculated,
        ).sumOf { outcome ->
            outcome.probability * outcome.orderProbability *
                (1.0 - outcome.stateBeforeResidual.pokemon.single { it.battlePokemonId == foe.battlePokemonId }.hpFraction)
        }
    }

    private fun protect(slot: Int) = BattleActionCandidate(
        actionId = "protect:$slot", kind = BattleActionKind.USE_MOVE, actorSlot = slot, moveSlot = 1,
        moveId = "cobblemon:protect",
        moveDetails = BattleMoveCandidateView(
            typeId = "normal", damageCategory = BattleMoveDamageCategory.STATUS, power = 0.0, accuracy = 100.0,
            priority = 4, currentPp = 10, targetPattern = BattleMoveTargetPattern.SELF,
            effects = BattleMoveEffectsView(
                coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                effects = listOf(BattleMoveEffectView(BattleMoveEffectKind.PROTECT_USER, BattleMoveEffectTarget.USER, 1.0)),
                scriptedBehavior = true,
                mechanicFlags = setOf("stalling_move"),
            ),
        ),
    )

    private fun attack(
        id: String,
        type: String,
        pattern: BattleMoveTargetPattern,
        slot: Int = 0,
        target: BattleTargetSlot? = null,
        power: Double = 90.0,
        priority: Int = 0,
        effects: List<BattleMoveEffectView> = emptyList(),
    ) = BattleActionCandidate(
        actionId = "$id:$slot", kind = BattleActionKind.USE_MOVE, actorSlot = slot, moveSlot = 0,
        moveId = "cobblemon:$id", targets = listOfNotNull(target),
        moveDetails = BattleMoveCandidateView(
            typeId = type, damageCategory = BattleMoveDamageCategory.PHYSICAL, power = power, accuracy = 100.0,
            priority = priority, currentPp = 10, targetPattern = pattern,
            effects = effects.takeIf { it.isNotEmpty() }?.let {
                BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, it, scriptedBehavior = false)
            },
        ),
    )

    private fun context(state: BattleStateView, candidate: BattleActionCandidate?) = BattleDecisionContext(
        requestId = UUID.randomUUID(), state = state, candidates = listOfNotNull(candidate),
        deadlineEpochMillis = Long.MAX_VALUE, memory = BattleTacticalMemoryView.empty(),
        publicActionCatalog = BattlePublicActionCatalogView(emptyList()),
    )

    private fun state(
        format: BattleFormat,
        pokemon: List<BattlePokemonStateView>,
        remaining: Map<BattleSide, Int>? = null,
    ) = BattleStateView(
        battleId = UUID.randomUUID(), format = format, turn = 3, pokemon = pokemon,
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = remaining ?: BattleSide.entries.associateWith { side ->
            pokemon.count { it.side == side && !it.fainted }
        },
        observedEvents = emptyList(), inferences = emptyList(),
    )

    private fun mon(
        side: BattleSide, slot: Int?, type: String, speed: Int = 100, attack: Int = 120,
        hp: Double = 1.0, fainted: Boolean = false, item: String? = null, ability: String? = null,
    ) =
        BattlePokemonStateView(
            battlePokemonId = UUID.randomUUID(), side = side, activeSlot = slot,
            speciesId = "showdown:probe", formId = null, level = 50, hpFraction = hp, statusId = null,
            statStages = emptyMap(), knownMoveIds = emptySet(), knownAbilityId = ability, knownHeldItemId = item,
            fainted = fainted, knownTypeIds = setOf(type),
            combatStats = BattleCombatStatRangesView(
                maxHp = BattleIntegerRange(160, 160), attack = BattleIntegerRange(attack, attack),
                defence = BattleIntegerRange(100, 100), specialAttack = BattleIntegerRange(120, 120),
                specialDefence = BattleIntegerRange(100, 100), speed = BattleIntegerRange(speed, speed),
                knowledge = BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
            ),
            knownVolatileEffectIds = emptySet(), knownBaseStabTypeIds = setOf(type),
        )
}
