package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

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
    fun `leftovers heals one sixteenth at end of turn`() {
        val projected = LocalEndTurnStateProjector.project(
            state(allyHp = 0.5, allyItem = "cobblemon:leftovers"),
        )

        assertEquals(0.5625, projected.pokemon.single { it.battlePokemonId == ALLY_ID }.hpFraction, 1e-9)
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
    ) = BattleStateView(
        battleId = BATTLE_ID,
        format = BattleFormat.SINGLE,
        turn = 1,
        pokemon = listOf(
            pokemon(ALLY_ID, BattleSide.ALLY, "cobblemon:test", allyHp, allyAbility, allyItem, allyStatus),
            pokemon(OPPONENT_ID, BattleSide.OPPONENT, opponentSpecies, opponentHp, opponentAbility, opponentItem),
        ),
        field = BattleFieldStateView.empty(),
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
            accuracy = 100.0,
            priority = 0,
            currentPp = 10,
            targetPattern = BattleMoveTargetPattern.SELECTED,
            effects = BattleMoveEffectsView(
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
