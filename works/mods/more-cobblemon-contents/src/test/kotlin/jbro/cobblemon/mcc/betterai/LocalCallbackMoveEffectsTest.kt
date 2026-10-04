package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.outcome.PublicSingleTurnProjector
import jbro.cobblemon.mcc.betterai.mechanics.RecursiveControlEffectKind
import jbro.cobblemon.mcc.betterai.mechanics.LocalStatStageChange
import jbro.cobblemon.mcc.betterai.state.RecursiveActionHistory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Moves whose effect Showdown applies in a callback (LOCAL_GAPS G-104, G-111, G-117, G-121, G-311). The declared
 * data has no heal or boost for them, so they were a generic twenty-point status move and did nothing in search.
 */
class LocalCallbackMoveEffectsTest {
    @Test
    fun `Synthesis heals half, two thirds in sun and a quarter in rain`() {
        assertEquals(0.5, healOf("synthesis", weather = null), 1e-9)
        assertEquals(2.0 / 3.0, healOf("synthesis", weather = "sunnyday"), 1e-9)
        assertEquals(0.25, healOf("moonlight", weather = "raindance"), 1e-9)
        assertEquals(0.25, healOf("morningsun", weather = "sandstorm"), 1e-9)
        assertEquals(2.0 / 3.0, healOf("shoreup", weather = "sandstorm"), 1e-9)
        assertEquals(0.5, healOf("shoreup", weather = "sunnyday"), 1e-9)
    }

    @Test
    fun `Strength Sap heals by the target's boosted Attack and lowers it`() {
        val user = mon(BattleSide.ALLY, "grass", hp = 0.4, maxHp = 300)
        val foe = mon(BattleSide.OPPONENT, "normal", attack = 150, stages = mapOf("attack" to 1))
        val calculated = calculate(move("strengthsap", BattleMoveTargetPattern.SELECTED_OPPONENT), user, foe)
        val heal = calculated.moveDetails!!.effects!!.effects.single { it.kind == BattleMoveEffectKind.HEAL_FRACTION }
        assertEquals(225.0 / 300.0, heal.fractionRange!!.minimum, 1e-9, "150 Attack at +1 is 225, of 300 HP")
        assertTrue(calculated.moveDetails!!.effects!!.effects.any {
            it.kind == BattleMoveEffectKind.STAT_STAGE && it.target == BattleMoveEffectTarget.SELECTED_TARGET &&
                it.statStages == mapOf("atk" to -1)
        })
        assertEquals(225.0 / 300.0, calculated.facts?.selfHealingFractionRange?.minimum ?: 0.0, 1e-9)
    }

    @Test
    fun `Curse boosts a non-Ghost user and costs a Ghost user half its HP`() {
        val foe = mon(BattleSide.OPPONENT, "normal")
        val normal = calculate(move("curse", BattleMoveTargetPattern.SELECTED_OPPONENT), mon(BattleSide.ALLY, "normal"), foe)
            .moveDetails!!.effects!!.effects
        assertTrue(normal.any { it.kind == BattleMoveEffectKind.STAT_STAGE && it.target == BattleMoveEffectTarget.USER &&
            it.statStages == mapOf("atk" to 1, "def" to 1, "spe" to -1) }, normal.toString())
        assertTrue(normal.none { it.kind == BattleMoveEffectKind.VOLATILE_STATUS || it.kind == BattleMoveEffectKind.MAX_HP_RECOIL })

        val ghost = calculate(move("curse", BattleMoveTargetPattern.SELECTED_OPPONENT), mon(BattleSide.ALLY, "ghost"), foe)
            .moveDetails!!.effects!!.effects
        assertTrue(ghost.none { it.kind == BattleMoveEffectKind.STAT_STAGE })
        assertTrue(ghost.any { it.kind == BattleMoveEffectKind.MAX_HP_RECOIL && it.fractionRange?.minimum == 0.5 })
        assertTrue(ghost.any { it.kind == BattleMoveEffectKind.VOLATILE_STATUS })
    }

    @Test
    fun `Belly Drum costs half the user's HP and reaches plus six Attack in search`() {
        val user = mon(BattleSide.ALLY, "normal", hp = 1.0, stages = mapOf("atk" to 2))
        val foe = mon(BattleSide.OPPONENT, "normal")
        val state = state(user, foe)
        val drum = move("bellydrum", BattleMoveTargetPattern.SELF)
        val calculated = PublicBattleTacticalCalculator.calculate(context(state, drum))
        val raise = calculated.candidates.single().moveDetails!!.effects!!.effects.single { it.kind == BattleMoveEffectKind.STAT_STAGE }
        assertEquals(mapOf("atk" to 4), raise.statStages, "from +2, Belly Drum adds four")
        val outcomes = PublicSingleTurnProjector.project(
            state, calculated.candidates.single(), BattleActionCandidate("wait", BattleActionKind.WAIT), calculated,
        )
        assertTrue(outcomes.isNotEmpty())
        outcomes.forEach { outcome ->
            val after = outcome.stateBeforeResidual.pokemon.single { it.battlePokemonId == user.battlePokemonId }
            assertEquals(0.5, after.hpFraction, 1e-9)
            assertEquals(6, after.statStages["atk"])
        }
    }

    @Test
    fun `Moonlight heals in search`() {
        val user = mon(BattleSide.ALLY, "fairy", hp = 0.3)
        val foe = mon(BattleSide.OPPONENT, "normal")
        val state = state(user, foe)
        val calculated = PublicBattleTacticalCalculator.calculate(context(state, move("moonlight", BattleMoveTargetPattern.SELF)))
        PublicSingleTurnProjector.project(
            state, calculated.candidates.single(), BattleActionCandidate("wait", BattleActionKind.WAIT), calculated,
        ).forEach { outcome ->
            assertEquals(0.8, outcome.stateBeforeResidual.pokemon.single { it.battlePokemonId == user.battlePokemonId }.hpFraction, 1e-9)
        }
    }

    @Test
    fun `Fillet Away and Clangorous Soul charge their HP`() {
        assertEquals(0.5, effects("filletaway").single { it.kind == BattleMoveEffectKind.MAX_HP_RECOIL }.fractionRange!!.minimum, 1e-9)
        assertEquals(1.0 / 3.0, effects("clangoroussoul").single { it.kind == BattleMoveEffectKind.MAX_HP_RECOIL }.fractionRange!!.minimum, 1e-9)
        assertNull(effects("recover").singleOrNull { it.kind == BattleMoveEffectKind.MAX_HP_RECOIL })
    }

    @Test
    fun `Take Heart cures a status and raises both special stats`() {
        val user = mon(BattleSide.ALLY, "water", status = "brn")
        project(user, move("takeheart", BattleMoveTargetPattern.SELF)).forEach { outcome ->
            val after = outcome.stateBeforeResidual.pokemon.single { it.battlePokemonId == user.battlePokemonId }
            assertNull(after.statusId)
            assertEquals(1, stage(after, "spa"))
            assertEquals(1, stage(after, "spd"))
        }
        assertTrue(effects("takeheart").any {
            it.kind == BattleMoveEffectKind.STAT_STAGE && it.statStages == mapOf("spa" to 1, "spd" to 1)
        })
    }

    @Test
    fun `Take Heart still cures at the stat cap and uses existing ability stage rules`() {
        for ((ability, initial, expected) in listOf(
            Triple(null, 6, 6), Triple("contrary", 0, -1), Triple("simple", 0, 2),
        )) {
            val user = mon(BattleSide.ALLY, "water", status = "psn", ability = ability,
                stages = mapOf("spa" to initial, "spd" to initial))
            project(user, move("takeheart", BattleMoveTargetPattern.SELF)).forEach { outcome ->
                val after = outcome.stateBeforeResidual.pokemon.single { it.battlePokemonId == user.battlePokemonId }
                assertNull(after.statusId)
                assertEquals(expected, stage(after, "spa"), ability ?: "cap")
                assertEquals(expected, stage(after, "spd"), ability ?: "cap")
            }
        }
    }

    @Test
    fun `Meteor Beam boosts on preparation without dealing damage`() {
        val user = mon(BattleSide.ALLY, "rock")
        project(user, beam("meteorbeam")).forEach { outcome ->
            val after = outcome.stateBeforeResidual.pokemon.single { it.battlePokemonId == user.battlePokemonId }
            assertEquals(1, stage(after, "spa"))
            assertEquals(1.0, outcome.stateBeforeResidual.pokemon.single { it.side == BattleSide.OPPONENT }.hpFraction)
            assertTrue(outcome.controlEffects.any { it.kind == RecursiveControlEffectKind.CHARGE })
        }
    }

    @Test
    fun `Meteor Beam continuation does not boost again`() {
        val user = mon(BattleSide.ALLY, "rock", stages = mapOf("spa" to 1))
        val history = RecursiveActionHistory(chargingMoveByPokemon = mapOf(user.battlePokemonId to "meteorbeam"))
        val outcomes = project(user, beam("meteorbeam"), history)
        outcomes.forEach { outcome ->
            assertEquals(1, stage(outcome.stateBeforeResidual.pokemon.single { it.battlePokemonId == user.battlePokemonId }, "spa"))
            assertTrue(outcome.controlEffects.none { it.kind == RecursiveControlEffectKind.CHARGE })
        }
        assertTrue(outcomes.any { it.stateBeforeResidual.pokemon.single { mon -> mon.side == BattleSide.OPPONENT }.hpFraction < 1.0 })
    }

    @Test
    fun `Power Herb Meteor Beam boosts once before firing and consumes the item`() {
        val user = mon(BattleSide.ALLY, "rock", item = "powerherb")
        val outcomes = project(user, beam("meteorbeam"))
        outcomes.forEach { outcome ->
            val after = outcome.stateBeforeResidual.pokemon.single { it.battlePokemonId == user.battlePokemonId }
            assertEquals(1, stage(after, "spa"))
            assertEquals("", after.knownHeldItemId)
            assertTrue(outcome.controlEffects.none { it.kind == RecursiveControlEffectKind.CHARGE })
        }
        assertTrue(outcomes.any { it.stateBeforeResidual.pokemon.single { mon -> mon.side == BattleSide.OPPONENT }.hpFraction < 1.0 })
        val alreadyCharged = mon(BattleSide.ALLY, "rock", stages = mapOf("spa" to 1))
        val continuation = project(alreadyCharged, beam("meteorbeam"), RecursiveActionHistory(
            chargingMoveByPokemon = mapOf(alreadyCharged.battlePokemonId to "meteorbeam"),
        ))
        fun foeHpDistribution(turns: List<jbro.cobblemon.mcc.betterai.state.PublicTurnProjection>) = turns
            .map { it.stateBeforeResidual.pokemon.single { mon -> mon.side == BattleSide.OPPONENT }.hpFraction }.sorted()
        assertEquals(foeHpDistribution(continuation), foeHpDistribution(outcomes), "The immediate hit must use the boosted stat")
    }

    @Test
    fun `Electro Shot in rain boosts before firing and keeps Power Herb`() {
        val user = mon(BattleSide.ALLY, "electric", item = "powerherb")
        val outcomes = project(user, beam("electroshot"), weather = "raindance")
        outcomes.forEach { outcome ->
            val after = outcome.stateBeforeResidual.pokemon.single { it.battlePokemonId == user.battlePokemonId }
            assertEquals(1, stage(after, "spa"))
            assertEquals("powerherb", after.knownHeldItemId)
            assertTrue(outcome.controlEffects.none { it.kind == RecursiveControlEffectKind.CHARGE })
        }
        assertTrue(outcomes.any { it.stateBeforeResidual.pokemon.single { mon -> mon.side == BattleSide.OPPONENT }.hpFraction < 1.0 })
    }

    @Test
    fun `faster Encore does not repeat Electro Shot preparation in rain`() {
        val user = mon(BattleSide.ALLY, "electric", speed = 50)
        val foe = mon(BattleSide.OPPONENT, "normal", speed = 200)
        val state = state(user, foe, "raindance")
        val shot = beam("electroshot")
        val catalog = BattlePublicActionCatalogView(listOf(BattlePokemonActionCatalogView(user.battlePokemonId,
            listOf(BattlePublicMoveOptionView("electroshot", shot.moveDetails!!, BattlePublicMoveKnowledge.PUBLICLY_REVEALED)),
            moveSetComplete = true)))
        val context = BattleDecisionContext(UUID.randomUUID(), state, listOf(shot), Long.MAX_VALUE,
            BattleTacticalMemoryView.empty(), publicActionCatalog = catalog)
        val encore = BattleActionCandidate("encore", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
            moveId = "encore", targets = listOf(BattleTargetSlot(BattleSide.ALLY, 0)),
            moveDetails = BattleMoveCandidateView("normal", BattleMoveDamageCategory.STATUS, 0.0, 100.0, 0, 5,
                targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
                effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                    listOf(BattleMoveEffectView(BattleMoveEffectKind.VOLATILE_STATUS,
                        BattleMoveEffectTarget.SELECTED_TARGET, 1.0, "encore")), false)))
        val outcomes = PublicSingleTurnProjector.project(state, shot, encore, context,
            RecursiveActionHistory(lastMoveByPokemon = mapOf(user.battlePokemonId to "electroshot")))
        assertTrue(outcomes.isNotEmpty())
        outcomes.forEach { outcome ->
            assertTrue(outcome.controlEffects.any { it.kind == RecursiveControlEffectKind.ENCORE })
            assertEquals(1, stage(outcome.stateBeforeResidual.pokemon.single { it.battlePokemonId == user.battlePokemonId }, "spa"))
        }
    }

    private fun beam(id: String) = BattleActionCandidate(
        actionId = id, kind = BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0, moveId = id,
        moveDetails = move(id, BattleMoveTargetPattern.SELECTED_OPPONENT).moveDetails!!.copy(
            typeId = if (id == "electroshot") "electric" else "rock",
            damageCategory = BattleMoveDamageCategory.SPECIAL, power = 120.0,
        ), targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)))

    private fun project(
        user: BattlePokemonStateView, candidate: BattleActionCandidate,
        history: RecursiveActionHistory = RecursiveActionHistory(), weather: String? = null,
    ): List<jbro.cobblemon.mcc.betterai.state.PublicTurnProjection> {
        val state = state(user, mon(BattleSide.OPPONENT, "normal"), weather)
        val context = PublicBattleTacticalCalculator.calculate(context(state, candidate))
        val outcomes = PublicSingleTurnProjector.project(
            state, context.candidates.single(), BattleActionCandidate("wait", BattleActionKind.WAIT), context, history,
        )
        assertTrue(outcomes.isNotEmpty())
        return outcomes
    }

    private fun stage(mon: BattlePokemonStateView, stat: String): Int =
        mon.statStages.entries.firstOrNull { LocalStatStageChange.normalise(it.key) == LocalStatStageChange.normalise(stat) }?.value ?: 0

    private fun healOf(moveId: String, weather: String?): Double {
        val user = mon(BattleSide.ALLY, "grass", hp = 0.2)
        val calculated = calculate(move(moveId, BattleMoveTargetPattern.SELF), user, mon(BattleSide.OPPONENT, "normal"), weather)
        return requireNotNull(calculated.facts?.selfHealingFractionRange) { "$moveId has no healing" }.minimum
    }

    private fun calculate(
        candidate: BattleActionCandidate,
        user: BattlePokemonStateView,
        foe: BattlePokemonStateView,
        weather: String? = null,
    ): BattleActionCandidate = PublicBattleTacticalCalculator.calculate(context(state(user, foe, weather), candidate)).candidates.single()

    private fun move(id: String, pattern: BattleMoveTargetPattern) = BattleActionCandidate(
        actionId = id, kind = BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0, moveId = id,
        moveDetails = BattleMoveCandidateView(
            typeId = "normal", damageCategory = BattleMoveDamageCategory.STATUS, power = 0.0, accuracy = 100.0,
            priority = 0, currentPp = 10, targetPattern = pattern, effects = BattleMoveEffectsView(
                BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, effects(id), scriptedBehavior = true),
        ),
    )

    private fun effects(id: String): List<BattleMoveEffectView> = requireNotNull(MOVES[id]) { "no $id" }.effects

    private fun context(state: BattleStateView, candidate: BattleActionCandidate) = BattleDecisionContext(
        requestId = UUID.randomUUID(), state = state, candidates = listOf(candidate),
        deadlineEpochMillis = Long.MAX_VALUE, memory = BattleTacticalMemoryView.empty(),
        publicActionCatalog = BattlePublicActionCatalogView(emptyList()),
    )

    private fun state(user: BattlePokemonStateView, foe: BattlePokemonStateView, weather: String? = null) = BattleStateView(
        battleId = UUID.randomUUID(), format = BattleFormat.SINGLE, turn = 3, pokemon = listOf(user, foe),
        field = BattleFieldStateView(weather?.let { BattleTimedEffectView(it, 3) }, null, emptyList(), emptyList(),
            BattleSide.entries.associateWith { emptyList() }),
        remainingPokemonBySide = BattleSide.entries.associateWith { 1 },
        observedEvents = emptyList(), inferences = emptyList(),
    )

    private fun mon(
        side: BattleSide, type: String, hp: Double = 1.0, maxHp: Int = 300, attack: Int = 120,
        stages: Map<String, Int> = emptyMap(),
        status: String? = null, ability: String? = null, item: String? = null,
        speed: Int = 100,
    ) = BattlePokemonStateView(
        battlePokemonId = UUID.randomUUID(), side = side, activeSlot = 0, speciesId = "showdown:probe", formId = null,
        level = 50, hpFraction = hp, statusId = status, statStages = stages, knownMoveIds = emptySet(),
        knownAbilityId = ability, knownHeldItemId = item, fainted = false, knownTypeIds = setOf(type),
        combatStats = BattleCombatStatRangesView(
            maxHp = BattleIntegerRange(maxHp, maxHp), attack = BattleIntegerRange(attack, attack),
            defence = BattleIntegerRange(100, 100), specialAttack = BattleIntegerRange(100, 100),
            specialDefence = BattleIntegerRange(100, 100), speed = BattleIntegerRange(speed, speed),
            knowledge = BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
        ),
        knownVolatileEffectIds = emptySet(), knownBaseStabTypeIds = setOf(type),
    )

    private companion object {
        val MOVES: Map<String, BattleMoveEffectsView> by lazy {
            ZipInputStream(requireNotNull(LocalCallbackMoveEffectsTest::class.java.getResourceAsStream("/data/cobblemon/showdown.zip"))).use { zip ->
                generateSequence { zip.nextEntry }.first { it.name == "data/moves.js" }
                BattleDeclarativeMoveEffects.parse(zip.readBytes().toString(Charsets.UTF_8))
            }
        }
    }
}
