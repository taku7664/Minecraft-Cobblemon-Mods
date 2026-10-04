package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.mechanics.*
import jbro.cobblemon.mcc.betterai.state.LocalEntryAbilityProjector
import jbro.cobblemon.mcc.betterai.state.LocalEndTurnStateProjector
import jbro.cobblemon.mcc.betterai.state.LocalTraitResidualBranches
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalRemainingTraitMechanicsTest {
    @Test fun `Sniper changes critical damage only`() {
        assertEquals(1.5, multiplier("sniper", setOf("criticalhit")), 1e-9)
        assertEquals(1.0, multiplier("sniper"), 1e-9)
    }
    @Test fun `Analytic requires every other pending move to be exhausted`() {
        assertEquals(1.3, multiplier("analytic", setOf("better_ai:analytic_active")), 1e-9)
        assertEquals(1.0, multiplier("analytic"), 1e-9)
    }
    @Test fun `Stakeout requires target entry this turn`() {
        assertEquals(2.0, multiplier("stakeout", foeVolatiles = setOf("better_ai:entered_this_turn")), 1e-9)
        assertEquals(1.0, multiplier("stakeout"), 1e-9)
    }
    @Test fun `Flash Fire boost requires the absorption volatile`() {
        assertEquals(1.5, multiplier("flashfire", userVolatiles = setOf("flashfire"), type = "fire"), 1e-9)
        assertEquals(1.0, multiplier("flashfire", type = "fire"), 1e-9)
    }
    @Test fun `charge doubles only Electric attacks`() {
        assertEquals(2.0, multiplier("electromorphosis", userVolatiles = setOf("charge"), type = "electric"), 1e-9)
        assertEquals(1.0, multiplier("electromorphosis", userVolatiles = setOf("charge")), 1e-9)
    }
    @Test fun `Slow Start actual entry state halves attack and speed for five turns`() {
        val user = mon(BattleSide.ALLY, ability = "slowstart")
        var current = LocalEntryAbilityProjector.project(state(user, mon(BattleSide.OPPONENT)), user.battlePokemonId)
        assertEquals(0.5, multiplierIn(current), 1e-9)
        assertEquals(50 to 50, LocalPublicTurnOrder.effectiveSpeed(current, current.pokemon.first()))
        repeat(5) { current = LocalEndTurnStateProjector.project(current) }
        assertEquals(1.0, multiplierIn(current), 1e-9)
        assertEquals(100 to 100, LocalPublicTurnOrder.effectiveSpeed(current, current.pokemon.first()))
    }
    @Test fun `Unburden requires activation and an empty item slot`() {
        fun speed(item: String?, volatiles: Set<String>) = mon(BattleSide.ALLY, ability = "unburden", item = item, volatiles = volatiles).let {
            LocalPublicTurnOrder.effectiveSpeed(state(it, mon(BattleSide.OPPONENT)), it)
        }
        assertEquals(100 to 100, speed(null, emptySet()))
        assertEquals(200 to 200, speed(null, setOf("unburden")))
        assertEquals(100 to 100, speed("sitrusberry", setOf("unburden")))
    }
    @Test fun `Custap is guaranteed fractional priority in its actual pinch range`() {
        val user = mon(BattleSide.ALLY, item = "custapberry", hp = .25)
        val current = state(user, mon(BattleSide.OPPONENT))
        assertEquals(1.0, LocalPublicTurnOrder.fractionalPriorityChance(current, BattleSide.ALLY, attack()), 1e-9)
    }
    @Test fun `Unnerve prevents Custap and same-action Lum consumption`() {
        val user = mon(BattleSide.ALLY, item = "custapberry", hp = .25)
        val foe = mon(BattleSide.OPPONENT, ability = "unnerve")
        assertEquals(0.0, LocalPublicTurnOrder.fractionalPriorityChance(state(user, foe), BattleSide.ALLY, attack()), 1e-9)
        val poisoned = user.copyState(knownHeldItemId = "lumberry", statusId = "psn")
        assertEquals("lumberry", LocalPublicStatusBerry.afterUpdate(state(poisoned, foe)).pokemon.first().knownHeldItemId)
    }
    @Test fun `Sitrus fires on Update even if the holder was already below half`() {
        val user = mon(BattleSide.ALLY, item = "sitrusberry", hp = .2)
        val result = LocalPublicStatusBerry.afterUpdate(state(user, mon(BattleSide.OPPONENT))).pokemon.first()
        assertEquals(.45, result.hpFraction, 1e-9)
        assertEquals("", result.knownHeldItemId)
    }
    @Test fun `Ripen doubles actual Sitrus healing and Cheek Pouch adds its heal`() {
        val ripen = mon(BattleSide.ALLY, item = "sitrusberry", ability = "ripen", hp = .25)
        val pouch = mon(BattleSide.OPPONENT, item = "sitrusberry", ability = "cheekpouch", hp = .25)
        val result = LocalPublicStatusBerry.afterUpdate(state(ripen, pouch))
        assertEquals(.75, result.pokemon.first().hpFraction, 1e-9)
        assertEquals(.25 + .25 + 53.0 / 160.0, result.pokemon.last().hpFraction, 1e-9)
    }
    @Test fun `Unnerve suppresses healing berries and dead Unnerve does not`() {
        val user = mon(BattleSide.ALLY, item = "sitrusberry", hp = .2)
        val foe = mon(BattleSide.OPPONENT, ability = "unnerve")
        assertEquals(.2, LocalPublicStatusBerry.afterUpdate(state(user, foe)).pokemon.first().hpFraction, 1e-9)
        assertEquals(.45, LocalPublicStatusBerry.afterUpdate(state(user, foe.copyState(fainted = true, hpFraction = 0.0))).pokemon.first().hpFraction, 1e-9)
    }
    @Test fun `Anger Point uses actual critical hit and normal stage rules`() {
        val user = mon(BattleSide.ALLY)
        val foe = mon(BattleSide.OPPONENT, ability = "angerpoint", stages = mapOf("attack" to -4))
        val before = state(user, foe)
        val after = before.copyState(pokemon = listOf(user, foe.copyState(hpFraction = .8)))
        val result = LocalAfterHitReactions.apply(before, after, user.battlePokemonId, foe.battlePokemonId, attack(setOf("criticalhit")), .2)
        assertEquals(6, result.pokemon.last().statStages["attack"])
    }
    @Test fun `Electromorphosis adds charge only after direct damaging hit`() {
        val user = mon(BattleSide.ALLY)
        val foe = mon(BattleSide.OPPONENT, ability = "electromorphosis")
        val before = state(user, foe)
        val after = before.copyState(pokemon = listOf(user, foe.copyState(hpFraction = .8)))
        val result = LocalAfterHitReactions.apply(before, after, user.battlePokemonId, foe.battlePokemonId, attack(), .2)
        assertTrue("charge" in result.pokemon.last().knownVolatileEffectIds)
        assertFalse("charge" in LocalAfterHitReactions.apply(before, after, user.battlePokemonId, foe.battlePokemonId, attack(), 0.0).pokemon.last().knownVolatileEffectIds)
    }
    @Test fun `Moody retains all twenty actual different raise and drop combinations`() {
        val user = mon(BattleSide.ALLY, ability = "moody")
        val results = LocalTraitResidualBranches.project(state(user, mon(BattleSide.OPPONENT)))
        assertEquals(20, results.size)
        assertEquals(1.0, results.sumOf { it.probability }, 1e-9)
        results.forEach { result ->
            val stages = result.state.pokemon.first().statStages
            assertEquals(listOf(-1, 2), stages.values.sorted())
            assertFalse("accuracy" in stages || "evasion" in stages)
            assertEquals(.05, result.probability, 1e-9)
        }
    }
    @Test fun `Moody at upper caps still lowers one real stat and never chooses accuracy`() {
        val stages = mapOf("attack" to 6, "defence" to 6, "special_attack" to 6, "special_defence" to 6, "speed" to 6)
        val user = mon(BattleSide.ALLY, ability = "moody", stages = stages)
        val results = LocalTraitResidualBranches.project(state(user, mon(BattleSide.OPPONENT)))
        assertEquals(5, results.size)
        results.forEach { assertEquals(listOf(5, 6, 6, 6, 6), it.state.pokemon.first().statStages.values.sorted()) }
    }
    @Test fun `Harvest restores a recorded consumed berry with actual half probability`() {
        val user = mon(BattleSide.ALLY, ability = "harvest", volatiles = setOf(LocalBerryMechanics.LAST_CONSUMED_ITEM + "sitrusberry"), hp = .8)
        val results = LocalTraitResidualBranches.project(state(user, mon(BattleSide.OPPONENT)))
        assertEquals(2, results.size)
        assertEquals(listOf(null, "sitrusberry"), results.map { it.state.pokemon.first().knownHeldItemId })
        assertEquals(listOf(.5, .5), results.map { it.probability })
        assertNull(LocalBerryMechanics.lastConsumedItem(results.last().state.pokemon.first()))
    }
    @Test fun `Harvest is guaranteed in sun and restored Sitrus immediately fires at low HP`() {
        val user = mon(BattleSide.ALLY, ability = "harvest", volatiles = setOf(LocalBerryMechanics.LAST_CONSUMED_ITEM + "sitrusberry"), hp = .2)
        val initial = state(user, mon(BattleSide.OPPONENT))
        val sunny = initial.derive(field = BattleFieldStateView(BattleTimedEffectView("sunnyday", 3), null, emptyList(), emptyList(), BattleSide.entries.associateWith { emptyList() }))
        val result = LocalTraitResidualBranches.project(sunny).single()
        assertEquals(1.0, result.probability, 1e-9)
        assertEquals(.45, result.state.pokemon.first().hpFraction, 1e-9)
        assertEquals("", result.state.pokemon.first().knownHeldItemId)
    }
    @Test fun `Harvest cannot recreate an unobserved consumed item`() {
        val user = mon(BattleSide.ALLY, ability = "harvest")
        val result = LocalTraitResidualBranches.project(state(user, mon(BattleSide.OPPONENT))).single()
        assertNull(result.state.pokemon.first().knownHeldItemId)
        assertEquals(1.0, result.probability, 1e-9)
    }
    @Test fun `Trace samples both different publicly known foes and runs copied entry weather`() {
        val user = mon(BattleSide.ALLY, ability = "trace")
        val dry = mon(BattleSide.OPPONENT, ability = "drought")
        val wet = mon(BattleSide.OPPONENT, ability = "drizzle").copyState(activeSlot = 1)
        val results = LocalEntryAbilityProjector.projectOutcomes(state(user, dry, wet), user.battlePokemonId)
        assertEquals(setOf("drought", "drizzle"), results.map { it.state.pokemon.first().knownAbilityId }.toSet())
        assertEquals(setOf("sunnyday", "raindance"), results.map { it.state.field.weather?.effectId }.toSet())
        results.forEach { assertEquals(.5, it.probability, 1e-9); assertEquals("trace", it.state.pokemon.first().knownBaseAbilityId) }
    }
    @Test fun `unrevealed Trace source branches only through publicly legal species abilities`() {
        val user = mon(BattleSide.ALLY, ability = "trace")
        val foe = mon(BattleSide.OPPONENT, species = "showdown:vaporeon")
        val results = LocalEntryAbilityProjector.projectOutcomes(state(user, foe), user.battlePokemonId)
        assertEquals(setOf("waterabsorb", "hydration"), results.map { it.state.pokemon.first().knownAbilityId }.toSet())
        results.forEach { assertEquals(it.state.pokemon.first().knownAbilityId, it.state.pokemon.last().knownAbilityId) }
        assertEquals(1.0, results.sumOf { it.probability }, 1e-9)
    }
    @Test fun `unrevealed Trace resolves the publicly visible form rather than base species`() {
        val user = mon(BattleSide.ALLY, ability = "trace")
        val foe = mon(BattleSide.OPPONENT, species = "showdown:giratina", form = "origin")
        val results = LocalEntryAbilityProjector.projectOutcomes(state(user, foe), user.battlePokemonId)
        assertEquals("levitate", results.single().state.pokemon.first().knownAbilityId)
    }
    @Test fun `a waiting Trace copies a newly eligible foe on Update without resetting entry age`() {
        val user = mon(BattleSide.ALLY, ability = "trace")
        val foe = mon(BattleSide.OPPONENT, ability = "drought")
        val result = LocalEntryAbilityProjector.updateOutcomes(state(user, foe)).single().state
        assertEquals("drought", result.pokemon.first().knownAbilityId)
        assertEquals("sunnyday", result.field.weather?.effectId)
        assertFalse(LocalReactiveAbilityState.ENTERED_THIS_TURN in result.pokemon.first().knownVolatileEffectIds)
    }
    @Test fun `copying two foes with the same ability does not duplicate its effect`() {
        val user = mon(BattleSide.ALLY, ability = "trace")
        val one = mon(BattleSide.OPPONENT, ability = "intimidate")
        val two = mon(BattleSide.OPPONENT, ability = "intimidate").copyState(activeSlot = 1)
        val results = LocalEntryAbilityProjector.projectOutcomes(state(user, one, two), user.battlePokemonId)
        results.forEach { result ->
            assertEquals(-1, result.state.pokemon[1].statStages["attack"])
            assertEquals(-1, result.state.pokemon[2].statStages["attack"])
        }
    }
    @Test fun `Illusion keeps real typing and stats and ends only when direct damage lands`() {
        val user = mon(BattleSide.ALLY, ability = "illusion", species = "showdown:zoroark")
        val disguise = mon(BattleSide.ALLY, species = "showdown:blissey").copyState(activeSlot = null)
        val foe = mon(BattleSide.OPPONENT)
        val entered = LocalEntryAbilityProjector.project(state(user, disguise, foe), user.battlePokemonId)
        assertTrue(entered.pokemon.first().knownVolatileEffectIds.any { it.startsWith(LocalReactiveAbilityState.ILLUSION_AS) })
        assertEquals(user.speciesId, entered.pokemon.first().speciesId)
        assertEquals(user.knownTypeIds, entered.pokemon.first().knownTypeIds)
        val after = entered.copyState(pokemon = entered.pokemon.map { if (it.battlePokemonId == user.battlePokemonId) it.copyState(hpFraction = .8) else it })
        val result = LocalAfterHitReactions.apply(entered, after, foe.battlePokemonId, user.battlePokemonId, attack(), .2)
        assertFalse(result.pokemon.first().knownVolatileEffectIds.any { it.startsWith(LocalReactiveAbilityState.ILLUSION_AS) })
    }
    @Test fun `berry consumption activates Unburden and consumption history survives bench copies`() {
        val user = mon(BattleSide.ALLY, ability = "unburden", item = "sitrusberry", hp = .25)
        val after = LocalBerryMechanics.afterUpdate(state(user, mon(BattleSide.OPPONENT)))
        val holder = after.pokemon.first()
        assertTrue("unburden" in holder.knownVolatileEffectIds)
        val benched = holder.copyState(activeSlot = null)
        assertEquals("sitrusberry", LocalBerryMechanics.lastConsumedItem(benched))
        assertFalse("unburden" in benched.knownVolatileEffectIds)
    }
    @Test fun `Quick Draw makes Custap consumption conditional but both alternatives move first`() {
        val user = mon(BattleSide.ALLY, ability = "quickdraw", item = "custapberry", hp = .25)
        val before = state(user, mon(BattleSide.OPPONENT))
        val results = LocalReactiveAbilityState.beforeAction(before, BattleSide.ALLY, attack())
        assertEquals(listOf(.3, .7), results.map { it.probability })
        assertEquals(listOf("custapberry", ""), results.map { it.state.pokemon.first().knownHeldItemId })
        assertEquals(1.0, LocalPublicTurnOrder.fractionalPriorityChance(before, BattleSide.ALLY, attack()), 1e-9)
    }
    @Test fun `queue consumption preserves Custap priority after its item is gone`() {
        val user = mon(BattleSide.ALLY, item = "custapberry", hp = .25)
        val prepared = LocalReactiveAbilityState.beforeAction(state(user, mon(BattleSide.OPPONENT)), BattleSide.ALLY, attack()).single().state
        assertEquals("", prepared.pokemon.first().knownHeldItemId)
        assertEquals(1.0, LocalPublicTurnOrder.fractionalPriorityChance(prepared, BattleSide.ALLY, attack()), 1e-9)
    }
    @Test fun `Custap checked above threshold cannot start fractional priority after later damage`() {
        val user = mon(BattleSide.ALLY, item = "custapberry", hp = .5)
        val prepared = LocalReactiveAbilityState.beforeAction(state(user, mon(BattleSide.OPPONENT)), BattleSide.ALLY, attack()).single().state
        val damaged = prepared.copyState(pokemon = prepared.pokemon.map { if (it.battlePokemonId == user.battlePokemonId) it.copyState(hpFraction = .125) else it })
        assertEquals(0.0, LocalPublicTurnOrder.fractionalPriorityChance(damaged, BattleSide.ALLY, attack()), 1e-9)
    }
    @Test fun `ordinary item loss activates Unburden without fabricating Harvest consumption history`() {
        val user = mon(BattleSide.ALLY, ability = "unburden", item = "sitrusberry")
        val before = state(user, mon(BattleSide.OPPONENT))
        val removed = before.copyState(pokemon = before.pokemon.map { if (it.battlePokemonId == user.battlePokemonId) it.copyState(knownHeldItemId = null) else it })
        val activated = LocalReactiveAbilityState.afterAction(before, removed, null, attack())
        assertTrue("unburden" in activated.pokemon.first().knownVolatileEffectIds)
        assertNull(LocalBerryMechanics.lastConsumedItem(activated.pokemon.first()))
        val gained = activated.copyState(pokemon = activated.pokemon.map { if (it.battlePokemonId == user.battlePokemonId) it.copyState(knownHeldItemId = "leftovers") else it })
        assertFalse("unburden" in LocalReactiveAbilityState.afterAction(activated, gained, null, attack()).pokemon.first().knownVolatileEffectIds)
    }
    @Test fun `Electric move consumes charge but another type keeps the charge`() {
        val user = mon(BattleSide.ALLY, volatiles = setOf("charge"))
        val before = state(user, mon(BattleSide.OPPONENT))
        assertFalse("charge" in LocalReactiveAbilityState.afterAction(before, before, user.battlePokemonId, attack(type = "electric")).pokemon.first().knownVolatileEffectIds)
        assertTrue("charge" in LocalReactiveAbilityState.afterAction(before, before, user.battlePokemonId, attack()).pokemon.first().knownVolatileEffectIds)
    }
    @Test fun `Galvanize converted Electric attack consumes charge and unexecuted move keeps it`() {
        val user = mon(BattleSide.ALLY, ability = "galvanize", volatiles = setOf("charge"))
        val before = state(user, mon(BattleSide.OPPONENT))
        assertFalse("charge" in LocalReactiveAbilityState.afterAction(before, before, user.battlePokemonId, attack()).pokemon.first().knownVolatileEffectIds)
        assertTrue("charge" in LocalReactiveAbilityState.afterAction(before, before, user.battlePokemonId, BattleActionCandidate("skip", BattleActionKind.WAIT)).pokemon.first().knownVolatileEffectIds)
    }
    @Test fun `Heal Block prevents berry and residual heals and keeps the HP berry uneaten`() {
        // Showdown's HP berries ask TryHeal in TryEatItem, which Heal Block refuses, so the berry stays held.
        val user = mon(BattleSide.ALLY, ability = "cheekpouch", item = "sitrusberry", hp = .25, volatiles = setOf("healblock"))
        val before = state(user, mon(BattleSide.OPPONENT))
        val after = LocalBerryMechanics.afterUpdate(before)
        assertEquals(.25, after.pokemon.first().hpFraction, 1e-9)
        assertEquals("sitrusberry", after.pokemon.first().knownHeldItemId)
        val leftover = user.copyState(knownHeldItemId = "leftovers")
        assertEquals(.25, LocalEndTurnStateProjector.project(state(leftover, mon(BattleSide.OPPONENT))).pokemon.first().hpFraction, 1e-9)
    }
    private fun multiplier(ability: String, tags: Set<String> = emptySet(), userVolatiles: Set<String> = emptySet(), foeVolatiles: Set<String> = emptySet(), type: String = "normal"): Double =
        multiplierIn(state(mon(BattleSide.ALLY, ability = ability, volatiles = userVolatiles), mon(BattleSide.OPPONENT, volatiles = foeVolatiles)), attack(tags, type))
    private fun multiplierIn(state: BattleStateView, action: BattleActionCandidate = attack()): Double = LocalPublicMechanicsKernel.projectMove(action,
        BattleDecisionContext(UUID.randomUUID(), state, listOf(action), Long.MAX_VALUE, BattleTacticalMemoryView.empty())).knownDamageMultiplier
    private fun attack(tags: Set<String> = emptySet(), type: String = "normal") = BattleActionCandidate("tackle", BattleActionKind.USE_MOVE,
        actorSlot = 0, moveSlot = 0, moveId = "tackle", tags = tags, targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
        moveDetails = BattleMoveCandidateView(typeId = type, damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 40.0, accuracy = 100.0,
            priority = 0, currentPp = 10, targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT))
    private fun state(vararg pokemon: BattlePokemonStateView) = BattleStateView(UUID.randomUUID(), if (pokemon.any { it.activeSlot == 1 }) BattleFormat.DOUBLE else BattleFormat.SINGLE, 2, pokemon.toList(),
        BattleFieldStateView(null, null, emptyList(), emptyList(), BattleSide.entries.associateWith { emptyList() }),
        BattleSide.entries.associateWith { side -> pokemon.count { it.side == side && !it.fainted } }, emptyList(), emptyList())
    private fun mon(side: BattleSide, ability: String? = null, item: String? = null, hp: Double = 1.0, volatiles: Set<String> = emptySet(), stages: Map<String, Int> = emptyMap(), species: String = "showdown:probe", form: String? = null) =
        BattlePokemonStateView(UUID.randomUUID(), side, 0, species, form, 100, hp, null, stages, emptySet(), ability, item, false,
            knownTypeIds = setOf("normal"), knownVolatileEffectIds = volatiles,
            combatStats = BattleCombatStatRangesView(BattleIntegerRange(160, 160), BattleIntegerRange(100, 100), BattleIntegerRange(100, 100),
                BattleIntegerRange(100, 100), BattleIntegerRange(100, 100), BattleIntegerRange(100, 100), BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE))
}
