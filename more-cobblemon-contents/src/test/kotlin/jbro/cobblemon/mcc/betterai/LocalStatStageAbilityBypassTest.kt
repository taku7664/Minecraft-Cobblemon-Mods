package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.evaluation.LocalStatStageMarginalEvaluator
import jbro.cobblemon.mcc.betterai.mechanics.LocalStatStageChange
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.betterai.outcome.PublicSingleTurnProjector
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalStatStageAbilityBypassTest {
    private val fixture = LocalEvaluationRegressionFixture

    @Test fun `root Charm value bypasses Clear Body with Mold Breaker`() = assertRootBypass("clearbody")
    @Test fun `root Charm value bypasses Contrary with Mold Breaker`() = assertRootBypass("contrary")
    @Test fun `root Charm value bypasses Mirror Armor with Mold Breaker`() = assertRootBypass("mirrorarmor")
    @Test fun `projected Charm bypasses Clear Body with Mold Breaker`() = assertTurnBypass("clearbody")
    @Test fun `projected Charm bypasses Contrary with Mold Breaker`() = assertTurnBypass("contrary")
    @Test fun `projected Charm bypasses Mirror Armor with Mold Breaker`() = assertTurnBypass("mirrorarmor")

    @Test fun `root Charm bypasses a grass target's Flower Veil`() = assertRootBypass("flowerveil", "grass")
    @Test fun `projected Charm bypasses a grass target's Flower Veil`() = assertTurnBypass("flowerveil", "grass")

    @Test fun `bypass does not erase each Defiant reaction`() = assertCommonBypass("defiant", 3, -1)
    @Test fun `bypass does not erase each Competitive reaction`() = assertCommonBypass("competitive", -1, 3)
    @Test fun `bypass does not erase Full Metal Body`() = assertCommonBypass("fullmetalbody", 0, 0)
    @Test fun `bypass removes the target's breakable Flower Veil`() = assertCommonBypass("flowerveil", -1, -1, "grass")

    @Test fun `projected Noble Roar keeps Defiant under Mold Breaker`() = assertProjectedNobleRoar("defiant", 3, -1)
    @Test fun `projected Noble Roar keeps Competitive under Mold Breaker`() = assertProjectedNobleRoar("competitive", -1, 3)

    @Test
    fun `Full Metal Body still blocks the projected and root Charm`() {
        val context = context("fullmetalbody")
        assertEquals(0.0, LocalStatStageMarginalEvaluator.candidateScore(context.candidates.single(), context, 1.0)!!, 1e-9)
        outcomes(context).forEach { assertEquals(0, stage(it.pokemon.single { pokemon -> pokemon.side == BattleSide.OPPONENT })) }
    }

    @Test
    fun `an active Ability Shield preserves Clear Body in root and projected stage changes`() {
        val context = context("clearbody", "abilityshield")
        assertEquals(0.0, LocalStatStageMarginalEvaluator.candidateScore(context.candidates.single(), context, 1.0)!!, 1e-9)
        assertTrue(outcomes(context).all { it.pokemon.all { pokemon -> stage(pokemon) == 0 } })
    }

    @Test
    fun `the common boost keeps a shielded breakable target ability`() {
        val own = fixture.mon(BattleSide.ALLY, 0, ability = "moldbreaker")
        val foe = fixture.mon(BattleSide.OPPONENT, 0, ability = "clearbody").copyState(knownHeldItemId = "abilityshield")
        val after = LocalStatStageChange.apply(fixture.state(own, foe), foe.battlePokemonId, own.battlePokemonId,
            mapOf("atk" to -2), ignoreTargetAbility = true)
        assertEquals(0, stage(after.pokemon.single { it.side == BattleSide.OPPONENT }))
    }

    @Test
    fun `a Flower Veil partner's Ability Shield preserves its protection`() {
        assertPartnerFlowerVeil(partnerItem = "abilityshield", targetItem = "", expected = 0)
    }

    @Test
    fun `the target's Ability Shield does not protect its unshielded Flower Veil partner`() {
        assertPartnerFlowerVeil(partnerItem = "", targetItem = "abilityshield", expected = -2)
    }

    private fun assertPartnerFlowerVeil(partnerItem: String, targetItem: String, expected: Int) {
        val base = context("pressure", targetItem, type = "grass")
        val partner = fixture.mon(BattleSide.OPPONENT, 1, ability = "flowerveil").copyState(knownHeldItemId = partnerItem)
        val context = base.copy(state = fixture.state(*(base.state.pokemon + partner).toTypedArray(), format = BattleFormat.DOUBLE))
        outcomes(context).forEach { outcome ->
            assertEquals(expected, stage(outcome.pokemon.single { it.side == BattleSide.OPPONENT && it.activeSlot == 0 }))
        }
    }

    private fun assertRootBypass(ability: String, type: String = "normal") {
        val context = context(ability, type = type)
        assertTrue(LocalStatStageMarginalEvaluator.candidateScore(context.candidates.single(), context, 1.0)!! > 0.0,
            "The target's public physical attack is weakened when the declared move bypasses $ability")
    }

    private fun assertTurnBypass(ability: String, type: String = "normal") {
        val context = context(ability, type = type)
        val states = outcomes(context)
        assertTrue(states.isNotEmpty())
        states.forEach {
            assertEquals(-2, stage(it.pokemon.single { pokemon -> pokemon.side == BattleSide.OPPONENT }))
            assertEquals(0, stage(it.pokemon.single { pokemon -> pokemon.side == BattleSide.ALLY }))
        }
    }

    private fun assertCommonBypass(ability: String, attack: Int, specialAttack: Int, type: String = "normal") {
        val own = fixture.mon(BattleSide.ALLY, 0, ability = "moldbreaker")
        val foe = fixture.mon(BattleSide.OPPONENT, 0, ability = ability, type = type)
        val after = LocalStatStageChange.apply(fixture.state(own, foe), foe.battlePokemonId, own.battlePokemonId,
            linkedMapOf("atk" to -1, "spa" to -1), ignoreTargetAbility = true)
        val target = after.pokemon.single { it.side == BattleSide.OPPONENT }
        assertEquals(attack, stage(target))
        assertEquals(specialAttack, stage(target, "special_attack"))
    }

    private fun assertProjectedNobleRoar(ability: String, attack: Int, specialAttack: Int) {
        val context = context(ability, stages = linkedMapOf("atk" to -1, "spa" to -1), moveId = "nobleroar")
        outcomes(context).forEach {
            val target = it.pokemon.single { pokemon -> pokemon.side == BattleSide.OPPONENT }
            assertEquals(attack, stage(target))
            assertEquals(specialAttack, stage(target, "special_attack"))
        }
    }

    private fun outcomes(context: BattleDecisionContext): List<BattleStateView> = PublicSingleTurnProjector.project(
        context.state, context.candidates.single(), BattleActionCandidate("wait", BattleActionKind.WAIT), context)
        .map { it.stateBeforeResidual }

    private fun context(ability: String, item: String = "", type: String = "normal",
        stages: Map<String, Int> = mapOf("atk" to -2), moveId: String = "charm"): BattleDecisionContext {
        val own = fixture.mon(BattleSide.ALLY, 0, ability = "moldbreaker", speed = 200)
        val foe = fixture.mon(BattleSide.OPPONENT, 0, ability = ability, type = type, speed = 100).copyState(knownHeldItemId = item)
        val charm = BattleActionCandidate("$moveId:0", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 1, moveId = moveId,
            targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)), moveDetails = BattleMoveCandidateView(if (moveId == "charm") "fairy" else "normal",
                BattleMoveDamageCategory.STATUS, 0.0, 100.0, 0, 10, effects = BattleMoveEffectsView(
                    BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, listOf(BattleMoveEffectView(BattleMoveEffectKind.STAT_STAGE,
                        BattleMoveEffectTarget.SELECTED_TARGET, 1.0, statStages = stages)), false)))
        fun attack(knowledge: BattlePublicMoveKnowledge) = BattlePublicMoveOptionView("tackle",
            requireNotNull(fixture.attack().moveDetails), knowledge)
        val catalog = BattlePublicActionCatalogView(listOf(
            BattlePokemonActionCatalogView(own.battlePokemonId, listOf(attack(BattlePublicMoveKnowledge.EXACT_OWN),
                BattlePublicMoveOptionView(moveId, requireNotNull(charm.moveDetails), BattlePublicMoveKnowledge.EXACT_OWN)), moveSetComplete = true),
            BattlePokemonActionCatalogView(foe.battlePokemonId, listOf(attack(BattlePublicMoveKnowledge.PUBLICLY_REVEALED)), moveSetComplete = true)))
        return PublicBattleTacticalCalculator.calculate(fixture.context(fixture.state(own, foe), charm)
            .copy(publicActionCatalog = catalog))
    }

    private fun stage(pokemon: BattlePokemonStateView, stat: String = "attack") = pokemon.statStages.entries
        .firstOrNull { LocalStatStageChange.normalise(it.key) == stat }?.value ?: 0
}
