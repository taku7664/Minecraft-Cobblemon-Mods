package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicAccuracy
import jbro.cobblemon.morebattlecontent.betterai.outcome.PublicSingleTurnProjector
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionOutcomeEvaluator
import jbro.cobblemon.morebattlecontent.betterai.state.LocalEndTurnStateProjector
import jbro.cobblemon.morebattlecontent.betterai.state.PublicTurnProjection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalBattleItemAbilityMechanicsTest {
    @Test
    fun `rocky helmet and rough skin stack after a damaging contact hit`() {
        val initial = state(
            opponentAbility = "cobblemon:rough_skin",
            opponentItem = "cobblemon:rocky_helmet",
        )

        val attackerHp = turn(initial, move("contact", power = 40.0, contact = true)).map { outcome ->
            outcome.state.pokemon.single { it.battlePokemonId == ALLY_ID }.hpFraction
        }.distinct()

        assertEquals(1, attackerHp.size)
        assertEquals(1.0 - 1.0 / 6.0 - 1.0 / 8.0, attackerHp.single(), 1e-9)
    }

    @Test
    fun `flame body branches into a thirty percent burn after contact damage`() {
        val initial = state(opponentAbility = "cobblemon:flame_body")

        val outcomes = turn(initial, move("contact", power = 40.0, contact = true))
        val burnProbability = outcomes.filter { outcome ->
            outcome.state.pokemon.single { it.battlePokemonId == ALLY_ID }.statusId == "cobblemon:burn"
        }.sumOf { it.probability }

        assertEquals(0.30, burnProbability, 1e-9)
    }

    @Test
    fun `focus sash is consumed and leaves exactly one hp from full health`() {
        val initial = state(opponentItem = "cobblemon:focus_sash")

        val outcome = turn(initial, move("knockout", power = 1_000.0)).single()
        val target = outcome.state.pokemon.single { it.battlePokemonId == OPPONENT_ID }

        assertEquals(1.0 / 200.0, target.hpFraction, 1e-9)
        assertNull(target.knownHeldItemId)
    }

    @Test
    fun `focus sash does not activate below full health`() {
        val initial = state(opponentHp = 0.75, opponentItem = "cobblemon:focus_sash")

        val outcome = turn(initial, move("knockout", power = 1_000.0)).single()
        val target = outcome.state.pokemon.single { it.battlePokemonId == OPPONENT_ID }

        assertEquals(0.0, target.hpFraction, 1e-9)
        assertEquals("cobblemon:focus_sash", target.knownHeldItemId)
    }

    @Test
    fun `klutz suppresses focus sash without consuming it`() {
        val initial = state(
            opponentAbility = "cobblemon:klutz",
            opponentItem = "cobblemon:focus_sash",
        )

        val outcome = turn(initial, move("knockout", power = 1_000.0)).single()
        val target = outcome.state.pokemon.single { it.battlePokemonId == OPPONENT_ID }

        assertEquals(0.0, target.hpFraction, 1e-9)
        assertEquals("cobblemon:focus_sash", target.knownHeldItemId)
    }

    @Test
    fun `disguise blocks only the first damaging hit and changes to busted form`() {
        val initial = state(
            opponentAbility = "cobblemon:disguise",
            opponentSpecies = "cobblemon:mimikyu",
        )
        val hit = move("heavy_hit", power = 150.0)

        val first = turn(initial, hit).single().state
        val disguised = first.pokemon.single { it.battlePokemonId == OPPONENT_ID }
        assertEquals(0.875, disguised.hpFraction, 1e-9)
        assertTrue(disguised.formId.orEmpty().contains("busted"))

        val second = turn(first, hit)
        assertTrue(second.all { outcome ->
            outcome.state.pokemon.single { it.battlePokemonId == OPPONENT_ID }.hpFraction < 0.875
        })
    }

    @Test
    fun `technician uses ninety power for a sixty power move but not for sixty one`() {
        val sixty = move("sixty", power = 60.0)
        val ninety = move("ninety", power = 90.0)
        val sixtyOne = move("sixty_one", power = 61.0)

        val technicianSixty = damageRange(state(allyAbility = "cobblemon:technician"), sixty)
        val plainNinety = damageRange(state(), ninety)
        val technicianSixtyOne = damageRange(state(allyAbility = "cobblemon:technician"), sixtyOne)
        val plainSixtyOne = damageRange(state(), sixtyOne)

        assertEquals(plainNinety, technicianSixty)
        assertEquals(plainSixtyOne, technicianSixtyOne)
    }

    @Test
    fun `assault vest increases only known holder special defence`() {
        val specialMove = move("special", power = 90.0, category = BattleMoveDamageCategory.SPECIAL)

        val plain = damageRange(state(), specialMove)
        val vested = damageRange(state(opponentItem = "cobblemon:assault_vest"), specialMove)

        assertTrue(vested.maximum < plain.maximum)
        assertTrue(vested.minimum < plain.minimum)
    }

    @Test
    fun `klutz suppresses assault vest damage reduction`() {
        val specialMove = move("special", power = 90.0, category = BattleMoveDamageCategory.SPECIAL)

        val plain = damageRange(state(), specialMove)
        val klutzVest = damageRange(
            state(opponentAbility = "cobblemon:klutz", opponentItem = "cobblemon:assault_vest"),
            specialMove,
        )

        assertEquals(plain, klutzVest)
    }

    @Test
    fun `leftovers heals one sixteenth rounded down to integer HP`() {
        val projected = LocalEndTurnStateProjector.project(
            state(allyHp = 0.5, allyItem = "cobblemon:leftovers"),
        )

        assertEquals(112.0 / 200.0, projected.pokemon.single { it.battlePokemonId == ALLY_ID }.hpFraction, 1e-9)
    }

    @Test
    fun `klutz suppresses leftovers and rocky helmet`() {
        val leftovers = LocalEndTurnStateProjector.project(
            state(
                allyHp = 0.5,
                allyAbility = "cobblemon:klutz",
                allyItem = "cobblemon:leftovers",
            ),
        )
        val helmet = turn(
            state(opponentAbility = "cobblemon:klutz", opponentItem = "cobblemon:rocky_helmet"),
            move("contact", power = 40.0, contact = true),
        )

        assertEquals(0.5, leftovers.pokemon.single { it.battlePokemonId == ALLY_ID }.hpFraction, 1e-9)
        assertTrue(helmet.all { outcome ->
            outcome.state.pokemon.single { it.battlePokemonId == ALLY_ID }.hpFraction == 1.0
        })
    }

    @Test
    fun `salt cure removes one eighth at end of turn`() {
        val projected = LocalEndTurnStateProjector.project(
            state(opponentHp = 0.75),
            saltCuredPokemonIds = setOf(OPPONENT_ID),
        )

        assertEquals(0.625, projected.pokemon.single { it.battlePokemonId == OPPONENT_ID }.hpFraction, 1e-9)
    }

    @Test
    fun `poison heal converts poison residual into one eighth healing`() {
        val projected = LocalEndTurnStateProjector.project(
            state(allyHp = 0.5, allyAbility = "cobblemon:poison_heal", allyStatus = "cobblemon:poison"),
        )

        assertEquals(0.625, projected.pokemon.single { it.battlePokemonId == ALLY_ID }.hpFraction, 1e-9)
    }

    @Test
    fun `paralysis branches into twenty five percent full paralysis`() {
        val outcomes = turn(state(allyStatus = "cobblemon:paralysis"), move("hit", power = 80.0))
        val unableProbability = outcomes.filter { outcome ->
            outcome.state.pokemon.single { it.battlePokemonId == OPPONENT_ID }.hpFraction == 1.0
        }.sumOf(PublicTurnProjection::probability)

        assertEquals(0.25, unableProbability, 1e-9)
    }

    @Test
    fun `ranged public duration counts down instead of freezing in recursive turns`() {
        val initial = state(
            field = BattleFieldStateView(
                weather = BattleTimedEffectView(
                    effectId = "cobblemon:rain",
                    remainingTurns = null,
                    remainingTurnsRange = BattleIntegerRange(5, 8),
                ),
                terrain = null,
                roomEffects = emptyList(),
                globalEffects = emptyList(),
                sideConditions = BattleSide.entries.associateWith { emptyList() },
            ),
        )

        val projected = LocalEndTurnStateProjector.project(initial)

        assertEquals(BattleIntegerRange(4, 7), projected.field.weather?.remainingTurnsRange)
    }

    @Test
    fun `crash move miss removes half of the users maximum hp`() {
        val crash = BattleMoveEffectsView(
            coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
            effects = listOf(
                BattleMoveEffectView(
                    kind = BattleMoveEffectKind.CRASH_RECOIL,
                    target = BattleMoveEffectTarget.USER,
                    probability = 1.0,
                ),
            ),
            scriptedBehavior = false,
        )

        val outcome = turn(state(), move("high_jump_kick", power = 130.0, accuracy = 0.0, effects = crash)).single()

        assertEquals(0.5, outcome.state.pokemon.single { it.battlePokemonId == ALLY_ID }.hpFraction, 1e-9)
    }

    @Test
    fun `no guard makes the public hit chance certain in every scoring layer`() {
        val initial = state(allyAbility = "cobblemon:no_guard")
        val action = move("dynamic_punch", power = 100.0, accuracy = 50.0)
        val calculatedContext = calculatedContext(initial, action)
        val calculated = calculatedContext.candidates.single()

        assertEquals(0.50, requireNotNull(calculated.facts?.baseAccuracyProbability), 1e-9)
        assertEquals(1.0, LocalPublicAccuracy.probability(calculated, calculatedContext, BattleSide.ALLY), 1e-9)
        assertEquals(
            1.0,
            requireNotNull(
                LocalBattleActionOutcomeEvaluator.evaluate(
                    calculated,
                    calculatedContext,
                    strategy = null,
                    profile = BattleTrainerProfile.balanced(),
                ).effectiveAccuracyProbability,
            ),
            1e-9,
        )
        assertEquals(
            1.0,
            effectiveAccuracy(state(opponentAbility = "cobblemon:no_guard"), action),
            1e-9,
        )
    }

    @Test
    fun `compound eyes and hustle change public accuracy by showdown fixed modifiers`() {
        val seventy = move("seventy", power = 80.0, accuracy = 70.0)
        val compoundEyes = effectiveAccuracy(state(allyAbility = "cobblemon:compound_eyes"), seventy)
        val hustlePhysical = effectiveAccuracy(
            state(allyAbility = "cobblemon:hustle"),
            move("physical", power = 80.0, accuracy = 100.0),
        )
        val hustleSpecial = effectiveAccuracy(
            state(allyAbility = "cobblemon:hustle"),
            move("special", power = 80.0, category = BattleMoveDamageCategory.SPECIAL, accuracy = 100.0),
        )

        assertEquals(0.70 * 5325.0 / 4096.0, compoundEyes, 1e-9)
        assertEquals(3277.0 / 4096.0, hustlePhysical, 1e-9)
        assertEquals(1.0, hustleSpecial, 1e-9)
    }

    @Test
    fun `weather resolves thunder hurricane and blizzard accuracy before score`() {
        val rain = weather("cobblemon:rain")
        val sun = weather("cobblemon:sunny_day")
        val snow = weather("cobblemon:snow")

        assertEquals(1.0, effectiveAccuracy(state(field = rain), move("thunder", 110.0, accuracy = 70.0)), 1e-9)
        assertEquals(1.0, effectiveAccuracy(state(field = rain), move("hurricane", 110.0, accuracy = 70.0)), 1e-9)
        assertEquals(0.5, effectiveAccuracy(state(field = sun), move("thunder", 110.0, accuracy = 70.0)), 1e-9)
        assertEquals(1.0, effectiveAccuracy(state(field = snow), move("blizzard", 110.0, accuracy = 70.0)), 1e-9)
    }

    @Test
    fun `hustle attack boost remains active inside magic room`() {
        val physical = move("physical", power = 80.0)
        val plain = damageRange(state(), physical)
        val hustle = damageRange(state(allyAbility = "cobblemon:hustle"), physical)
        val hustleMagicRoom = damageRange(
            state(
                allyAbility = "cobblemon:hustle",
                field = BattleFieldStateView(
                    weather = null,
                    terrain = null,
                    roomEffects = listOf(BattleTimedEffectView("cobblemon:magic_room", 3)),
                    globalEffects = emptyList(),
                    sideConditions = BattleSide.entries.associateWith { emptyList() },
                ),
            ),
            physical,
        )

        assertTrue(hustle.minimum > plain.minimum)
        assertEquals(hustle, hustleMagicRoom)
    }

    private fun effectiveAccuracy(state: BattleStateView, action: BattleActionCandidate): Double {
        val calculated = calculatedContext(state, action)
        return LocalPublicAccuracy.probability(calculated.candidates.single(), calculated, BattleSide.ALLY)
    }

    private fun calculatedContext(state: BattleStateView, action: BattleActionCandidate): BattleDecisionContext {
        val raw = context(state, action)
        val calculated = PublicBattleTacticalCalculator.calculate(raw).candidates.single()
        return BattleDecisionContext(
            requestId = raw.requestId,
            state = raw.state,
            candidates = listOf(calculated),
            deadlineEpochMillis = raw.deadlineEpochMillis,
            memory = raw.memory,
            publicActionCatalog = raw.publicActionCatalog,
        )
    }

    private fun weather(id: String) = BattleFieldStateView(
        weather = BattleTimedEffectView(id, 3),
        terrain = null,
        roomEffects = emptyList(),
        globalEffects = emptyList(),
        sideConditions = BattleSide.entries.associateWith { emptyList() },
    )

    private fun damageRange(state: BattleStateView, action: BattleActionCandidate): BattleDamageFractionRange =
        requireNotNull(PublicBattleTacticalCalculator.calculate(context(state, action)).candidates.single()
            .facts?.standardDamageFractionRange)

    private fun turn(state: BattleStateView, action: BattleActionCandidate): List<PublicTurnProjection> =
        PublicSingleTurnProjector.project(state, action, BattleActionCandidate("wait", BattleActionKind.WAIT), context(state, action))

    private fun context(state: BattleStateView, action: BattleActionCandidate) = BattleDecisionContext(
        requestId = REQUEST_ID,
        state = state,
        candidates = listOf(action),
        deadlineEpochMillis = Long.MAX_VALUE,
    )

    private fun state(
        allyAbility: String? = null,
        allyItem: String? = null,
        allyHp: Double = 1.0,
        allyStatus: String? = null,
        opponentAbility: String? = null,
        opponentItem: String? = null,
        opponentHp: Double = 1.0,
        opponentSpecies: String = "cobblemon:test",
        field: BattleFieldStateView = BattleFieldStateView.empty(),
    ) = BattleStateView(
        battleId = BATTLE_ID,
        format = BattleFormat.SINGLE,
        turn = 1,
        pokemon = listOf(
            pokemon(ALLY_ID, BattleSide.ALLY, "cobblemon:test", allyHp, allyAbility, allyItem, allyStatus),
            pokemon(OPPONENT_ID, BattleSide.OPPONENT, opponentSpecies, opponentHp, opponentAbility, opponentItem),
        ),
        field = field,
        remainingPokemonBySide = mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1),
        observedEvents = emptyList(),
        inferences = emptyList(),
    )

    private fun pokemon(
        id: UUID,
        side: BattleSide,
        species: String,
        hp: Double,
        ability: String?,
        item: String?,
        status: String? = null,
    ) = BattlePokemonStateView(
        battlePokemonId = id,
        side = side,
        activeSlot = 0,
        speciesId = species,
        formId = null,
        level = 50,
        hpFraction = hp,
        statusId = status,
        statStages = emptyMap(),
        knownMoveIds = emptySet(),
        knownAbilityId = ability,
        knownHeldItemId = item,
        fainted = false,
        knownTypeIds = setOf("normal"),
        combatStats = if (side == BattleSide.ALLY) {
            BattleCombatStatRangesView.exact(200, 120, 100, 120, 100, 100)
        } else {
            BattleCombatStatRangesView(
                maxHp = BattleIntegerRange(200, 200),
                attack = BattleIntegerRange(120, 120),
                defence = BattleIntegerRange(100, 100),
                specialAttack = BattleIntegerRange(120, 120),
                specialDefence = BattleIntegerRange(100, 100),
                speed = BattleIntegerRange(100, 100),
                knowledge = BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
            )
        },
    )

    private fun move(
        id: String,
        power: Double,
        contact: Boolean = false,
        category: BattleMoveDamageCategory = BattleMoveDamageCategory.PHYSICAL,
        accuracy: Double = 100.0,
        effects: BattleMoveEffectsView? = null,
    ) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = 0,
        moveSlot = 0,
        moveId = "cobblemon:$id",
        targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
        moveDetails = BattleMoveCandidateView(
            typeId = "normal",
            damageCategory = category,
            power = power,
            accuracy = accuracy,
            priority = 0,
            currentPp = 10,
            targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
            effects = effects ?: BattleMoveEffectsView(
                coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                effects = emptyList(),
                scriptedBehavior = false,
                mechanicFlags = if (contact) setOf("contact") else emptySet(),
            ),
        ),
    )

    private companion object {
        val BATTLE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000701")
        val REQUEST_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000702")
        val ALLY_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000703")
        val OPPONENT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000704")
    }
}
