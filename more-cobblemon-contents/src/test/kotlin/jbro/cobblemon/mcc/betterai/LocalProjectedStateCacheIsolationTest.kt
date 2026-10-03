package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.mcc.betterai.evaluation.LocalLeafMatchups
import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalProjectedStateCacheIsolationTest {
    @Test
    fun `a changed public learnset cannot reuse the previous hypothetical move list`() {
        val base = context()
        val state = base.state.copyState(pokemon = base.state.pokemon.map {
            if (it.side == BattleSide.OPPONENT) it.copyState(knownMoveIds = emptySet()) else it
        })
        val foe = state.pokemon.single { it.side == BattleSide.OPPONENT }
        fun catalog(move: String, type: String) = BattlePublicActionCatalogView(emptyList(), candidatePools = listOf(
            BattlePublicMoveCandidatePoolView(foe.battlePokemonId, foe.speciesId, foe.formId, setOf(move), "fixture",
                mapOf(move to base.candidates.single().moveDetails!!.copy(typeId = type)))))
        val cache = LocalProjectedActionCalculationCache()
        fun actions(catalog: BattlePublicActionCatalogView) = cache.slotActions(state, BattleSide.OPPONENT, catalog, true) {
            PublicFutureActionFactory.slotActions(state, BattleSide.OPPONENT, catalog, true)
        }
        assertTrue(actions(catalog("surf", "water")).any { it.moveId == "surf" })
        val changed = catalog("icebeam", "ice")
        assertTrue(PublicFutureActionFactory.slotActions(state, BattleSide.OPPONENT, changed, true).any { it.moveId == "icebeam" })
        assertTrue(actions(changed).any { it.moveId == "icebeam" })
        assertEquals(2, cache.slotActionListsBuilt)
    }

    @Test
    fun `a different transformed Tera type cannot reuse an action with the same mechanic ID`() {
        val context = context()
        fun action(type: String): BattleActionCandidate {
            val base = context.candidates.single()
            return BattleActionCandidate(base.actionId, base.kind, base.actorSlot, base.moveSlot, base.moveId,
                targets = base.targets, moveDetails = base.moveDetails, mechanic = BattleMechanicCandidate(
                    "tera", BattleTargetSlot(BattleSide.ALLY, 0), null, transformedActorTypeIds = setOf(type)))
        }
        val ground = action("ground")
        val normal = action("normal")
        val cache = LocalProjectedActionCalculationCache()
        fun calculated(action: BattleActionCandidate) = cache.getOrCalculate(context.state, BattleSide.ALLY, action) {
            PublicBattleTacticalCalculator.calculate(context.copy(candidates = listOf(action)))
        }.candidates.single().facts?.standardDamageFractionRange
        val boosted = requireNotNull(calculated(ground))
        val fresh = requireNotNull(PublicBattleTacticalCalculator.calculate(context.copy(candidates = listOf(normal)))
            .candidates.single().facts?.standardDamageFractionRange)
        assertTrue(boosted.minimum > fresh.minimum + 0.05, "The fresh Tera calculation must distinguish the new type")
        assertEquals(fresh, calculated(normal))
        assertEquals(2, cache.calculationsPerformed)
    }

    @Test
    fun `an equivalent projected position still reuses its calculation`() {
        val context = context()
        val cache = LocalProjectedActionCalculationCache()
        val first = cache.getOrCalculate(context.state, BattleSide.ALLY, context.candidates.single()) {
            PublicBattleTacticalCalculator.calculate(context)
        }
        val second = cache.getOrCalculate(context.state.copyState(), BattleSide.ALLY, context.candidates.single()) {
            fail<BattleDecisionContext>("Equivalent public inputs should share a calculation")
        }
        assertSame(first, second)
        assertEquals(1, cache.calculationsPerformed)
    }

    @Test
    fun `a lost Choice Band cannot reuse an earlier leaf matchup`() {
        val base = context()
        val state = base.state.copyState(pokemon = base.state.pokemon.map {
            if (it.side == BattleSide.ALLY) it.copyState(knownHeldItemId = "choiceband") else it
        })
        val context = base.copy(state = state, publicActionCatalog = BattlePublicActionCatalogView(
            state.pokemon.map { pokemon -> BattlePokemonActionCatalogView(pokemon.battlePokemonId,
                listOf(BattlePublicMoveOptionView("earthquake", base.candidates.single().moveDetails!!,
                    if (pokemon.side == BattleSide.ALLY) BattlePublicMoveKnowledge.EXACT_OWN
                    else BattlePublicMoveKnowledge.PUBLICLY_REVEALED)), moveSetComplete = true) }))
        val changed = state.copyState(pokemon = state.pokemon.map {
            if (it.side == BattleSide.ALLY) it.copyState(knownHeldItemId = "") else it
        })
        val cache = LocalProjectedActionCalculationCache()
        val original = requireNotNull(LocalLeafMatchups.evaluate(state, context, cache) { true })
        val fresh = requireNotNull(LocalLeafMatchups.evaluate(changed, context, LocalProjectedActionCalculationCache()) { true })
        assertTrue(original.field > fresh.field + 0.1, "The fresh matchup must distinguish the known item loss")
        val cached = requireNotNull(LocalLeafMatchups.evaluate(changed, context, cache) { true })
        assertEquals(fresh.field, cached.field, 1e-9)
        assertEquals(fresh.team, cached.team, 1e-9)
    }

    @Test
    fun `an ability swap cannot reuse a tactical context for the former ability`() {
        val context = context()
        val changed = context.state.copyState(pokemon = context.state.pokemon.map {
            if (it.side == BattleSide.ALLY) it.copyState(knownAbilityId = "hugepower") else it
        })
        val cache = LocalProjectedActionCalculationCache()
        fun calculated(state: BattleStateView) = cache.getOrCalculate(state, BattleSide.ALLY, context.candidates.single()) {
            PublicBattleTacticalCalculator.calculate(context.copy(state = state))
        }
        calculated(context.state)
        assertEquals("hugepower", calculated(changed).state.pokemon.first().knownAbilityId)
        assertEquals(2, cache.calculationsPerformed)
    }

    @Test
    fun `a changed bounded offensive stat cannot reuse an earlier projected calculation`() {
        val context = context()
        val changed = context.state.copyState(pokemon = context.state.pokemon.map {
            if (it.side == BattleSide.ALLY) it.copyState(combatStats = stats(200)) else it
        })
        verify(context, changed)
    }

    private fun verify(context: BattleDecisionContext, changed: BattleStateView) {
        val cache = LocalProjectedActionCalculationCache()
        fun calculated(state: BattleStateView) = cache.getOrCalculate(state, BattleSide.ALLY, context.candidates.single()) {
            PublicBattleTacticalCalculator.calculate(context.copy(state = state))
        }.candidates.single().facts?.standardDamageFractionRange
        val original = requireNotNull(calculated(context.state))
        val fresh = requireNotNull(PublicBattleTacticalCalculator.calculate(context.copy(state = changed))
            .candidates.single().facts?.standardDamageFractionRange)
        assertTrue(fresh.minimum > original.minimum * 1.5, "The uncached public mechanics must distinguish the states")
        assertEquals(fresh, calculated(changed))
        assertEquals(2, cache.calculationsPerformed)
    }

    private fun context(): BattleDecisionContext {
        fun mon(n: Long, side: BattleSide) = BattlePokemonStateView(UUID(0, n), side, 0, "test", null, 50,
            1.0, null, emptyMap(), setOf("earthquake"), null, "", false, setOf("ground"),
            if (side == BattleSide.ALLY) stats(100) else BattleCombatStatRangesView(
                BattleIntegerRange(200, 201), BattleIntegerRange(100, 101), BattleIntegerRange(100, 101),
                BattleIntegerRange(100, 101), BattleIntegerRange(100, 101), BattleIntegerRange(100, 101),
                BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE))
        val state = BattleStateView(UUID(0, 99), BattleFormat.SINGLE, 3,
            listOf(mon(1, BattleSide.ALLY), mon(2, BattleSide.OPPONENT)), BattleFieldStateView.empty(),
            mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1), emptyList(), emptyList())
        val action = BattleActionCandidate("earthquake", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
            moveId = "earthquake", targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
            moveDetails = BattleMoveCandidateView("ground", BattleMoveDamageCategory.PHYSICAL, 100.0, 100.0, 0, 10))
        return BattleDecisionContext(UUID(0, 100), state, listOf(action), Long.MAX_VALUE)
    }

    private fun stats(attack: Int) = BattleCombatStatRangesView.exact(200, attack, 100, 100, 100, 100)
}
