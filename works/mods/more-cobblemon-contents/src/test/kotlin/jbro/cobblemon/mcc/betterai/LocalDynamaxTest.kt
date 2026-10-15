package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.evaluation.LocalMechanicOptionValue
import jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax
import jbro.cobblemon.mcc.betterai.mechanics.LocalPersistentMoveState
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173PublicPersistentMoveState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Dynamax in the legacy calculation: Max Moves' effects read from their `onHit` code, the doubled HP of a Dynamaxed
 * Pokemon and its three turns, and the named callback table kept to moves no shape reads.
 */
class LocalDynamaxTest {
    @Test
    fun `Max Moves carry their side effects read from onHit`() {
        assertTrue(effects("maxairstream").any { it.kind == BattleMoveEffectKind.STAT_STAGE && it.target == BattleMoveEffectTarget.USER &&
            it.statStages == mapOf("speed" to 1) }, effects("maxairstream").toString())
        assertTrue(effects("maxstrike").any { it.kind == BattleMoveEffectKind.STAT_STAGE &&
            it.target == BattleMoveEffectTarget.SELECTED_TARGET && it.statStages == mapOf("speed" to -1) })
        assertTrue(effects("maxflare").any { it.kind == BattleMoveEffectKind.WEATHER && it.valueId == "sunnyday" })
        assertTrue(effects("maxlightning").any { it.kind == BattleMoveEffectKind.TERRAIN && it.valueId == "electricterrain" })
        assertTrue(effects("gmaxsteelsurge").any { it.kind == BattleMoveEffectKind.SIDE_CONDITION &&
            it.target == BattleMoveEffectTarget.TARGET_SIDE && it.valueId == "gmaxsteelsurge" })
        // Every Max Move with an onHit is read.
        val readable = BattleDeclarativeMoveEffects.shapedHandlersReadable(SOURCE)
        val maxMoves = Regex("""\n  (max[a-z]+): \{""").findAll(SOURCE).map { it.groupValues[1] }.filter { it in readable }.toList()
        assertEquals(19, maxMoves.size, maxMoves.toString())
        assertTrue(maxMoves.all { readable.getValue(it) }, maxMoves.filterNot { readable.getValue(it) }.toString())
        // G-Max Moves: all but the ones whose onHit is logic (a move's PP, a Berry, a list of screens, a team cure, a
        // condition on the target, a sleep that checks for a status).
        val gmax = readable.filterKeys { it.startsWith("gmax") }
        assertEquals(setOf("gmaxdepletion", "gmaxreplenish", "gmaxwindrage", "gmaxsweetness", "gmaxmeltdown", "gmaxsnooze"),
            gmax.filterValues { !it }.keys)
        assertTrue(effects("gmaxsandblast").any { it.kind == BattleMoveEffectKind.VOLATILE_STATUS && it.valueId == "partiallytrapped" })
    }

    @Test
    fun `moves taken off the named table keep their effects through the shapes`() {
        assertEquals(0.5, effects("filletaway").single { it.kind == BattleMoveEffectKind.MAX_HP_RECOIL }.fractionRange!!.minimum, 1e-9)
        val dire = effects("direclaw").filter { it.kind == BattleMoveEffectKind.STATUS }
        assertEquals(setOf("psn", "par", "slp"), dire.mapTo(hashSetOf()) { it.valueId })
        assertTrue(dire.all { kotlin.math.abs(it.probability!! - 0.5 / 3.0) < 1e-9 }, dire.toString())
        val tri = effects("triattack").filter { it.kind == BattleMoveEffectKind.STATUS }
        assertEquals(setOf("brn", "par", "frz"), tri.mapTo(hashSetOf()) { it.valueId })
        // Protect's stall counter stays a flag, not a volatile on the user.
        assertTrue(effects("protect").none { it.valueId == "stall" }, effects("protect").toString())
    }

    @Test
    fun `the named callback table holds only moves no shape reads`() {
        val readable = BattleDeclarativeMoveEffects.shapedHandlersReadable(SOURCE)
        // Curse's Ghost half (an HP cost in onHit) reads as a shape; its other half is set in onTryHit, which no shape reads.
        val readableOnHitOnly = setOf("curse")
        val wronglyListed = BattleDeclarativeMoveEffects.CALLBACK_EFFECT_MOVES.filter { readable[it] == true && it !in readableOnHitOnly }
        assertTrue(wronglyListed.isEmpty(), "read as shapes, so off the table: $wronglyListed")
        assertEquals(11, BattleDeclarativeMoveEffects.CALLBACK_EFFECT_MOVES.size, BattleDeclarativeMoveEffects.CALLBACK_EFFECT_MOVES.toString())
    }

    @Test
    fun `a Dynamaxed target takes half the share of a hit and of a set amount`() {
        val user = mon(BattleSide.ALLY)
        val plain = mon(BattleSide.OPPONENT)
        val dynamaxed = LocalDynamax.start(plain)
        val hit = attack("tackle", 80.0)
        val normal = rolls(hit, user, plain)
        val halved = rolls(hit, user, dynamaxed)
        assertEquals(normal.average() / 2.0, halved.average(), 1e-9)
        val seismic = attack("seismictoss", 1.0, BattleMoveEffectView(BattleMoveEffectKind.FIXED_DAMAGE_LEVEL, BattleMoveEffectTarget.SELECTED_TARGET, 1.0))
        assertEquals(rolls(seismic, user, plain).average() / 2.0, rolls(seismic, user, dynamaxed).average(), 1e-9)
    }

    @Test
    fun `Dynamax lasts three turns, the one it starts on included`() {
        val started = Cobblemon173PublicPersistentMoveState.afterVolatile(emptySet(), "dynamax", true, null, null)
        assertEquals(setOf("dynamax", "dynamaxturns:3"), started)
        assertEquals(setOf("dynamax", "dynamaxturns:2"), Cobblemon173PublicPersistentMoveState.advance(started, 1))
        assertFalse(LocalDynamax.active(mon(BattleSide.OPPONENT).copyState(
            knownVolatileEffectIds = Cobblemon173PublicPersistentMoveState.advance(started, 3))))
        assertEquals(emptySet<String>(), Cobblemon173PublicPersistentMoveState.afterVolatile(started, "dynamax", false, null, null))

        // In projection, the turn-end step counts it down and ends it.
        var state = state(mon(BattleSide.ALLY), LocalDynamax.start(mon(BattleSide.OPPONENT)))
        repeat(2) { state = LocalPersistentMoveState.afterResidual(state) }
        assertTrue(LocalDynamax.active(state.pokemon[1]))
        state = LocalPersistentMoveState.afterResidual(state)
        assertFalse(LocalDynamax.active(state.pokemon[1]), state.pokemon[1].knownVolatileEffectIds.toString())
        assertNotEquals(null, state.pokemon[1])
    }

    @Test
    fun `a Dynamaxed Pokemon's attack is read as the Max Move of its type`() {
        val flamethrower = BattleActionCandidate("flamethrower", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
            moveId = "flamethrower", targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
            moveDetails = BattleMoveCandidateView("fire", BattleMoveDamageCategory.SPECIAL, 90.0, 100.0, 0, 15,
                targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT))
        val user = mon(BattleSide.ALLY)
        assertTrue(LocalDynamax.asMaxMove(flamethrower, user) === flamethrower)
        val max = LocalDynamax.asMaxMove(flamethrower, LocalDynamax.start(user))
        assertEquals("maxflare", max.moveId)
        assertEquals(130.0, max.moveDetails!!.power, 1e-9)
        assertTrue(max.moveDetails!!.effects!!.effects.any { it.kind == BattleMoveEffectKind.WEATHER && it.valueId == "sunnyday" })
        assertEquals("flamethrower", LocalDynamax.baseMoveId(max))
    }

    @Test
    fun `a Dynamax status move is ranked as Max Guard, not as the move it replaces`() {
        val guard = BattleMoveCandidateView("normal", BattleMoveDamageCategory.STATUS, 0.0, 100.0, 4, 10,
            BattleMoveTargetPattern.SELF, effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, listOf(
                BattleMoveEffectView(BattleMoveEffectKind.PROTECT_USER, BattleMoveEffectTarget.USER, probability = 1.0)), false))
        val (id, tags) = LocalDynamax.choiceIdentity("cobblemon:leechseed", guard, "Max Guard")
        assertEquals("maxguard", id)
        val user = mon(BattleSide.ALLY).copyState(knownVolatileEffectIds = setOf(LocalDynamax.VOLATILE))
        val foe = mon(BattleSide.OPPONENT)
        val candidate = BattleActionCandidate("move:0:3", BattleActionKind.USE_MOVE, 0, 3, id, moveDetails = guard, tags = tags)
        assertEquals("leechseed", LocalDynamax.baseMoveId(candidate))
        // Ranked under Leech Seed's id it took Leech Seed's drain (2.5 turns of an eighth, 31.25).
        val context = BattleDecisionContext(UUID.randomUUID(), state(user, foe), listOf(candidate), Long.MAX_VALUE)
        assertNotEquals(31.25, jbro.cobblemon.mcc.betterai.evaluation.LocalNonDamagingMoveEvaluator.pressure(candidate, context, 1.0))
        // An attack keeps its base id (the matchup table turns it into its Max Move itself, G-946).
        val attack = guard.copy(damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 120.0)
        assertEquals("cobblemon:powerwhip" to emptySet<String>(), LocalDynamax.choiceIdentity("cobblemon:powerwhip", attack, "maxovergrowth"))
        assertEquals("cobblemon:leechseed" to emptySet<String>(), LocalDynamax.choiceIdentity("cobblemon:leechseed", guard, null))
    }

    @Test
    fun `a Gigantamax Pokemon's attack of its G-Max Move's type is that G-Max Move`() {
        fun fire(id: String, power: Double) = BattleActionCandidate(id, BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
            moveId = id, targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
            moveDetails = BattleMoveCandidateView("fire", BattleMoveDamageCategory.SPECIAL, power, 100.0, 0, 15,
                targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT))
        val charizard = mon(BattleSide.ALLY, "cobblemon:charizard")
        // Without the factor, the plain Max Move.
        assertEquals("maxflare", LocalDynamax.asMaxMove(fire("flamethrower", 90.0), LocalDynamax.start(charizard)).moveId)
        val wildfire = LocalDynamax.asMaxMove(fire("flamethrower", 90.0), LocalDynamax.start(charizard, gigantamax = true))
        assertEquals("gmaxwildfire", wildfire.moveId)
        assertEquals(130.0, wildfire.moveDetails!!.power, 1e-9, "the move's own Max power, not the G-Max placeholder")
        assertTrue(wildfire.moveDetails!!.effects!!.effects.any { it.kind == BattleMoveEffectKind.SIDE_CONDITION &&
            it.target == BattleMoveEffectTarget.TARGET_SIDE && it.valueId == "gmaxwildfire" })
        // A G-Max Move Showdown keeps at its own power.
        val fireball = LocalDynamax.asMaxMove(fire("pyroball", 120.0),
            LocalDynamax.start(mon(BattleSide.ALLY, "cobblemon:cinderace"), gigantamax = true))
        assertEquals("gmaxfireball", fireball.moveId)
        assertEquals(160.0, fireball.moveDetails!!.power, 1e-9)
        assertTrue(LocalDynamax.isGMaxMove("gmaxwildfire"))
        assertFalse(LocalDynamax.isGMaxMove("maxflare"))
    }

    @Test
    fun `a public Gigantamax start is remembered past its end`() {
        val started = Cobblemon173PublicPersistentMoveState.afterVolatile(emptySet(), LocalDynamax.GIGANTAMAX_START, true, null, null)
        assertEquals(setOf("dynamax", "dynamaxturns:3", "gigantamax"), started)
        assertEquals(setOf("gigantamax"), Cobblemon173PublicPersistentMoveState.afterVolatile(started, "dynamax", false, null, null))
    }

    @Test
    fun `species data decides who can Dynamax`() {
        assertTrue(LocalDynamax.canDynamax(mon(BattleSide.ALLY, "cobblemon:snorlax")))
        assertFalse(LocalDynamax.canDynamax(mon(BattleSide.ALLY, "cobblemon:zacian")))
    }

    @Test
    fun `Dynamax is held back only for an ally that gains by it and can use it`() {
        val boss = BattleTrainerProfile.boss()
        val able = LocalMechanicOptionValue.cost(dynamaxTackle(), duel("cobblemon:snorlax"), boss)
        val unable = LocalMechanicOptionValue.cost(dynamaxTackle(), duel("cobblemon:zacian"), boss)
        // The benched Snorlax loses the duel unless it Dynamaxes; a Zacian cannot, so nothing is kept for it.
        assertTrue(able > 0.0, "able=$able")
        assertEquals(0.0, unable, 1e-9)
    }

    @Test
    fun `nothing is held back for an ally its trainer does not let Dynamax`() {
        val boss = BattleTrainerProfile.boss()
        val context = duel("cobblemon:snorlax")
        val bench = context.state.pokemon.first { it.side == BattleSide.ALLY && it.activeSlot == null }
        fun build(id: UUID, allowed: Boolean) = BattleExactPokemonBuildView(id, "thickfat", null, "cobblemon:hardy", "N",
            EVS, EVS.mapValues { 31 }, null, null, dynamaxAllowed = allowed)
        val active = context.state.pokemon.first { it.side == BattleSide.ALLY && it.activeSlot == 0 }
        val barred = context.copy(exactOwnTeam = BattleExactOwnTeamView(listOf(build(active.battlePokemonId, true), build(bench.battlePokemonId, false))))
        assertTrue(LocalMechanicOptionValue.cost(dynamaxTackle(), context, boss) > 0.0)
        assertEquals(0.0, LocalMechanicOptionValue.cost(dynamaxTackle(), barred, boss), 1e-9)
    }

    /** The active ally, a benched [benchSpecies] that loses to the faster foe unless Dynamaxed, and that foe. */
    private fun duel(benchSpecies: String): BattleDecisionContext {
        val active = mon(BattleSide.ALLY, "cobblemon:charizard")
        val bench = mon(BattleSide.ALLY, benchSpecies).copyState(activeSlot = null)
        val foe = mon(BattleSide.OPPONENT, speed = 120)
        val tackle = dynamaxTackle().moveDetails!!
        val catalog = BattlePublicActionCatalogView(listOf(active, bench, foe).map {
            BattlePokemonActionCatalogView(it.battlePokemonId,
                listOf(BattlePublicMoveOptionView("tackle", tackle, BattlePublicMoveKnowledge.PUBLICLY_REVEALED)), moveSetComplete = true)
        })
        val state = BattleStateView(
            battleId = UUID.randomUUID(), format = BattleFormat.SINGLE, turn = 3, pokemon = listOf(active, bench, foe),
            field = BattleFieldStateView(null, null, emptyList(), emptyList(), BattleSide.entries.associateWith { emptyList() }),
            remainingPokemonBySide = mapOf(BattleSide.ALLY to 2, BattleSide.OPPONENT to 1),
            observedEvents = emptyList(), inferences = emptyList(),
        )
        return BattleDecisionContext(UUID.randomUUID(), state, listOf(dynamaxTackle()), Long.MAX_VALUE,
            BattleTacticalMemoryView.empty(), publicActionCatalog = catalog)
    }

    private fun dynamaxTackle() = BattleActionCandidate("tackle-max", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
        moveId = "tackle", targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
        mechanic = BattleMechanicCandidate("dynamax", null, null),
        moveDetails = BattleMoveCandidateView("normal", BattleMoveDamageCategory.PHYSICAL, 40.0, 100.0, 0, 35,
            targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT))

    private fun rolls(candidate: BattleActionCandidate, user: BattlePokemonStateView, foe: BattlePokemonStateView): List<Double> {
        val context = context(state(user, foe), candidate)
        val calculated = PublicBattleTacticalCalculator.calculate(context)
        return requireNotNull(PublicBattleTacticalCalculator.conservativeDamageRollFractions(calculated.candidates.single(), calculated, BattleSide.ALLY))
    }

    private fun attack(id: String, power: Double, vararg effects: BattleMoveEffectView) = BattleActionCandidate(
        actionId = id, kind = BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0, moveId = id,
        targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
        moveDetails = BattleMoveCandidateView(
            typeId = "normal", damageCategory = BattleMoveDamageCategory.PHYSICAL, power = power, accuracy = 100.0,
            priority = 0, currentPp = 10, targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
            effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, effects.toList(), scriptedBehavior = false),
        ),
    )

    private fun effects(id: String): List<BattleMoveEffectView> = requireNotNull(MOVES[id]) { "no $id" }.effects

    private fun context(state: BattleStateView, candidate: BattleActionCandidate) = BattleDecisionContext(
        requestId = UUID.randomUUID(), state = state, candidates = listOf(candidate),
        deadlineEpochMillis = Long.MAX_VALUE, memory = BattleTacticalMemoryView.empty(),
        publicActionCatalog = BattlePublicActionCatalogView(emptyList()),
    )

    private fun state(user: BattlePokemonStateView, foe: BattlePokemonStateView) = BattleStateView(
        battleId = UUID.randomUUID(), format = BattleFormat.SINGLE, turn = 3, pokemon = listOf(user, foe),
        field = BattleFieldStateView(null, null, emptyList(), emptyList(), BattleSide.entries.associateWith { emptyList() }),
        remainingPokemonBySide = BattleSide.entries.associateWith { 1 },
        observedEvents = emptyList(), inferences = emptyList(),
    )

    private fun mon(side: BattleSide, species: String = "showdown:probe", speed: Int = 100) = BattlePokemonStateView(
        battlePokemonId = UUID.randomUUID(), side = side, activeSlot = 0, speciesId = species, formId = null,
        level = 50, hpFraction = 1.0, statusId = null, statStages = emptyMap(), knownMoveIds = emptySet(),
        knownAbilityId = null, knownHeldItemId = null, fainted = false, knownTypeIds = setOf("water"),
        combatStats = BattleCombatStatRangesView(
            maxHp = BattleIntegerRange(300, 300), attack = BattleIntegerRange(150, 150),
            defence = BattleIntegerRange(100, 100), specialAttack = BattleIntegerRange(100, 100),
            specialDefence = BattleIntegerRange(100, 100), speed = BattleIntegerRange(speed, speed),
            knowledge = BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
        ),
        knownVolatileEffectIds = emptySet(), knownBaseStabTypeIds = setOf("water"),
    )

    private companion object {
        val EVS = listOf("hp", "atk", "def", "spa", "spd", "spe").associateWith { 0 }
        val SOURCE: String by lazy {
            ZipInputStream(requireNotNull(LocalDynamaxTest::class.java.getResourceAsStream("/data/cobblemon/showdown.zip"))).use { zip ->
                generateSequence { zip.nextEntry }.first { it.name == "data/moves.js" }
                zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        val MOVES: Map<String, BattleMoveEffectsView> by lazy { BattleDeclarativeMoveEffects.parse(SOURCE) }
    }
}
