package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.betterai.outcome.PublicActionOutcomeProjector
import jbro.cobblemon.mcc.betterai.outcome.PublicSingleTurnProjector
import jbro.cobblemon.mcc.betterai.policy.LocalBattleActionOutcomeEvaluator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalMegaCandidateEvaluationTest {
    @Test
    fun `mega speed can reverse the ordinary reply order in either direction`() {
        assertEquals(0.0, facts(context(baseSpeed = 333, megaSpeed = 441, foeSpeed = 400), mega = false).actsFirstProbability)
        assertEquals(1.0, facts(context(baseSpeed = 333, megaSpeed = 441, foeSpeed = 400)).actsFirstProbability)
        assertEquals(1.0, facts(context(baseSpeed = 333, megaSpeed = 311, foeSpeed = 320), mega = false).actsFirstProbability)
        assertEquals(0.0, facts(context(baseSpeed = 333, megaSpeed = 311, foeSpeed = 320)).actsFirstProbability)
    }

    @Test
    fun `mega gains priority from its new prankster ability`() {
        val status = move(mega = true, category = BattleMoveDamageCategory.STATUS, power = 0.0)
        val source = context(megaAbility = "prankster", baseSpeed = 100, megaSpeed = 100, foeSpeed = 200, action = status)
        assertEquals(0.0, facts(source, mega = false).actsFirstProbability)
        assertEquals(1.0, facts(source).actsFirstProbability)
    }

    @Test
    fun `huge power is priced on the activation turn`() {
        val source = context(megaAbility = "hugepower")
        val base = outcome(source, mega = false).expectedDamageFraction!!
        assertEquals(base * 2.0, outcome(source).expectedDamageFraction!!, 1e-9)
    }

    @Test
    fun `an unknown mega ability does not inherit the base technician`() {
        val source = context(baseAbility = "technician", megaAbility = null)
        assertTrue(outcome(source).expectedDamageFraction!! < outcome(source, mega = false).expectedDamageFraction!!)
    }

    @Test
    fun `mold breaker removes revealed levitate immunity on the activation turn`() {
        val source = context(megaAbility = "moldbreaker", foeAbility = "levitate", action = move(mega = true, type = "ground"))
        assertTrue(outcome(source, mega = false).publiclyNullified)
        assertFalse(outcome(source).publiclyNullified)
        assertTrue(outcome(source).expectedDamageFraction!! > 0.0)
    }

    @Test
    fun `ability shield still protects levitate from the new mold breaker`() {
        val source = context(megaAbility = "moldbreaker", foeAbility = "levitate", action = move(mega = true, type = "ground"))
        val protectedFoe = source.state.pokemon.last().copyState(knownHeldItemId = "abilityshield")
        val protected = source.copy(state = source.state.copyState(pokemon = listOf(source.state.pokemon.first(), protectedFoe)))
        assertTrue(outcome(protected).publiclyNullified)
        assertEquals(0.0, outcome(protected).expectedDamageFraction)
    }

    @Test
    fun `neutralizing gas suppresses the newly evolved drought`() {
        val source = context(megaAbility = "drought", foeAbility = "neutralizinggas",
            action = move(mega = true, id = "weatherball", category = BattleMoveDamageCategory.SPECIAL))
        assertEquals("normal", PublicBattleTacticalCalculator.calculate(source).candidates.single().moveDetails!!.typeId)
        assertEquals(outcome(source, mega = false).expectedDamageFraction, outcome(source).expectedDamageFraction)
    }

    @Test
    fun `garchompite Z uses Mega Z speed and its Dragon only typing`() {
        val source = context(baseSpeed = 333, foeSpeed = 400,
            action = move(mega = true, id = "earthpower", type = "ground", category = BattleMoveDamageCategory.SPECIAL, power = 90.0))
        val baseStats = BattleCombatStatRangesView.exact(3000, 200, 200, 259, 200, 333)
        val normalMega = BattlePokemonFormStateView("Mega", setOf("dragon", "ground"),
            BattleCombatStatRangesView.exact(3000, 200, 200, 339, 200, 311), abilityId = "sandforce")
        val megaZ = BattlePokemonFormStateView("Mega-Z", setOf("dragon"),
            BattleCombatStatRangesView.exact(3000, 200, 200, 381, 200, 441), abilityId = "levitate")
        val garchomp = BattlePokemonStateView(ALLY, BattleSide.ALLY, 0, "cobblemon:garchomp", "Normal", 100,
            1.0, null, emptyMap(), emptySet(), "roughskin", "mega_showdown:garchompite_z", false,
            setOf("dragon", "ground"), baseStats, mapOf("Mega" to normalMega, "Mega-Z" to megaZ))
        val battle = source.copy(state = source.state.copyState(pokemon = listOf(garchomp, source.state.pokemon.last())))
        assertEquals(0.0, facts(battle, mega = false).actsFirstProbability)
        assertEquals(1.0, facts(battle).actsFirstProbability)
        assertEquals(1.0, facts(battle).baseSameTypeAttackBonus)
        assertTrue(outcome(battle).expectedDamageFraction!! < outcome(battle, mega = false).expectedDamageFraction!!,
            "The 381 SpA without Ground STAB is slightly weaker than 259 SpA with Ground STAB")
    }

    @Test
    fun `pixilate changes the move type before the mega candidates facts are calculated`() {
        val source = context(megaAbility = "pixilate", megaTypes = setOf("fairy"), foeTypes = setOf("dragon"))
        val calculated = PublicBattleTacticalCalculator.calculate(source).candidates.single()
        assertEquals("fairy", calculated.moveDetails!!.typeId)
        val facts = requireNotNull(calculated.facts)
        assertEquals(1.5, facts.baseSameTypeAttackBonus)
        assertEquals(2.0, facts.typeChartMultiplier)
        // Both forms get 1.5x STAB here; Fairy's 2x chart and Pixilate's 1.2x boost are the difference.
        assertEquals(outcome(source, mega = false).expectedDamageFraction!! * 2.4, outcome(source).expectedDamageFraction!!, 1e-9)
    }

    @Test
    fun `drought starts before weather ball and is local to the mega candidate`() {
        val source = context(megaAbility = "drought", action = move(mega = true, id = "weatherball", category = BattleMoveDamageCategory.SPECIAL))
        val mega = PublicBattleTacticalCalculator.calculate(source)
        val base = PublicBattleTacticalCalculator.calculate(withoutMega(source))
        assertEquals("fire", mega.candidates.single().moveDetails!!.typeId)
        assertEquals("normal", base.candidates.single().moveDetails!!.typeId)
        assertTrue(outcome(source).expectedDamageFraction!! > outcome(source, mega = false).expectedDamageFraction!! * 2.0)
        assertNull(source.state.field.weather, "Evaluating a candidate must not mutate the input battle")
        assertEquals(mega.candidates.single().facts, PublicBattleTacticalCalculator.calculate(mega).candidates.single().facts)
    }

    @Test
    fun `no guard is used consistently by outcome accuracy and ranking`() {
        val source = context(megaAbility = "noguard", action = move(mega = true, accuracy = 50.0))
        assertEquals(outcome(source, mega = false).expectedDamageFraction!! * 2.0, outcome(source).expectedDamageFraction!!, 1e-9)
        val calculated = PublicBattleTacticalCalculator.calculate(source)
        val rankedOutcome = LocalBattleActionOutcomeEvaluator.evaluate(calculated.candidates.single(), calculated,
            null, BattleTrainerProfile.balanced())
        assertEquals(1.0, rankedOutcome.effectiveAccuracyProbability)
        assertEquals(outcome(source).expectedDamageFraction, rankedOutcome.expectedDamageFraction)
    }

    @Test
    fun `root mega damage agrees with the already evolved state for new ability and weather`() {
        for (ability in listOf("hugepower", "adaptability", "drought", "pixilate")) {
            val source = context(megaAbility = ability, megaTypes = if (ability == "pixilate") setOf("fairy") else setOf("normal"))
            val types = source.state.pokemon.first().knownFormStates.getValue("mega").knownTypeIds
            val evolved = BattlePokemonStateView(ALLY, BattleSide.ALLY, 0, "showdown:mawile", "mega", 100,
                1.0, null, emptyMap(), emptySet(), ability, "mawilite", false, types, stats(150))
            val weather = if (ability == "drought") BattleTimedEffectView("sunnyday", 5) else null
            val evolvedState = source.state.derive(pokemon = listOf(evolved, source.state.pokemon.last()),
                field = BattleFieldStateView(weather, null, emptyList(), emptyList(), BattleSide.entries.associateWith { emptyList() }))
            val alreadyEvolved = withoutMega(source).copy(state = evolvedState)
            assertEquals(outcome(alreadyEvolved).expectedDamageFraction, outcome(source).expectedDamageFraction, ability)
        }
    }

    @Test
    fun `a joint candidate activates the mega weather before calculating the partners move`() {
        val first = move(mega = true)
        val second = move(mega = false, id = "weatherball", category = BattleMoveDamageCategory.SPECIAL,
            actionId = "partner", actorSlot = 1)
        val joint = BattleActionCandidate("joint", BattleActionKind.COMPOSITE,
            componentActionIds = listOf(first.actionId, second.actionId), componentActions = listOf(first, second))
        val source = context(megaAbility = "drought", action = joint)
        val partner = BattlePokemonStateView(PARTNER, BattleSide.ALLY, 1, "showdown:venusaur", null, 100,
            1.0, null, emptyMap(), emptySet(), "chlorophyll", null, false, setOf("grass"), stats(100))
        val state = BattleStateView(BATTLE, BattleFormat.DOUBLE, 3, source.state.pokemon + partner,
            source.state.field, mapOf(BattleSide.ALLY to 2, BattleSide.OPPONENT to 1), emptyList(), emptyList())
        val calculatedPartner = PublicBattleTacticalCalculator.calculate(source.copy(state = state))
            .candidates.single().componentActions.last()
        assertEquals("fire", calculatedPartner.moveDetails!!.typeId)
        assertEquals(1.0, calculatedPartner.facts!!.actsFirstProbability)
    }

    @Test
    fun `new defensive levitate prevents the ground reply in turn search`() {
        val source = context(megaAbility = "levitate", action = move(mega = true, power = 1.0))
        val reply = move(mega = false, type = "ground", power = 80.0, actionId = "reply", targetSide = BattleSide.ALLY)
        val baseTurns = PublicSingleTurnProjector.project(source.state, withoutMega(source).candidates.single(), reply, source)
        val megaTurns = PublicSingleTurnProjector.project(source.state, source.candidates.single(), reply, source)
        assertTrue(baseTurns.isNotEmpty() && megaTurns.isNotEmpty())
        assertTrue(baseTurns.any { it.state.pokemon.first().hpFraction < 1.0 })
        assertTrue(megaTurns.all { it.state.pokemon.first().hpFraction == 1.0 })
    }

    private fun facts(source: BattleDecisionContext, mega: Boolean = true) =
        PublicBattleTacticalCalculator.calculate(if (mega) source else withoutMega(source)).candidates.single().facts!!

    private fun outcome(source: BattleDecisionContext, mega: Boolean = true): jbro.cobblemon.mcc.betterai.outcome.PublicActionOutcomeProjection {
        val calculated = PublicBattleTacticalCalculator.calculate(if (mega) source else withoutMega(source))
        return PublicActionOutcomeProjector.project(calculated.candidates.single(), calculated)
    }

    private fun withoutMega(source: BattleDecisionContext): BattleDecisionContext {
        val action = source.candidates.single()
        return source.copy(candidates = listOf(BattleActionCandidate(action.actionId, action.kind,
            action.actorSlot, action.moveSlot, action.moveId, action.targets, moveDetails = action.moveDetails)))
    }

    private fun context(
        baseAbility: String? = null, megaAbility: String? = null, foeAbility: String? = null,
        baseSpeed: Int = 150, megaSpeed: Int = 150, foeSpeed: Int = 150,
        megaTypes: Set<String> = setOf("normal"), foeTypes: Set<String> = setOf("normal"),
        action: BattleActionCandidate = move(mega = true),
    ): BattleDecisionContext {
        val mega = BattlePokemonFormStateView("mega", megaTypes, stats(megaSpeed), abilityId = megaAbility)
        val ally = BattlePokemonStateView(ALLY, BattleSide.ALLY, 0, "showdown:mawile", null, 100,
            1.0, null, emptyMap(), emptySet(), baseAbility, "mawilite", false, setOf("normal"), stats(baseSpeed), mapOf("mega" to mega))
        val exactFoe = stats(foeSpeed)
        val foeStats = BattleCombatStatRangesView(exactFoe.maxHp, exactFoe.attack, exactFoe.defence,
            exactFoe.specialAttack, exactFoe.specialDefence, exactFoe.speed, BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE)
        val foe = BattlePokemonStateView(FOE, BattleSide.OPPONENT, 0, "showdown:test", null, 100,
            1.0, null, emptyMap(), emptySet(), foeAbility, null, false, foeTypes, foeStats)
        val state = BattleStateView(BATTLE, BattleFormat.SINGLE, 3, listOf(ally, foe), BattleFieldStateView.empty(),
            mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1), emptyList(), emptyList())
        return BattleDecisionContext(BATTLE, state, listOf(action), Long.MAX_VALUE)
    }

    private fun move(mega: Boolean, id: String = "tackle", type: String = "normal", power: Double = 40.0,
        category: BattleMoveDamageCategory = BattleMoveDamageCategory.PHYSICAL, accuracy: Double = 100.0,
        actionId: String = "move", actorSlot: Int = 0, targetSide: BattleSide = BattleSide.OPPONENT) =
        BattleActionCandidate(actionId, BattleActionKind.USE_MOVE, actorSlot = actorSlot, moveSlot = 0, moveId = id,
            targets = listOf(BattleTargetSlot(targetSide, 0)),
            mechanic = if (mega) BattleMechanicCandidate("cobblemon:mega", null, null) else null,
            moveDetails = BattleMoveCandidateView(type, category, power, accuracy, 0, 10))

    private fun stats(speed: Int) = BattleCombatStatRangesView.exact(3000, 200, 200, 200, 200, speed)

    private companion object {
        val BATTLE = UUID.fromString("00000000-0000-0000-0000-000000003000")
        val ALLY = UUID.fromString("00000000-0000-0000-0000-000000003001")
        val FOE = UUID.fromString("00000000-0000-0000-0000-000000003002")
        val PARTNER = UUID.fromString("00000000-0000-0000-0000-000000003003")
    }
}
