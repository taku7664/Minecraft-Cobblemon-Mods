package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.mechanics.RecursiveControlEffectKind
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.betterai.outcome.PublicSingleTurnProjector
import jbro.cobblemon.mcc.betterai.state.LocalEndTurnStateProjector
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalStatusImmunityBoundaryTest {
    private val fixture = LocalEvaluationRegressionFixture

    @Test
    fun `an active Pastel Veil partner prevents the Toxic Orb status`() {
        val actor = fixture.mon(BattleSide.ALLY, 0).copyState(knownHeldItemId = "toxicorb")
        val partner = fixture.mon(BattleSide.ALLY, 1, ability = "pastelveil")
        val result = LocalEndTurnStateProjector.project(fixture.state(actor, partner, format = BattleFormat.DOUBLE))
        assertNull(result.pokemon.single { it.battlePokemonId == actor.battlePokemonId }.statusId)
    }

    @Test
    fun `an active Sweet Veil partner prevents Yawn sleep and leaves Lum unspent`() {
        val actor = fixture.mon(BattleSide.ALLY, 0).copyState(knownHeldItemId = "lumberry")
        val partner = fixture.mon(BattleSide.ALLY, 1, ability = "sweetveil")
        val result = LocalEndTurnStateProjector.project(fixture.state(actor, partner, format = BattleFormat.DOUBLE),
            yawnPokemonIds = setOf(actor.battlePokemonId)).pokemon.single { it.battlePokemonId == actor.battlePokemonId }
        assertNull(result.statusId)
        assertEquals("lumberry", result.knownHeldItemId)
    }

    @Test
    fun `a Grass target protected by Flower Veil cannot acquire a new Yawn`() {
        val actor = fixture.mon(BattleSide.ALLY, 0, speed = 200)
        val target = fixture.mon(BattleSide.OPPONENT, 0, type = "grass", speed = 50)
        val partner = fixture.mon(BattleSide.OPPONENT, 1, ability = "flowerveil")
        val state = fixture.state(actor, target, partner, format = BattleFormat.DOUBLE)
        val action = statusMove("yawn", "yawn", BattleMoveEffectKind.VOLATILE_STATUS)
        val context = PublicBattleTacticalCalculator.calculate(fixture.context(state, action))
        val outcomes = PublicSingleTurnProjector.project(state, context.candidates.single(),
            BattleActionCandidate("foe-wait", BattleActionKind.WAIT), context)
        assertTrue(outcomes.isNotEmpty())
        assertTrue(outcomes.none { it.controlEffects.any { effect -> effect.kind == RecursiveControlEffectKind.YAWN } })
    }

    @Test
    fun `Misty Terrain does not protect a semi invulnerable Dig user from its own orb`() {
        val actor = fixture.mon(BattleSide.ALLY, 0, volatiles = setOf("dig")).copyState(knownHeldItemId = "flameorb")
        val state = fixture.state(actor).derive(field = BattleFieldStateView(null, BattleTimedEffectView("mistyterrain", 3),
            emptyList(), emptyList(), BattleSide.entries.associateWith { emptyList() }))
        assertEquals("brn", LocalEndTurnStateProjector.project(state).pokemon.single().statusId)
    }

    @Test
    fun `Corrosion allows a Steel holder to poison itself with its Toxic Orb`() {
        val actor = fixture.mon(BattleSide.ALLY, 0, type = "steel", ability = "corrosion").copyState(knownHeldItemId = "toxicorb")
        assertEquals("tox", LocalEndTurnStateProjector.project(fixture.state(actor)).pokemon.single().statusId)
    }

    @Test
    fun `Embargo suppresses Lum so a declared move status is not cured`() {
        val actor = fixture.mon(BattleSide.ALLY, 0, speed = 200)
        val target = fixture.mon(BattleSide.OPPONENT, 0, speed = 50, volatiles = setOf("embargo"))
            .copyState(knownHeldItemId = "lumberry")
        val state = fixture.state(actor, target)
        val action = statusMove("thunderwave", "par", BattleMoveEffectKind.STATUS)
        val context = PublicBattleTacticalCalculator.calculate(fixture.context(state, action))
        val outcomes = PublicSingleTurnProjector.project(state, context.candidates.single(),
            BattleActionCandidate("foe-wait", BattleActionKind.WAIT), context)
        assertTrue(outcomes.isNotEmpty())
        assertTrue(outcomes.all { it.stateBeforeResidual.pokemon.single { p -> p.battlePokemonId == target.battlePokemonId }.statusId == "par" })
        assertTrue(outcomes.all { it.state.pokemon.single { p -> p.battlePokemonId == target.battlePokemonId }.knownHeldItemId == "lumberry" })
    }

    private fun statusMove(id: String, value: String, kind: BattleMoveEffectKind) = BattleActionCandidate(
        "$id:0", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0, moveId = "cobblemon:$id",
        targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
        moveDetails = BattleMoveCandidateView("normal", BattleMoveDamageCategory.STATUS, 0.0, 100.0, 0, 10,
            effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                listOf(BattleMoveEffectView(kind, BattleMoveEffectTarget.SELECTED_TARGET, 1.0, valueId = value)), false)))
}
