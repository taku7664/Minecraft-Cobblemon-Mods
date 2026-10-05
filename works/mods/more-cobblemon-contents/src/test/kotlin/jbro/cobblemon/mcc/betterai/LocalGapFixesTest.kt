package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.mcc.betterai.mechanics.LocalAfterHitReactions
import jbro.cobblemon.mcc.betterai.mechanics.LocalDirectHitMechanics
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicItemState
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicMechanicsKernel
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicMoveDamageInputs
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicStatusImmunity
import jbro.cobblemon.mcc.betterai.mechanics.LocalReactiveAbilityState
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

    @Test
    fun `G-601 Rock Head and Magic Guard stop recoil but not an HP cost`() {
        fun hpAfter(ability: String?, effect: BattleMoveEffectView): Double {
            val user = mon(BattleSide.ALLY, 0, "rock", ability = ability)
            val target = mon(BattleSide.OPPONENT, 0, "normal")
            return LocalDirectHitMechanics.apply(state(listOf(user, target)), user.battlePokemonId, target.battlePokemonId, 0.5,
                listOf(effect), ignoreTargetAbility = false).state.pokemon.single { it.battlePokemonId == user.battlePokemonId }.hpFraction
        }
        val recoil = BattleMoveEffectView(BattleMoveEffectKind.RECOIL_FRACTION, BattleMoveEffectTarget.USER, 1.0,
            fractionRange = BattleFractionRange(0.5, 0.5))
        val hpCost = BattleMoveEffectView(BattleMoveEffectKind.MAX_HP_RECOIL, BattleMoveEffectTarget.USER, 1.0,
            fractionRange = BattleFractionRange(0.5, 0.5))
        val mindBlown = BattleMoveEffectView(BattleMoveEffectKind.MAX_HP_RECOIL, BattleMoveEffectTarget.USER, 1.0,
            valueId = BattleDeclarativeMoveEffects.MIND_BLOWN_RECOIL, fractionRange = BattleFractionRange(0.5, 0.5))
        assertTrue(hpAfter(null, recoil) < 1.0)
        assertEquals(1.0, hpAfter("rockhead", recoil), 1e-9)
        assertEquals(1.0, hpAfter("magicguard", recoil), 1e-9)
        assertEquals(1.0, hpAfter("magicguard", mindBlown), 1e-9)
        assertTrue(hpAfter("magicguard", hpCost) < 1.0, "Belly Drum's cost is not recoil")
    }

    @Test
    fun `G-602 Poltergeist fails on a Pokemon that lost its item in the line`() {
        val user = mon(BattleSide.ALLY, 0, "ghost", speed = 150)
        val target = mon(BattleSide.OPPONENT, 0, "psychic", speed = 50)
        val state = state(listOf(user, target))
        val poltergeist = attack("poltergeist", "ghost", power = 110.0,
            requirements = listOf(BattleMoveRequirementView(BattleMoveRequirementKind.TARGET_HELD_ITEM_PRESENT)))
        val reply = attack("splash", "normal", power = 0.0, target = null)
        val calculated = PublicBattleTacticalCalculator.calculate(context(state, poltergeist))
        fun targetHp(history: RecursiveActionHistory) = PublicSingleTurnProjector.project(state, calculated.candidates.single(), reply, calculated, history)
            .minOf { outcome -> outcome.stateBeforeResidual.pokemon.single { it.battlePokemonId == target.battlePokemonId }.hpFraction }
        assertTrue(targetHp(RecursiveActionHistory()) < 1.0, "an unrevealed item may still be there")
        assertEquals(1.0, targetHp(RecursiveActionHistory(itemHoldersAtRoot = setOf(target.battlePokemonId))), 1e-9)
    }

    @Test
    fun `G-604 Defeatist halves at half HP and Grass Pelt guards on Grassy Terrain`() {
        fun multiplier(attacker: BattlePokemonStateView, target: BattlePokemonStateView, terrain: String? = null): Double =
            LocalPublicMechanicsKernel.projectMove(attack("tackle", "normal"), context(state(listOf(attacker, target), field(terrain = terrain)), null))
                .knownDamageMultiplier
        assertEquals(0.5, multiplier(mon(BattleSide.ALLY, 0, "rock", hp = 0.4, ability = "defeatist"), mon(BattleSide.OPPONENT, 0, "water")), 1e-9)
        assertEquals(2.0 / 3.0, multiplier(mon(BattleSide.ALLY, 0, "rock"), mon(BattleSide.OPPONENT, 0, "water", ability = "grasspelt"), "grassyterrain"), 1e-9)
    }

    @Test
    fun `G-605 Damp stops Explosion and Unnerve keeps a berry from working`() {
        val user = mon(BattleSide.ALLY, 0, "normal")
        val damp = mon(BattleSide.OPPONENT, 0, "water", ability = "damp")
        val explosion = attack("explosion", "normal", power = 250.0)
        assertTrue(LocalPublicMechanicsKernel.projectMove(explosion, context(state(listOf(user, damp)), null)).publiclyNullified)
        val holder = mon(BattleSide.ALLY, 0, "normal", item = "sitrusberry")
        assertEquals("sitrusberry", LocalPublicItemState.activeItemId(state(listOf(holder, mon(BattleSide.OPPONENT, 0, "normal"))), holder))
        assertNull(LocalPublicItemState.activeItemId(state(listOf(holder, mon(BattleSide.OPPONENT, 0, "normal", ability = "unnerve"))), holder))
    }

    @Test
    fun `G-606 Soul-Heart rises on a knockout and Magician takes the item it hit`() {
        val attacker = mon(BattleSide.ALLY, 0, "psychic", ability = "magician")
        val heart = mon(BattleSide.OPPONENT, 1, "fairy", ability = "soulheart")
        val target = mon(BattleSide.OPPONENT, 0, "normal", item = "leftovers")
        val pokemon = listOf(attacker, target, heart)
        val before = BattleStateView(
            battleId = UUID.randomUUID(), format = BattleFormat.DOUBLE, turn = 3, pokemon = pokemon, field = field(),
            remainingPokemonBySide = BattleSide.entries.associateWith { side -> pokemon.count { it.side == side } },
            observedEvents = emptyList(), inferences = emptyList(),
        )
        val hit = before.derive(pokemon = before.pokemon.map {
            if (it.battlePokemonId == target.battlePokemonId) mon(BattleSide.OPPONENT, 0, "normal", item = "leftovers", hp = 0.5, id = target.battlePokemonId) else it
        })
        val after = LocalAfterHitReactions.apply(before, hit, attacker.battlePokemonId, target.battlePokemonId, attack("psychic", "psychic", special = true), 0.5)
        assertEquals("leftovers", after.pokemon.single { it.battlePokemonId == attacker.battlePokemonId }.knownHeldItemId)
        assertEquals("", after.pokemon.single { it.battlePokemonId == target.battlePokemonId }.knownHeldItemId, "a confirmed absence")
        val knockedOut = before.derive(pokemon = before.pokemon.map {
            if (it.battlePokemonId == target.battlePokemonId) mon(BattleSide.OPPONENT, 0, "normal", hp = 0.0, id = target.battlePokemonId) else it
        })
        val afterKo = LocalAfterHitReactions.apply(before, knockedOut, attacker.battlePokemonId, target.battlePokemonId, attack("psychic", "psychic", special = true), 1.0)
        assertEquals(1, afterKo.pokemon.single { it.battlePokemonId == heart.battlePokemonId }.statStages["special_attack"])
    }

    @Test
    fun `G-607 Opportunist copies a foe's raise and both raises are marked for this turn`() {
        val dancer = mon(BattleSide.ALLY, 0, "normal")
        val copier = mon(BattleSide.OPPONENT, 0, "psychic", ability = "opportunist")
        val mirror = mon(BattleSide.ALLY, 1, "normal", ability = "opportunist")
        val after = LocalStatStageChange.apply(state(listOf(dancer, copier, mirror)), dancer.battlePokemonId, dancer.battlePokemonId, mapOf("attack" to 2))
        val copied = after.pokemon.single { it.battlePokemonId == copier.battlePokemonId }
        assertEquals(2, copied.statStages["attack"])
        assertEquals(0, after.pokemon.single { it.battlePokemonId == mirror.battlePokemonId }.statStages["attack"] ?: 0,
            "an Opportunist's own copy is not copied back")
        assertTrue(LocalReactiveAbilityState.BOOSTED_THIS_TURN in copied.knownVolatileEffectIds)
        val dropped = LocalStatStageChange.apply(state(listOf(dancer, copier)), dancer.battlePokemonId, dancer.battlePokemonId, mapOf("speed" to -1))
        assertEquals(0, dropped.pokemon.single { it.battlePokemonId == copier.battlePokemonId }.statStages["speed"] ?: 0, "drops are not copied")
    }

    @Test
    fun `G-607 Alluring Voice confuses a target that set up earlier in the turn`() {
        val singer = mon(BattleSide.ALLY, 0, "fairy", speed = 50)
        val dancer = mon(BattleSide.OPPONENT, 0, "normal", speed = 150)
        val state = state(listOf(singer, dancer))
        val voice = attack("alluringvoice", "fairy", special = true, power = 80.0)
        val swordsDance = status("swordsdance", listOf(BattleMoveEffectView(BattleMoveEffectKind.STAT_STAGE, BattleMoveEffectTarget.USER, 1.0,
            statStages = mapOf("attack" to 2))))
        val calculated = PublicBattleTacticalCalculator.calculate(context(state, voice))
        fun confused(reply: BattleActionCandidate) = PublicSingleTurnProjector.project(state, calculated.candidates.single(), reply, calculated, RecursiveActionHistory())
            .map { outcome -> "confusion" in outcome.stateBeforeResidual.pokemon.single { it.battlePokemonId == dancer.battlePokemonId }.knownVolatileEffectIds }
        val afterSetup = confused(swordsDance)
        assertTrue(afterSetup.isNotEmpty() && afterSetup.all { it }, "$afterSetup")
        assertTrue(confused(status("splash", emptyList())).none { it })
    }

    @Test
    fun `G-607 Poison Puppeteer confuses what Pecharunt poisons`() {
        val pecharunt = mon(BattleSide.ALLY, 0, "poison", ability = "poisonpuppeteer", species = "cobblemon:pecharunt", speed = 150)
        val target = mon(BattleSide.OPPONENT, 0, "normal", speed = 50)
        val state = state(listOf(pecharunt, target))
        val toxic = status("toxic", listOf(BattleMoveEffectView(BattleMoveEffectKind.STATUS, BattleMoveEffectTarget.SELECTED_TARGET, 1.0, "tox")),
            target = BattleTargetSlot(BattleSide.OPPONENT, 0))
        val calculated = PublicBattleTacticalCalculator.calculate(context(state, toxic))
        val struck = PublicSingleTurnProjector.project(state, calculated.candidates.single(), status("splash", emptyList()), calculated, RecursiveActionHistory())
            .map { outcome -> outcome.stateBeforeResidual.pokemon.single { it.battlePokemonId == target.battlePokemonId } }
        assertTrue(struck.isNotEmpty() && struck.all { it.statusId == "tox" && "confusion" in it.knownVolatileEffectIds },
            "${struck.map { it.statusId to it.knownVolatileEffectIds }}")
    }

    @Test
    fun `G-607 Steadfast speeds up when flinched and Truant loafs the turn after it moved`() {
        val fakeOut = attack("fakeout", "normal", power = 40.0, priority = 3, effects = listOf(
            BattleMoveEffectView(BattleMoveEffectKind.VOLATILE_STATUS, BattleMoveEffectTarget.SELECTED_TARGET, 1.0, "flinch")))
        val steadfast = mon(BattleSide.OPPONENT, 0, "fighting", ability = "steadfast")
        val state = state(listOf(mon(BattleSide.ALLY, 0, "normal"), steadfast))
        val calculated = PublicBattleTacticalCalculator.calculate(context(state, fakeOut))
        val reply = attack("closecombat", "fighting", power = 120.0, target = BattleTargetSlot(BattleSide.ALLY, 0))
        val flinched = PublicSingleTurnProjector.project(state, calculated.candidates.single(), reply, calculated, RecursiveActionHistory())
            .map { outcome -> outcome.stateBeforeResidual.pokemon.single { it.battlePokemonId == steadfast.battlePokemonId } }
        assertTrue(flinched.isNotEmpty() && flinched.all { it.statStages["speed"] == 1 }, "${flinched.map { it.statStages }}")

        val user = mon(BattleSide.ALLY, 0, "normal", speed = 150)
        fun slakingTurn(volatiles: Set<String>): List<Pair<Double, Set<String>>> {
            val slaking = mon(BattleSide.OPPONENT, 0, "normal", ability = "truant", volatiles = volatiles)
            val board = state(listOf(user, slaking))
            val calc = PublicBattleTacticalCalculator.calculate(context(board, status("splash", emptyList())))
            return PublicSingleTurnProjector.project(board, calc.candidates.single(), reply, calc, RecursiveActionHistory()).map { outcome ->
                outcome.stateBeforeResidual.pokemon.single { it.battlePokemonId == user.battlePokemonId }.hpFraction to
                    outcome.stateBeforeResidual.pokemon.single { it.battlePokemonId == slaking.battlePokemonId }.knownVolatileEffectIds
            }
        }
        val acting = slakingTurn(emptySet())
        assertTrue(acting.isNotEmpty() && acting.all { it.first < 1.0 && "truant" in it.second }, "it attacks, then owes a turn: $acting")
        val loafing = slakingTurn(setOf("truant"))
        assertTrue(loafing.isNotEmpty() && loafing.all { it.first == 1.0 && "truant" !in it.second }, "it loafs and is free next turn: $loafing")
    }

    @Test
    fun `G-607 Plus and Minus, Flower Gift and Hunger Switch`() {
        fun multiplier(pokemon: List<BattlePokemonStateView>, move: BattleActionCandidate, weather: String? = null): Double =
            LocalPublicMechanicsKernel.projectMove(move, context(state(pokemon, field(weather = weather)), null)).knownDamageMultiplier
        val thunderbolt = attack("thunderbolt", "electric", special = true)
        val alone = multiplier(listOf(mon(BattleSide.ALLY, 0, "electric", ability = "plus"), mon(BattleSide.ALLY, 1, "electric"),
            mon(BattleSide.OPPONENT, 0, "normal")), thunderbolt)
        val paired = multiplier(listOf(mon(BattleSide.ALLY, 0, "electric", ability = "plus"), mon(BattleSide.ALLY, 1, "electric", ability = "minus"),
            mon(BattleSide.OPPONENT, 0, "normal")), thunderbolt)
        assertEquals(1.5, paired / alone, 1e-9)
        val cherrim = mon(BattleSide.ALLY, 1, "grass", ability = "flowergift", species = "cobblemon:cherrim")
        val sunny = multiplier(listOf(mon(BattleSide.ALLY, 0, "rock"), cherrim, mon(BattleSide.OPPONENT, 0, "normal")), attack("tackle", "normal"), "sunnyday")
        val cloudy = multiplier(listOf(mon(BattleSide.ALLY, 0, "rock"), cherrim, mon(BattleSide.OPPONENT, 0, "normal")), attack("tackle", "normal"))
        assertEquals(1.5, sunny / cloudy, 1e-9)

        val morpeko = mon(BattleSide.ALLY, 0, "electric", ability = "hungerswitch", species = "cobblemon:morpeko")
        val first = LocalEndTurnStateProjector.project(state(listOf(morpeko, mon(BattleSide.OPPONENT, 0, "normal"))))
        val hangry = first.pokemon.single { it.battlePokemonId == morpeko.battlePokemonId }
        assertEquals("Hangry", hangry.formId)
        assertEquals("dark", LocalPublicMoveDamageInputs.resolvedTypeId(attack("aurawheel", "electric"), hangry, first))
        assertEquals("Normal", LocalEndTurnStateProjector.project(first).pokemon.single { it.battlePokemonId == morpeko.battlePokemonId }.formId)
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
        requirements: List<BattleMoveRequirementView> = emptyList(),
    ) = BattleActionCandidate(
        actionId = id, kind = BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0, moveId = "cobblemon:$id",
        targets = listOfNotNull(target), facts = facts,
        moveDetails = BattleMoveCandidateView(
            typeId = type,
            damageCategory = if (special) BattleMoveDamageCategory.SPECIAL else BattleMoveDamageCategory.PHYSICAL,
            power = power, accuracy = 100.0, priority = priority, currentPp = 10,
            targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
            effects = if (effects.isEmpty() && requirements.isEmpty()) null else
                BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, effects, scriptedBehavior = false, requirements = requirements),
        ),
    )

    private fun status(id: String, effects: List<BattleMoveEffectView>, target: BattleTargetSlot? = null) = BattleActionCandidate(
        actionId = id, kind = BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0, moveId = "cobblemon:$id",
        targets = listOfNotNull(target),
        moveDetails = BattleMoveCandidateView(
            typeId = "normal", damageCategory = BattleMoveDamageCategory.STATUS, power = 0.0, accuracy = 100.0, priority = 0, currentPp = 10,
            targetPattern = if (target == null) BattleMoveTargetPattern.SELF else BattleMoveTargetPattern.SELECTED_OPPONENT,
            effects = if (effects.isEmpty()) null else BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, effects, scriptedBehavior = false),
        ),
    )

    private fun context(state: BattleStateView, candidate: BattleActionCandidate?) = BattleDecisionContext(
        requestId = UUID.randomUUID(), state = state,
        candidates = listOf(candidate ?: BattleActionCandidate("wait", BattleActionKind.WAIT)),
        deadlineEpochMillis = Long.MAX_VALUE, memory = BattleTacticalMemoryView.empty(),
        publicActionCatalog = BattlePublicActionCatalogView(emptyList()),
    )

    private fun state(pokemon: List<BattlePokemonStateView>, field: BattleFieldStateView = field()) = BattleStateView(
        battleId = UUID.randomUUID(), format = if (pokemon.count { it.side == BattleSide.ALLY && it.activeSlot != null } > 1) BattleFormat.DOUBLE else BattleFormat.SINGLE, turn = 3, pokemon = pokemon, field = field,
        remainingPokemonBySide = BattleSide.entries.associateWith { side -> pokemon.count { it.side == side && !it.fainted } },
        observedEvents = emptyList(), inferences = emptyList(),
    )

    private fun mon(
        side: BattleSide, slot: Int?, type: String, speed: Int = 100, hp: Double = 1.0,
        item: String? = null, ability: String? = null, tera: String? = null,
        species: String = "showdown:probe", id: UUID = UUID.randomUUID(), volatiles: Set<String> = emptySet(),
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
        knownVolatileEffectIds = volatiles, knownBaseStabTypeIds = setOf(type), knownTeraTypeId = tera,
    )
}
