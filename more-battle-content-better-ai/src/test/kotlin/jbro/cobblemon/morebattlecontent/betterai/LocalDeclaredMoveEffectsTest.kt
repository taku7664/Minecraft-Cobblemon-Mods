package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicMoveOutcomeBranchProjector
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalDirectHitMechanics
import jbro.cobblemon.morebattlecontent.betterai.outcome.ChanceEffectProjectionMode
import jbro.cobblemon.morebattlecontent.betterai.outcome.PublicSingleTurnProjector
import jbro.cobblemon.morebattlecontent.betterai.state.RecursiveActionHistory
import jbro.cobblemon.morebattlecontent.betterai.state.RecursiveHistoryProjector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalDeclaredMoveEffectsTest {
    @Test
    fun `accuracy and evasion stages change recursive hit probability`() {
        val initial = state(allyStages = mapOf("accuracy" to -1), opponentStages = mapOf("evasion" to 1))
        val ordinary = action("ordinary", effects())
        val ignoresEvasion = action(
            "ignore_evasion",
            effects(effect(BattleMoveEffectKind.IGNORE_EVASION_STAGES)),
        )

        val ordinaryHit = PublicMoveOutcomeBranchProjector.project(ordinary, context(initial, ordinary), BattleSide.ALLY)
            .single { it.hit }.probability
        val ignoredHit = PublicMoveOutcomeBranchProjector.project(
            ignoresEvasion,
            context(initial, ignoresEvasion),
            BattleSide.ALLY,
        ).single { it.hit }.probability

        assertEquals(0.6, ordinaryHit, 1e-9)
        assertEquals(0.75, ignoredHit, 1e-9)
    }

    @Test
    fun `multi accuracy branches every independent hit instead of assuming one accuracy roll`() {
        val declared = effects(
            effect(BattleMoveEffectKind.MULTI_HIT, amountRange = BattleIntegerRange(1, 3)),
            effect(BattleMoveEffectKind.MULTI_ACCURACY),
        )
        val move = action("three_checks", declared, accuracy = 50.0, damage = 0.1)
        val outcomes = PublicMoveOutcomeBranchProjector.project(move, context(state(withStats = false), move), BattleSide.ALLY)

        assertEquals(4, outcomes.size)
        assertEquals(0.5, outcomes.single { !it.hit }.probability, 1e-9)
        assertEquals(0.25, outcomes.single { kotlin.math.abs(it.damageFraction - 0.1) < 1e-9 }.probability, 1e-9)
        assertEquals(0.125, outcomes.single { kotlin.math.abs(it.damageFraction - 0.2) < 1e-9 }.probability, 1e-9)
        assertEquals(0.125, outcomes.single { kotlin.math.abs(it.damageFraction - 0.3) < 1e-9 }.probability, 1e-9)
        assertEquals(1.0, outcomes.sumOf { it.probability }, 1e-9)
    }

    @Test
    fun `spectral thief transfers positive stages after a successful hit`() {
        val initial = state(allyStages = mapOf("attack" to -1), opponentStages = mapOf("attack" to 2, "defense" to -1))
        val applied = LocalDirectHitMechanics.apply(
            initial,
            ALLY_ID,
            OPPONENT_ID,
            0.1,
            listOf(effect(BattleMoveEffectKind.STEALS_STAT_STAGES)),
            ignoreTargetAbility = false,
        ).state

        val ally = applied.pokemon.single { it.battlePokemonId == ALLY_ID }
        val opponent = applied.pokemon.single { it.battlePokemonId == OPPONENT_ID }
        assertEquals(1, ally.statStages["attack"])
        assertEquals(0, opponent.statStages["attack"])
        assertEquals(-1, opponent.statStages["defense"])
    }

    @Test
    fun `declared thaw removes freeze only after a successful hit`() {
        val initial = state(opponentStatus = "cobblemon:freeze")
        val applied = LocalDirectHitMechanics.apply(
            initial,
            ALLY_ID,
            OPPONENT_ID,
            0.1,
            listOf(effect(BattleMoveEffectKind.THAWS_TARGET)),
            ignoreTargetAbility = false,
        ).state

        assertNull(applied.pokemon.single { it.battlePokemonId == OPPONENT_ID }.statusId)
    }

    @Test
    fun `damaging secondary status respects public type immunity`() {
        val initial = state(opponentTypes = setOf("fire"))
        val burn = action(
            "burn_hit",
            effects(
                BattleMoveEffectView(
                    BattleMoveEffectKind.STATUS,
                    BattleMoveEffectTarget.SELECTED_TARGET,
                    probability = 1.0,
                    valueId = "brn",
                ),
            ),
        )

        val outcomes = PublicSingleTurnProjector.project(
            initial,
            burn,
            BattleActionCandidate("wait", BattleActionKind.WAIT),
            context(initial, burn),
        )

        assertTrue(outcomes.all { projection ->
            projection.state.pokemon.single { it.battlePokemonId == OPPONENT_ID }.statusId == null
        })
    }

    @Test
    fun `switch target replaces a surviving target with each publicly known legal reserve`() {
        val benchId = UUID.fromString("00000000-0000-0000-0000-000000000805")
        val initial = BattleStateView(
            BATTLE_ID,
            BattleFormat.SINGLE,
            1,
            listOf(
                pokemon(ALLY_ID, BattleSide.ALLY, emptyMap(), null, setOf("normal")),
                pokemon(OPPONENT_ID, BattleSide.OPPONENT, emptyMap(), null, setOf("normal")),
                pokemon(benchId, BattleSide.OPPONENT, emptyMap(), null, setOf("normal"), activeSlot = null),
            ),
            BattleFieldStateView.empty(),
            mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 2),
            emptyList(),
            emptyList(),
        )
        val phaze = action(
            "dragon_tail",
            effects(effect(BattleMoveEffectKind.SWITCH_TARGET)),
            damage = 0.1,
        )

        val outcomes = PublicSingleTurnProjector.project(
            initial,
            phaze,
            BattleActionCandidate("wait", BattleActionKind.WAIT),
            context(initial, phaze),
        )

        assertTrue(outcomes.isNotEmpty())
        assertTrue(outcomes.all { projection ->
            projection.state.pokemon.single { it.battlePokemonId == benchId }.activeSlot == 0 &&
                projection.state.pokemon.single { it.battlePokemonId == OPPONENT_ID }.activeSlot == null &&
                BattleSide.OPPONENT in projection.switchedSides
        })
    }

    @Test
    fun `sleep blocks ordinary moves but permits declared sleep usable moves`() {
        val sleeping = state(allyStatus = "slp")
        val ordinary = action("ordinary_asleep", effects(), damage = 0.2)
        val usable = action(
            "snore",
            effects(effect(BattleMoveEffectKind.USABLE_WHILE_ASLEEP)),
            damage = 0.2,
        )

        val blocked = PublicSingleTurnProjector.project(
            sleeping, ordinary, BattleActionCandidate("wait", BattleActionKind.WAIT), context(sleeping, ordinary),
        )
        val executed = PublicSingleTurnProjector.project(
            sleeping, usable, BattleActionCandidate("wait", BattleActionKind.WAIT), context(sleeping, usable),
        )

        assertTrue(blocked.all { it.state.pokemon.single { pokemon -> pokemon.battlePokemonId == OPPONENT_ID }.hpFraction == 1.0 })
        assertTrue(executed.all { it.state.pokemon.single { pokemon -> pokemon.battlePokemonId == OPPONENT_ID }.hpFraction < 1.0 })
    }

    @Test
    fun `freeze branches a twenty percent thaw that can act`() {
        val frozen = state(allyStatus = "frz")
        val hit = action("frozen_hit", effects(), damage = 0.2)

        val outcomes = PublicSingleTurnProjector.project(
            frozen, hit, BattleActionCandidate("wait", BattleActionKind.WAIT), context(frozen, hit),
            chanceEffectMode = ChanceEffectProjectionMode.BRANCH_STATE,
        )

        val acted = outcomes.filter {
            it.state.pokemon.single { pokemon -> pokemon.battlePokemonId == OPPONENT_ID }.hpFraction < 1.0
        }
        assertEquals(0.2, acted.sumOf { it.probability }, 1e-9)
        assertTrue(acted.all { it.state.pokemon.single { pokemon -> pokemon.battlePokemonId == ALLY_ID }.statusId == null })
    }

    @Test
    fun `first active turn move fails after that pokemon has already acted`() {
        val initial = state()
        val fakeOut = action(
            "fake_out",
            effects(effect(BattleMoveEffectKind.FIRST_ACTIVE_TURN_ONLY)),
            damage = 0.2,
        )

        val outcomes = PublicSingleTurnProjector.project(
            initial,
            fakeOut,
            BattleActionCandidate("wait", BattleActionKind.WAIT),
            context(initial, fakeOut),
            history = RecursiveActionHistory(actedSinceEntryPokemonIds = setOf(ALLY_ID)),
        )

        assertTrue(outcomes.all {
            it.state.pokemon.single { pokemon -> pokemon.battlePokemonId == OPPONENT_ID }.hpFraction == 1.0
        })
    }

    @Test
    fun `publicly unmet move requirement fails inside recursive projection`() {
        val initial = state()
        val veilWithoutWeather = action(
            "weather_gated_hit",
            effects(
                requirements = listOf(
                    BattleMoveRequirementView(
                        BattleMoveRequirementKind.WEATHER_ANY_OF,
                        setOf("hail", "snow"),
                    ),
                ),
            ),
            damage = 0.2,
        )

        val outcomes = PublicSingleTurnProjector.project(
            initial,
            veilWithoutWeather,
            BattleActionCandidate("wait", BattleActionKind.WAIT),
            context(initial, veilWithoutWeather),
        )

        assertTrue(outcomes.all {
            it.state.pokemon.single { pokemon -> pokemon.battlePokemonId == OPPONENT_ID }.hpFraction == 1.0
        })
    }

    @Test
    fun `future move waits two full turns then strikes the current occupant of the target slot`() {
        val reserveId = UUID.fromString("00000000-0000-0000-0000-000000000806")
        val initial = BattleStateView(
            BATTLE_ID,
            BattleFormat.SINGLE,
            1,
            listOf(
                pokemon(ALLY_ID, BattleSide.ALLY, emptyMap(), null, setOf("psychic")),
                pokemon(OPPONENT_ID, BattleSide.OPPONENT, emptyMap(), null, setOf("normal")),
                pokemon(reserveId, BattleSide.OPPONENT, emptyMap(), null, setOf("normal"), activeSlot = null),
            ),
            BattleFieldStateView.empty(),
            mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 2),
            emptyList(),
            emptyList(),
        )
        val wait = BattleActionCandidate("wait", BattleActionKind.WAIT)
        val futureMove = action(
            "future_sight",
            effects(effect(BattleMoveEffectKind.SLOT_CONDITION, valueId = "futuremove")),
            damage = 0.3,
        )

        val first = PublicSingleTurnProjector.project(initial, futureMove, wait, context(initial, futureMove)).single()
        assertEquals(1.0, first.state.pokemon.single { it.battlePokemonId == OPPONENT_ID }.hpFraction, 1e-9)
        val firstHistory = RecursiveHistoryProjector.project(
            RecursiveActionHistory(), initial, first, futureMove, wait,
        )

        val second = PublicSingleTurnProjector.project(
            first.state, wait, wait, context(first.state, wait), history = firstHistory,
        ).single()
        assertEquals(1.0, second.state.pokemon.single { it.battlePokemonId == OPPONENT_ID }.hpFraction, 1e-9)
        val secondHistory = RecursiveHistoryProjector.project(firstHistory, first.state, second, wait, wait)
        val switchedState = BattleStateView(
            BATTLE_ID,
            BattleFormat.SINGLE,
            second.state.turn,
            listOf(
                pokemon(ALLY_ID, BattleSide.ALLY, emptyMap(), null, setOf("psychic")),
                pokemon(OPPONENT_ID, BattleSide.OPPONENT, emptyMap(), null, setOf("normal"), activeSlot = null),
                pokemon(reserveId, BattleSide.OPPONENT, emptyMap(), null, setOf("normal")),
            ),
            second.state.field,
            mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 2),
            emptyList(),
            emptyList(),
        )

        val third = PublicSingleTurnProjector.project(
            switchedState, wait, wait, context(switchedState, wait), history = secondHistory,
        ).single()

        assertEquals(1.0, third.state.pokemon.single { it.battlePokemonId == OPPONENT_ID }.hpFraction, 1e-9)
        assertTrue(third.state.pokemon.single { it.battlePokemonId == reserveId }.hpFraction < 1.0)
    }

    @Test
    fun `future sight reservation immunity bypass does not bypass dark immunity on impact`() {
        val initial = state(opponentTypes = setOf("dark"))
        val wait = BattleActionCandidate("wait", BattleActionKind.WAIT)
        val futureSight = action(
            "future_sight_dark",
            effects(
                effect(BattleMoveEffectKind.SLOT_CONDITION, valueId = "futuremove"),
                effect(BattleMoveEffectKind.IGNORE_TYPE_IMMUNITY),
            ),
            damage = 0.3,
            typeId = "psychic",
        )

        val first = PublicSingleTurnProjector.project(initial, futureSight, wait, context(initial, futureSight)).single()
        val firstHistory = RecursiveHistoryProjector.project(
            RecursiveActionHistory(), initial, first, futureSight, wait,
        )
        val second = PublicSingleTurnProjector.project(
            first.state, wait, wait, context(first.state, wait), history = firstHistory,
        ).single()
        val secondHistory = RecursiveHistoryProjector.project(firstHistory, first.state, second, wait, wait)
        val third = PublicSingleTurnProjector.project(
            second.state, wait, wait, context(second.state, wait), history = secondHistory,
        ).single()

        assertEquals(1.0, third.state.pokemon.single { it.battlePokemonId == OPPONENT_ID }.hpFraction, 1e-9)
    }

    @Test
    fun `revival blessing revives a publicly known fainted ally at half health`() {
        val faintedId = UUID.fromString("00000000-0000-0000-0000-000000000807")
        val initial = BattleStateView(
            BATTLE_ID,
            BattleFormat.SINGLE,
            1,
            listOf(
                pokemon(ALLY_ID, BattleSide.ALLY, emptyMap(), null, setOf("normal")),
                pokemon(faintedId, BattleSide.ALLY, emptyMap(), null, setOf("normal"), activeSlot = null, fainted = true),
                pokemon(OPPONENT_ID, BattleSide.OPPONENT, emptyMap(), null, setOf("normal")),
            ),
            BattleFieldStateView.empty(),
            mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1),
            emptyList(),
            emptyList(),
        )
        val revive = action(
            "revival_blessing",
            effects(effect(BattleMoveEffectKind.SLOT_CONDITION, valueId = "revivalblessing")),
        )

        val outcome = PublicSingleTurnProjector.project(
            initial,
            revive,
            BattleActionCandidate("wait", BattleActionKind.WAIT),
            context(initial, revive),
        ).single()
        val revived = outcome.state.pokemon.single { it.battlePokemonId == faintedId }

        assertEquals(false, revived.fainted)
        assertEquals(0.5, revived.hpFraction, 1e-9)
        assertEquals(2, outcome.state.remainingPokemonBySide.getValue(BattleSide.ALLY))
    }

    private fun context(state: BattleStateView, action: BattleActionCandidate) = BattleDecisionContext(
        REQUEST_ID,
        state,
        listOf(action),
        Long.MAX_VALUE,
    )

    private fun state(
        allyStages: Map<String, Int> = emptyMap(),
        opponentStages: Map<String, Int> = emptyMap(),
        opponentStatus: String? = null,
        opponentTypes: Set<String> = setOf("normal"),
        allyStatus: String? = null,
        withStats: Boolean = true,
    ) = BattleStateView(
        BATTLE_ID,
        BattleFormat.SINGLE,
        1,
        listOf(
            pokemon(ALLY_ID, BattleSide.ALLY, allyStages, allyStatus, setOf("normal"), withStats = withStats),
            pokemon(
                OPPONENT_ID,
                BattleSide.OPPONENT,
                opponentStages,
                opponentStatus,
                opponentTypes,
                withStats = withStats,
            ),
        ),
        BattleFieldStateView.empty(),
        mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1),
        emptyList(),
        emptyList(),
    )

    private fun pokemon(
        id: UUID,
        side: BattleSide,
        stages: Map<String, Int>,
        status: String?,
        types: Set<String>,
        activeSlot: Int? = 0,
        withStats: Boolean = true,
        fainted: Boolean = false,
    ) = BattlePokemonStateView(
        id, side, activeSlot, "showdown:test", null, 50, 1.0, status, stages,
        emptySet(), null, null, fainted, types,
        if (!withStats) null else if (side == BattleSide.ALLY) {
            BattleCombatStatRangesView.exact(200, 120, 100, 120, 100, 100)
        } else {
            BattleCombatStatRangesView(
                BattleIntegerRange(200, 200),
                BattleIntegerRange(120, 120),
                BattleIntegerRange(100, 100),
                BattleIntegerRange(120, 120),
                BattleIntegerRange(100, 100),
                BattleIntegerRange(100, 100),
                BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
            )
        },
    )

    private fun action(
        id: String,
        effects: BattleMoveEffectsView,
        accuracy: Double = 100.0,
        damage: Double? = null,
        typeId: String = "normal",
    ) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = 0,
        moveSlot = 0,
        moveId = "cobblemon:$id",
        targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
        moveDetails = BattleMoveCandidateView(
            typeId,
            BattleMoveDamageCategory.PHYSICAL,
            40.0,
            accuracy,
            0,
            10,
            BattleMoveTargetPattern.SELECTED_OPPONENT,
            effects,
        ),
        facts = damage?.let {
            BattleCandidateFactsView(
                baseAccuracyProbability = accuracy / 100.0,
                typeChartMultiplier = 1.0,
                standardDamageModel = BattleStandardDamageModel.SHOWDOWN_GEN9_BASE_NON_CRITICAL,
                standardDamageFractionRange = BattleDamageFractionRange(it, it),
                standardDamageRollKoProbabilityRange = BattleFractionRange(0.0, 0.0),
                standardKnockoutAssessment = BattleKnockoutAssessment.IMPOSSIBLE,
                calculationCoverage = BattleCalculationCoverage.PARTIAL,
            )
        },
    )

    private fun effects(
        vararg effects: BattleMoveEffectView,
        requirements: List<BattleMoveRequirementView> = emptyList(),
    ) = BattleMoveEffectsView(
        BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
        effects.toList(),
        scriptedBehavior = false,
        requirements = requirements,
    )

    private fun effect(
        kind: BattleMoveEffectKind,
        amountRange: BattleIntegerRange? = null,
        valueId: String? = null,
    ) = BattleMoveEffectView(
        kind,
        BattleMoveEffectTarget.SELECTED_TARGET,
        probability = 1.0,
        valueId = valueId,
        amountRange = amountRange,
    )

    private companion object {
        val BATTLE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000801")
        val REQUEST_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000802")
        val ALLY_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000803")
        val OPPONENT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000804")
    }
}
