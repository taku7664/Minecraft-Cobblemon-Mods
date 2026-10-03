package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.mcc.betterai.mechanics.LocalAfterHitReactions
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicMechanicsKernel
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicMoveDamageInputs
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicStatusImmunity
import jbro.cobblemon.mcc.betterai.mechanics.LocalStatStageChange
import jbro.cobblemon.mcc.betterai.outcome.PublicSingleTurnProjector
import jbro.cobblemon.mcc.betterai.state.LocalEndTurnStateProjector
import jbro.cobblemon.mcc.betterai.state.LocalFieldClearing
import jbro.cobblemon.mcc.betterai.state.RecursiveActionHistory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** Items of docs/betterai/engine/LOCAL_GAPS.md chapters 1-4, each pinned where it was wrong or missing. */
class LocalGapFixesTest {
    @Test
    fun `G-001 the defender's Utility Umbrella cancels the sun boost, the attacker's does not`() {
        val sun = field(weather = "sunnyday")
        fun multiplier(attackerItem: String?, defenderItem: String?): Double {
            val state = state(listOf(mon(BattleSide.ALLY, 0, "fire", item = attackerItem), mon(BattleSide.OPPONENT, 0, "normal", item = defenderItem)), sun)
            return LocalPublicMechanicsKernel.projectMove(attack("flamethrower", "fire", special = true), context(state, null)).knownDamageMultiplier
        }
        assertEquals(1.0, multiplier(null, "utilityumbrella"), 1e-9)
        assertEquals(1.5, multiplier("utilityumbrella", null), 1e-9)
    }

    @Test
    fun `G-004 an Air Balloon makes its holder immune to Ground moves`() {
        val state = state(listOf(mon(BattleSide.ALLY, 0, "ground"), mon(BattleSide.OPPONENT, 0, "steel", item = "airballoon")))
        assertTrue(LocalPublicMechanicsKernel.projectMove(attack("earthquake", "ground"), context(state, null)).publiclyNullified)
    }

    @Test
    fun `G-008 G-302 Misty Terrain blocks status and Corrosion poisons a Steel type`() {
        val target = mon(BattleSide.OPPONENT, 0, "normal")
        val misty = state(listOf(mon(BattleSide.ALLY, 0, "normal"), target), field(terrain = "mistyterrain"))
        assertTrue(LocalPublicStatusImmunity.blocked(misty, target, "brn"))
        val steel = mon(BattleSide.OPPONENT, 0, "steel")
        val corrosive = mon(BattleSide.ALLY, 0, "poison", ability = "corrosion")
        val plain = state(listOf(corrosive, steel))
        assertFalse(LocalPublicStatusImmunity.blocked(plain, steel, "tox", corrosive))
        assertTrue(LocalPublicStatusImmunity.blocked(plain, steel, "tox", null))
    }

    @Test
    fun `G-118 weight moves take their power from species weight`() {
        val user = mon(BattleSide.ALLY, 0, "grass")
        val snorlax = mon(BattleSide.OPPONENT, 0, "normal", species = "cobblemon:snorlax")
        val state = state(listOf(user, snorlax))
        val resolution = LocalPublicMoveDamageInputs.resolve(attack("grassknot", "grass", special = true, power = 0.0), user, snorlax, state)
        assertEquals(setOf(120), resolution?.powers, "Snorlax weighs 460 kg")
    }

    @Test
    fun `G-109 Tera Blast takes the Tera type and G-114 Judgment the Plate`() {
        val tera = mon(BattleSide.ALLY, 0, "normal", tera = "fire")
        val state = state(listOf(tera, mon(BattleSide.OPPONENT, 0, "grass")))
        assertEquals("fire", LocalPublicMoveDamageInputs.resolvedTypeId(attack("terablast", "normal", special = true), tera, state))
        val arceus = mon(BattleSide.ALLY, 0, "normal", item = "splashplate")
        assertEquals("water", LocalPublicMoveDamageInputs.resolvedTypeId(attack("judgment", "normal", special = true), arceus, state))
    }

    @Test
    fun `G-303 Contrary inverts, Clear Body stops a drop and Defiant answers it`() {
        val source = mon(BattleSide.ALLY, 0, "normal")
        fun after(ability: String, stages: Map<String, Int>, from: UUID?): Map<String, Int> {
            val target = mon(BattleSide.OPPONENT, 0, "normal", ability = ability)
            val state = state(listOf(source, target))
            return LocalStatStageChange.apply(state, target.battlePokemonId, from, stages)
                .pokemon.single { it.battlePokemonId == target.battlePokemonId }.statStages.filterValues { it != 0 }
        }
        assertEquals(mapOf("special_attack" to 2), after("contrary", mapOf("special_attack" to -2), null))
        assertEquals(emptyMap<String, Int>(), after("clearbody", mapOf("attack" to -1), source.battlePokemonId))
        assertEquals(mapOf("attack" to 1), after("defiant", mapOf("attack" to -1), source.battlePokemonId))
    }

    @Test
    fun `G-305 G-010 end of turn heals on Grassy Terrain, chips a Black Sludge holder and skips a fresh Speed Boost`() {
        val grounded = mon(BattleSide.ALLY, 0, "normal", hp = 0.5)
        val sludge = mon(BattleSide.OPPONENT, 0, "normal", item = "blacksludge", ability = "speedboost")
        val state = state(listOf(grounded, sludge), field(terrain = "grassyterrain"))
        val next = LocalEndTurnStateProjector.project(state, enteredThisTurnPokemonIds = setOf(sludge.battlePokemonId))
        val healed = next.pokemon.single { it.battlePokemonId == grounded.battlePokemonId }
        val chipped = next.pokemon.single { it.battlePokemonId == sludge.battlePokemonId }
        assertTrue(healed.hpFraction > 0.5, "grassy terrain heals a sixteenth")
        assertTrue(chipped.hpFraction < 1.0, "black sludge hurts a non-Poison type")
        assertEquals(0, chipped.statStages["speed"] ?: 0, "Speed Boost waits a turn after entry")
    }

    @Test
    fun `G-207 G-101 G-210 Weakness Policy, Knock Off and Life Orb react to the hit`() {
        val attacker = mon(BattleSide.ALLY, 0, "dark", item = "lifeorb")
        val target = mon(BattleSide.OPPONENT, 0, "psychic", item = "weaknesspolicy")
        val before = state(listOf(attacker, target))
        val hit = before.derive(pokemon = before.pokemon.map {
            if (it.battlePokemonId == target.battlePokemonId) mon(BattleSide.OPPONENT, 0, "psychic", item = "weaknesspolicy", hp = 0.6, id = target.battlePokemonId) else it
        })
        val knockOff = attack("knockoff", "dark", facts = BattleCandidateFactsView(typeChartMultiplier = 2.0))
        val after = LocalAfterHitReactions.apply(before, hit, attacker.battlePokemonId, target.battlePokemonId, knockOff, 0.4)
        val struck = after.pokemon.single { it.battlePokemonId == target.battlePokemonId }
        val user = after.pokemon.single { it.battlePokemonId == attacker.battlePokemonId }
        assertEquals(2, struck.statStages["attack"], "Weakness Policy")
        assertEquals("", struck.knownHeldItemId, "the policy is spent and Knock Off took nothing else")
        assertTrue(user.hpFraction < 1.0, "Life Orb recoil")
    }

    @Test
    fun `G-103 Rapid Spin clears its side and Defog the target's screens`() {
        val user = mon(BattleSide.ALLY, 0, "normal")
        val hazards = field(sides = mapOf(
            BattleSide.ALLY to listOf(BattleTimedEffectView("stealthrock", null)),
            BattleSide.OPPONENT to listOf(BattleTimedEffectView("reflect", 5)),
        ))
        val state = state(listOf(user, mon(BattleSide.OPPONENT, 0, "normal")), hazards)
        assertTrue(LocalFieldClearing.apply(state, user.battlePokemonId, "rapidspin").field.sideConditions.getValue(BattleSide.ALLY).isEmpty())
        assertTrue(LocalFieldClearing.apply(state, user.battlePokemonId, "defog").field.sideConditions.getValue(BattleSide.OPPONENT).isEmpty())
    }

    @Test
    fun `G-206 G-209 Huge Power doubles and Multiscale halves at full HP`() {
        fun multiplier(attackerAbility: String?, targetAbility: String?): Double {
            val state = state(listOf(mon(BattleSide.ALLY, 0, "normal", ability = attackerAbility), mon(BattleSide.OPPONENT, 0, "dragon", ability = targetAbility)))
            return LocalPublicMechanicsKernel.projectMove(attack("return", "normal"), context(state, null)).knownDamageMultiplier
        }
        assertEquals(2.0, multiplier("hugepower", null), 1e-9)
        assertEquals(0.5, multiplier(null, "multiscale"), 1e-9)
    }

    @Test
    fun `G-204 a Choice item locks the move used since coming in`() {
        val scarf = mon(BattleSide.OPPONENT, 0, "normal", item = "choicescarf")
        val state = state(listOf(mon(BattleSide.ALLY, 0, "normal"), scarf))
        val tackle = attack("tackle", "normal")
        val ember = attack("ember", "fire", special = true)
        val catalog = BattlePublicActionCatalogView(listOf(BattlePokemonActionCatalogView(scarf.battlePokemonId, listOf(
            BattlePublicMoveOptionView("cobblemon:tackle", tackle.moveDetails!!, BattlePublicMoveKnowledge.PUBLICLY_REVEALED),
            BattlePublicMoveOptionView("cobblemon:ember", ember.moveDetails!!, BattlePublicMoveKnowledge.PUBLICLY_REVEALED),
        ), moveSetComplete = true)))
        val moves = PublicFutureActionFactory.primitiveActionsForPokemon(state, BattleSide.OPPONENT, scarf.battlePokemonId, catalog,
            RecursiveActionHistory(lastMoveByPokemon = mapOf(scarf.battlePokemonId to "cobblemon:tackle")))
            .filter { it.kind == BattleActionKind.USE_MOVE }.mapNotNull { it.moveId }.toSet()
        assertEquals(setOf("cobblemon:tackle"), moves)
    }

    @Test
    fun `G-102 Fake Out makes a slower target lose its move`() {
        val fast = mon(BattleSide.ALLY, 0, "normal", speed = 150)
        val slow = mon(BattleSide.OPPONENT, 0, "normal", speed = 50)
        val state = state(listOf(fast, slow))
        val fakeOut = attack("fakeout", "normal", power = 40.0, priority = 3, target = BattleTargetSlot(BattleSide.OPPONENT, 0),
            effects = listOf(BattleMoveEffectView(BattleMoveEffectKind.VOLATILE_STATUS, BattleMoveEffectTarget.SELECTED_TARGET, 1.0, valueId = "flinch")))
        val reply = attack("tackle", "normal", target = BattleTargetSlot(BattleSide.ALLY, 0))
        val calculated = PublicBattleTacticalCalculator.calculate(context(state, fakeOut))
        val outcomes = PublicSingleTurnProjector.project(state, calculated.candidates.single(), reply, calculated)
        outcomes.forEach { outcome ->
            assertEquals(1.0, outcome.stateBeforeResidual.pokemon.single { it.battlePokemonId == fast.battlePokemonId }.hpFraction, 1e-9)
        }
    }

    @Test
    fun `G-122 Super Fang takes half the target's HP`() {
        val state = state(listOf(mon(BattleSide.ALLY, 0, "normal"), mon(BattleSide.OPPONENT, 0, "normal", hp = 0.8)))
        val fang = attack("superfang", "normal", power = 0.0, target = BattleTargetSlot(BattleSide.OPPONENT, 0))
        val facts = PublicBattleTacticalCalculator.calculate(context(state, fang)).candidates.single().facts
        assertEquals(0.4, facts?.standardDamageFractionRange?.maximum ?: 0.0, 1e-6)
    }

    @Test
    fun `G-112 G-124 Throat Chop bars sound moves and Disable the last move`() {
        val user = mon(BattleSide.OPPONENT, 0, "normal")
        val chopped = BattlePokemonStateView(
            battlePokemonId = user.battlePokemonId, side = user.side, activeSlot = 0, speciesId = user.speciesId, formId = null,
            level = 50, hpFraction = 1.0, statusId = null, statStages = emptyMap(), knownMoveIds = emptySet(),
            knownAbilityId = null, knownHeldItemId = null, fainted = false, knownTypeIds = setOf("normal"),
            combatStats = user.combatStats, knownVolatileEffectIds = setOf("throatchop", "disable"),
            knownBaseStabTypeIds = setOf("normal"),
        )
        val state = state(listOf(mon(BattleSide.ALLY, 0, "normal"), chopped))
        val sound = attack("hypervoice", "normal", special = true).moveDetails!!.let { details ->
            details.copy(effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, emptyList(), false,
                mechanicFlags = setOf("sound")))
        }
        val catalog = BattlePublicActionCatalogView(listOf(BattlePokemonActionCatalogView(user.battlePokemonId, listOf(
            BattlePublicMoveOptionView("cobblemon:hypervoice", sound, BattlePublicMoveKnowledge.PUBLICLY_REVEALED),
            BattlePublicMoveOptionView("cobblemon:tackle", attack("tackle", "normal").moveDetails!!, BattlePublicMoveKnowledge.PUBLICLY_REVEALED),
            BattlePublicMoveOptionView("cobblemon:ember", attack("ember", "fire").moveDetails!!, BattlePublicMoveKnowledge.PUBLICLY_REVEALED),
        ), moveSetComplete = true)))
        val moves = PublicFutureActionFactory.primitiveActionsForPokemon(state, BattleSide.OPPONENT, user.battlePokemonId, catalog,
            RecursiveActionHistory(lastMoveByPokemon = mapOf(user.battlePokemonId to "cobblemon:tackle")))
            .filter { it.kind == BattleActionKind.USE_MOVE }.mapNotNull { it.moveId }.toSet()
        assertEquals(setOf("cobblemon:ember"), moves)
    }

    @Test
    fun `G-219 Flower Veil spares a Grass partner from status`() {
        val grass = mon(BattleSide.OPPONENT, 0, "grass")
        val pokemon = listOf(mon(BattleSide.ALLY, 0, "normal"), mon(BattleSide.ALLY, 1, "normal"), grass,
            mon(BattleSide.OPPONENT, 1, "fairy", ability = "flowerveil"))
        val state = BattleStateView(
            battleId = UUID.randomUUID(), format = BattleFormat.DOUBLE, turn = 3, pokemon = pokemon, field = field(),
            remainingPokemonBySide = BattleSide.entries.associateWith { side -> pokemon.count { it.side == side } },
            observedEvents = emptyList(), inferences = emptyList(),
        )
        val attacker = state.pokemon.first { it.side == BattleSide.ALLY }
        assertTrue(LocalPublicStatusImmunity.blocked(state, grass, "par", attacker))
    }

    @Test
    fun `G-126 a Glaive Rush user takes double damage`() {
        val target = BattlePokemonStateView(
            battlePokemonId = UUID.randomUUID(), side = BattleSide.OPPONENT, activeSlot = 0, speciesId = "showdown:probe",
            formId = null, level = 50, hpFraction = 1.0, statusId = null, statStages = emptyMap(), knownMoveIds = emptySet(),
            knownAbilityId = null, knownHeldItemId = null, fainted = false, knownTypeIds = setOf("normal"),
            combatStats = mon(BattleSide.OPPONENT, 0, "normal").combatStats, knownVolatileEffectIds = setOf("glaiverush"),
            knownBaseStabTypeIds = setOf("normal"),
        )
        val state = state(listOf(mon(BattleSide.ALLY, 0, "normal"), target))
        assertEquals(2.0, LocalPublicMechanicsKernel.projectMove(attack("tackle", "normal"), context(state, null)).knownDamageMultiplier, 1e-9)
    }

    @Test
    fun `G-120 Clear Smog resets the target's stages`() {
        val attacker = mon(BattleSide.ALLY, 0, "poison")
        val boosted = BattlePokemonStateView(
            battlePokemonId = UUID.randomUUID(), side = BattleSide.OPPONENT, activeSlot = 0, speciesId = "showdown:probe",
            formId = null, level = 50, hpFraction = 0.8, statusId = null, statStages = mapOf("attack" to 4), knownMoveIds = emptySet(),
            knownAbilityId = null, knownHeldItemId = null, fainted = false, knownTypeIds = setOf("normal"),
            combatStats = attacker.combatStats, knownVolatileEffectIds = emptySet(), knownBaseStabTypeIds = setOf("normal"),
        )
        val state = state(listOf(attacker, boosted))
        val after = LocalAfterHitReactions.apply(state, state, attacker.battlePokemonId, boosted.battlePokemonId,
            attack("clearsmog", "poison", special = true, power = 50.0), 0.2)
        assertTrue(after.pokemon.single { it.battlePokemonId == boosted.battlePokemonId }.statStages.isEmpty())
    }

    @Test
    fun `G-128 Beat Up counts the healthy party`() {
        val user = mon(BattleSide.ALLY, 0, "dark", species = "cobblemon:umbreon")
        val state = state(listOf(user, mon(BattleSide.ALLY, null, "normal", species = "cobblemon:snorlax"), mon(BattleSide.OPPONENT, 0, "psychic")))
        val target = state.pokemon.single { it.side == BattleSide.OPPONENT }
        val powers = LocalPublicMoveDamageInputs.resolve(attack("beatup", "dark", power = 0.0), user, target, state)?.powers
        assertTrue(powers != null && powers.single() > 10, "two healthy party members: $powers")
    }

    private fun field(
        weather: String? = null,
        terrain: String? = null,
        sides: Map<BattleSide, List<BattleTimedEffectView>> = emptyMap(),
    ) = BattleFieldStateView(
        weather = weather?.let { BattleTimedEffectView(it, 5) },
        terrain = terrain?.let { BattleTimedEffectView(it, 5) },
        roomEffects = emptyList(),
        globalEffects = emptyList(),
        sideConditions = BattleSide.entries.associateWith { sides[it].orEmpty() },
    )

    private fun attack(
        id: String,
        type: String,
        special: Boolean = false,
        power: Double = 90.0,
        priority: Int = 0,
        target: BattleTargetSlot? = BattleTargetSlot(BattleSide.OPPONENT, 0),
        effects: List<BattleMoveEffectView> = emptyList(),
        facts: BattleCandidateFactsView? = null,
    ) = BattleActionCandidate(
        actionId = id, kind = BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0, moveId = "cobblemon:$id",
        targets = listOfNotNull(target), facts = facts,
        moveDetails = BattleMoveCandidateView(
            typeId = type,
            damageCategory = if (special) BattleMoveDamageCategory.SPECIAL else BattleMoveDamageCategory.PHYSICAL,
            power = power, accuracy = 100.0, priority = priority, currentPp = 10,
            targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
            effects = effects.takeIf { it.isNotEmpty() }?.let {
                BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, it, scriptedBehavior = false)
            },
        ),
    )

    private fun context(state: BattleStateView, candidate: BattleActionCandidate?) = BattleDecisionContext(
        requestId = UUID.randomUUID(), state = state,
        candidates = listOf(candidate ?: BattleActionCandidate("wait", BattleActionKind.WAIT)),
        deadlineEpochMillis = Long.MAX_VALUE, memory = BattleTacticalMemoryView.empty(),
        publicActionCatalog = BattlePublicActionCatalogView(emptyList()),
    )

    private fun state(pokemon: List<BattlePokemonStateView>, field: BattleFieldStateView = field()) = BattleStateView(
        battleId = UUID.randomUUID(), format = BattleFormat.SINGLE, turn = 3, pokemon = pokemon, field = field,
        remainingPokemonBySide = BattleSide.entries.associateWith { side -> pokemon.count { it.side == side && !it.fainted } },
        observedEvents = emptyList(), inferences = emptyList(),
    )

    private fun mon(
        side: BattleSide, slot: Int?, type: String, speed: Int = 100, hp: Double = 1.0,
        item: String? = null, ability: String? = null, tera: String? = null,
        species: String = "showdown:probe", id: UUID = UUID.randomUUID(),
    ) = BattlePokemonStateView(
        battlePokemonId = id, side = side, activeSlot = slot, speciesId = species, formId = null, level = 50,
        hpFraction = hp, statusId = null, statStages = emptyMap(), knownMoveIds = emptySet(), knownAbilityId = ability,
        knownHeldItemId = item, fainted = false, knownTypeIds = setOf(type),
        combatStats = BattleCombatStatRangesView(
            maxHp = BattleIntegerRange(160, 160), attack = BattleIntegerRange(120, 120),
            defence = BattleIntegerRange(100, 100), specialAttack = BattleIntegerRange(120, 120),
            specialDefence = BattleIntegerRange(100, 100), speed = BattleIntegerRange(speed, speed),
            knowledge = BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
        ),
        knownVolatileEffectIds = emptySet(), knownBaseStabTypeIds = setOf(type), knownTeraTypeId = tera,
    )
}
