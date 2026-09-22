package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalDynamicTypePowerProjectionTest {
    @Test
    fun `weather ball resolves effective public weather before every damage consumer`() {
        val cases = listOf(
            null to ("normal" to 50),
            "sunnyday" to ("fire" to 100),
            "raindance" to ("water" to 100),
            "sandstorm" to ("rock" to 100),
            "snowscape" to ("ice" to 100),
        )
        for ((weather, expected) in cases) {
            val state = state(weather = weather)
            assertEquivalent(state, dynamicMove("weatherball"), fixedMove(expected.first, expected.second))
        }
    }

    @Test
    fun `weather suppression leaves weather ball normal and fifty power`() {
        val state = state(weather = "raindance", opponentAbility = "cloudnine")

        assertEquivalent(state, dynamicMove("weatherball"), fixedMove("normal", 50))
    }

    @Test
    fun `utility umbrella suppresses rain and sun for weather ball but not sand or snow`() {
        for (weather in listOf("raindance", "sunnyday")) {
            val state = state(weather = weather, allyItem = "utilityumbrella")
            assertEquivalent(state, dynamicMove("weatherball"), fixedMove("normal", 50))
        }
        assertEquivalent(
            state(weather = "sandstorm", allyItem = "utilityumbrella"),
            dynamicMove("weatherball"),
            fixedMove("rock", 100),
        )
        assertEquivalent(
            state(weather = "snowscape", allyItem = "utilityumbrella"),
            dynamicMove("weatherball"),
            fixedMove("ice", 100),
        )
    }

    @Test
    fun `terrain pulse changes type and power only for a grounded actor`() {
        val cases = listOf(
            "electricterrain" to "electric",
            "grassyterrain" to "grass",
            "mistyterrain" to "fairy",
            "psychicterrain" to "psychic",
        )
        for ((terrain, type) in cases) {
            val grounded = state(terrain = terrain)
            assertEquivalent(grounded, dynamicMove("terrainpulse"), fixedMove(type, 100))

            val airborne = state(terrain = terrain, allyTypes = setOf("flying"))
            assertEquivalent(airborne, dynamicMove("terrainpulse"), fixedMove("normal", 50))
        }

        val levitating = state(terrain = "electricterrain", allyAbility = "levitate")
        assertEquivalent(levitating, dynamicMove("terrainpulse"), fixedMove("normal", 50))
    }

    @Test
    fun `resolved weather ball type participates in doubles redirection`() {
        val state = state(
            format = BattleFormat.DOUBLE,
            weather = "raindance",
            secondOpponentAbility = "stormdrain",
            secondOpponentHp = 0.6,
        )
        val context = BattleDecisionContext(REQUEST_ID, state, listOf(dynamicMove("weatherball")), Long.MAX_VALUE)
        val calculated = PublicBattleTacticalCalculator.calculate(context).candidates.single()
        val calculatedContext = BattleDecisionContext(
            REQUEST_ID,
            state,
            listOf(calculated),
            Long.MAX_VALUE,
        )

        assertEquals("water", calculated.moveDetails?.typeId)
        assertEquals(
            0.6,
            PublicBattleTacticalCalculator.primaryTargetHpFraction(
                calculated,
                calculatedContext,
                BattleSide.ALLY,
            ),
        )
    }

    private fun assertEquivalent(
        state: BattleStateView,
        dynamic: BattleActionCandidate,
        fixed: BattleActionCandidate,
    ) {
        val dynamicContext = BattleDecisionContext(REQUEST_ID, state, listOf(dynamic), Long.MAX_VALUE)
        val fixedContext = BattleDecisionContext(REQUEST_ID, state, listOf(fixed), Long.MAX_VALUE)
        val calculatedDynamic = PublicBattleTacticalCalculator.calculate(dynamicContext).candidates.single()
        val calculatedFixed = PublicBattleTacticalCalculator.calculate(fixedContext).candidates.single()

        assertEquals(calculatedFixed.moveDetails?.typeId, calculatedDynamic.moveDetails?.typeId)
        assertEquals(calculatedFixed.facts?.typeChartMultiplier, calculatedDynamic.facts?.typeChartMultiplier)
        assertEquals(
            calculatedFixed.facts?.standardDamageFractionRange,
            calculatedDynamic.facts?.standardDamageFractionRange,
        )
        assertEquals(
            PublicBattleTacticalCalculator.conservativeDamageRollFractions(
                calculatedFixed,
                BattleDecisionContext(REQUEST_ID, state, listOf(calculatedFixed), Long.MAX_VALUE),
                BattleSide.ALLY,
            ),
            PublicBattleTacticalCalculator.conservativeDamageRollFractions(
                calculatedDynamic,
                BattleDecisionContext(REQUEST_ID, state, listOf(calculatedDynamic), Long.MAX_VALUE),
                BattleSide.ALLY,
            ),
        )
    }

    private fun dynamicMove(id: String) = move(id, type = "normal", power = 50, dynamic = true)

    private fun fixedMove(type: String, power: Int) = move("fixed_${type}_$power", type, power, dynamic = false)

    private fun move(id: String, type: String, power: Int, dynamic: Boolean) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = 0,
        moveSlot = 0,
        moveId = "cobblemon:$id",
        targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
        moveDetails = BattleMoveCandidateView(
            typeId = type,
            damageCategory = BattleMoveDamageCategory.SPECIAL,
            power = power.toDouble(),
            accuracy = 100.0,
            priority = 0,
            currentPp = 10,
            targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
            effects = BattleMoveEffectsView(
                coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                effects = emptyList(),
                scriptedBehavior = dynamic,
                mechanicFlags = if (dynamic) {
                    setOf("dynamic_base_power", "dynamic_move_type")
                } else {
                    emptySet()
                },
            ),
        ),
    )

    private fun state(
        format: BattleFormat = BattleFormat.SINGLE,
        weather: String? = null,
        terrain: String? = null,
        allyTypes: Set<String> = setOf("normal"),
        allyAbility: String? = null,
        allyItem: String? = null,
        opponentAbility: String? = null,
        secondOpponentAbility: String? = null,
        secondOpponentHp: Double = 1.0,
    ): BattleStateView {
        val pokemon = mutableListOf(
            pokemon(ALLY_ID, BattleSide.ALLY, 0, allyTypes, allyAbility, item = allyItem),
            pokemon(OPPONENT_ID, BattleSide.OPPONENT, 0, setOf("normal"), opponentAbility),
        )
        if (format == BattleFormat.DOUBLE) {
            pokemon += pokemon(
                SECOND_OPPONENT_ID,
                BattleSide.OPPONENT,
                1,
                setOf("normal"),
                secondOpponentAbility,
                secondOpponentHp,
            )
        }
        return BattleStateView(
            battleId = BATTLE_ID,
            format = format,
            turn = 1,
            pokemon = pokemon,
            field = BattleFieldStateView(
                weather = weather?.let { BattleTimedEffectView("cobblemon:$it", 3) },
                terrain = terrain?.let { BattleTimedEffectView("cobblemon:$it", 3) },
                roomEffects = emptyList(),
                globalEffects = emptyList(),
                sideConditions = BattleSide.entries.associateWith { emptyList() },
            ),
            remainingPokemonBySide = mapOf(
                BattleSide.ALLY to 1,
                BattleSide.OPPONENT to if (format == BattleFormat.DOUBLE) 2 else 1,
            ),
            observedEvents = emptyList(),
            inferences = emptyList(),
        )
    }

    private fun pokemon(
        id: UUID,
        side: BattleSide,
        slot: Int,
        types: Set<String>,
        ability: String? = null,
        hpFraction: Double = 1.0,
        item: String? = null,
    ) = BattlePokemonStateView(
        battlePokemonId = id,
        side = side,
        activeSlot = slot,
        speciesId = "cobblemon:test",
        formId = null,
        level = 50,
        hpFraction = hpFraction,
        statusId = null,
        statStages = emptyMap(),
        knownMoveIds = emptySet(),
        knownAbilityId = ability,
        knownHeldItemId = item,
        fainted = false,
        knownTypeIds = types,
        combatStats = BattleCombatStatRangesView(
            maxHp = BattleIntegerRange(200, 200),
            attack = BattleIntegerRange(100, 100),
            defence = BattleIntegerRange(100, 100),
            specialAttack = BattleIntegerRange(100, 100),
            specialDefence = BattleIntegerRange(100, 100),
            speed = BattleIntegerRange(100, 100),
            knowledge = if (side == BattleSide.ALLY) {
                BattleCombatStatKnowledge.EXACT_OWN
            } else {
                BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE
            },
        ),
    )

    private companion object {
        val BATTLE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000d000")
        val REQUEST_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000d001")
        val ALLY_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000d002")
        val OPPONENT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000d003")
        val SECOND_OPPONENT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000d004")
    }
}
