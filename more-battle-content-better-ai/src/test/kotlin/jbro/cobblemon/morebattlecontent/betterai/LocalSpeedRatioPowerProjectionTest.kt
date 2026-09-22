package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalSpeedRatioPowerProjectionTest {
    @Test
    fun `electro ball resolves its official speed-ratio tiers`() {
        assertEquals(
            damage(state(allySpeed = 400..400, opponentSpeed = 100..100), fixedPower(150)),
            damage(state(allySpeed = 400..400, opponentSpeed = 100..100), speedMove("electroball")),
        )
        assertEquals(
            damage(state(allySpeed = 100..100, opponentSpeed = 400..400), fixedPower(40)),
            damage(state(allySpeed = 100..100, opponentSpeed = 400..400), speedMove("electroball")),
        )
    }

    @Test
    fun `gyro ball resolves its official ratio and one-point formula`() {
        assertEquals(
            damage(state(allySpeed = 50..50, opponentSpeed = 300..300), fixedPower(150)),
            damage(state(allySpeed = 50..50, opponentSpeed = 300..300), speedMove("gyroball")),
        )
        assertEquals(
            damage(state(allySpeed = 300..300, opponentSpeed = 50..50), fixedPower(5)),
            damage(state(allySpeed = 300..300, opponentSpeed = 50..50), speedMove("gyroball")),
        )
    }

    @Test
    fun `public speed uncertainty widens dynamic power instead of inventing one value`() {
        val electro = damage(state(allySpeed = 100..100, opponentSpeed = 25..100), speedMove("electroball"))
        val electroLow = damage(state(allySpeed = 100..100, opponentSpeed = 100..100), fixedPower(60))
        val electroHigh = damage(state(allySpeed = 100..100, opponentSpeed = 25..25), fixedPower(150))

        assertEquals(electroLow.minimum, electro.minimum)
        assertEquals(electroHigh.maximum, electro.maximum)
        assertTrue(electro.minimum < electro.maximum)
    }

    @Test
    fun `electro ball keeps discrete tiers across the technician threshold`() {
        val ranged = damage(
            state(allySpeed = 100..100, opponentSpeed = 25..100, allyAbility = "technician"),
            speedMove("electroball"),
        )
        val lowestRealTier = damage(
            state(allySpeed = 100..100, opponentSpeed = 100..100, allyAbility = "technician"),
            fixedPower(80),
        )
        val highestTier = damage(
            state(allySpeed = 100..100, opponentSpeed = 25..25, allyAbility = "technician"),
            fixedPower(150),
        )

        assertEquals(lowestRealTier.minimum, ranged.minimum)
        assertEquals(highestTier.maximum, ranged.maximum)
    }

    @Test
    fun `trick room reverses order but does not reverse speed-ratio power`() {
        for (move in listOf("electroball", "gyroball")) {
            val ordinary = damage(state(allySpeed = 300..300, opponentSpeed = 50..50), speedMove(move))
            val room = damage(
                state(allySpeed = 300..300, opponentSpeed = 50..50, trickRoom = true),
                speedMove(move),
            )

            assertEquals(ordinary, room, move)
        }
    }

    @Test
    fun `iron ball changes speed-ratio power from the modified speed`() {
        val ironBallState = state(
            allySpeed = 100..100,
            opponentSpeed = 100..100,
            allyItem = "ironball",
        )

        assertEquals(
            damage(ironBallState, fixedPower(40)),
            damage(ironBallState, speedMove("electroball")),
        )
        assertEquals(
            damage(ironBallState, fixedPower(51)),
            damage(ironBallState, speedMove("gyroball")),
        )
    }

    @Test
    fun `public speed items respect klutz and magic room`() {
        val trainingItems = setOf(
            "machobrace", "poweranklet", "powerband", "powerbelt",
            "powerbracer", "powerlens", "powerweight",
        )
        trainingItems.forEach { item ->
            assertEquals(50 to 50, effectiveAllySpeed(state(
                allySpeed = 100..100,
                opponentSpeed = 100..100,
                allyAbility = "klutz",
                allyItem = item,
            )), item)
        }
        for (item in listOf("choicescarf", "ironball")) {
            assertEquals(100 to 100, effectiveAllySpeed(state(
                allySpeed = 100..100,
                opponentSpeed = 100..100,
                allyAbility = "klutz",
                allyItem = item,
            )), item)
        }
        for (item in trainingItems + setOf("choicescarf", "ironball")) {
            assertEquals(100 to 100, effectiveAllySpeed(state(
                allySpeed = 100..100,
                opponentSpeed = 100..100,
                allyItem = item,
                magicRoom = true,
            )), "magic room $item")
        }
    }

    private fun effectiveAllySpeed(state: BattleStateView): Pair<Int, Int>? =
        jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicTurnOrder.effectiveSpeed(
            state,
            state.pokemon.single { it.side == BattleSide.ALLY },
        )

    private fun damage(state: BattleStateView, action: BattleActionCandidate): BattleDamageFractionRange {
        val calculated = PublicBattleTacticalCalculator.calculate(
            BattleDecisionContext(REQUEST_ID, state, listOf(action), Long.MAX_VALUE),
        ).candidates.single()
        return requireNotNull(calculated.facts?.standardDamageFractionRange) {
            "missing projected damage for ${action.moveId}"
        }
    }

    private fun speedMove(id: String) = move(id, 0.0, dynamic = true)

    private fun fixedPower(power: Int) = move("fixed_$power", power.toDouble(), dynamic = false)

    private fun move(id: String, power: Double, dynamic: Boolean) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = 0,
        moveSlot = 0,
        moveId = "cobblemon:$id",
        targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
        moveDetails = BattleMoveCandidateView(
            typeId = "normal",
            damageCategory = BattleMoveDamageCategory.SPECIAL,
            power = power,
            accuracy = 100.0,
            priority = 0,
            currentPp = 10,
            targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
            effects = BattleMoveEffectsView(
                coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                effects = emptyList(),
                scriptedBehavior = dynamic,
                mechanicFlags = if (dynamic) setOf("dynamic_base_power") else emptySet(),
            ),
        ),
    )

    private fun state(
        allySpeed: IntRange,
        opponentSpeed: IntRange,
        trickRoom: Boolean = false,
        allyAbility: String? = null,
        allyItem: String? = null,
        magicRoom: Boolean = false,
    ) = BattleStateView(
        battleId = BATTLE_ID,
        format = BattleFormat.SINGLE,
        turn = 1,
        pokemon = listOf(
            pokemon(
                ALLY_ID,
                BattleSide.ALLY,
                allySpeed,
                BattleCombatStatKnowledge.EXACT_OWN,
                allyAbility,
                allyItem,
            ),
            pokemon(OPPONENT_ID, BattleSide.OPPONENT, opponentSpeed, BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE),
        ),
        field = BattleFieldStateView(
            weather = null,
            terrain = null,
            roomEffects = buildList {
                if (trickRoom) add(BattleTimedEffectView("cobblemon:trick_room", 3))
                if (magicRoom) add(BattleTimedEffectView("cobblemon:magic_room", 3))
            },
            globalEffects = emptyList(),
            sideConditions = BattleSide.entries.associateWith { emptyList() },
        ),
        remainingPokemonBySide = mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1),
        observedEvents = emptyList(),
        inferences = emptyList(),
    )

    private fun pokemon(
        id: UUID,
        side: BattleSide,
        speed: IntRange,
        knowledge: BattleCombatStatKnowledge,
        ability: String? = null,
        item: String? = null,
    ) = BattlePokemonStateView(
        battlePokemonId = id,
        side = side,
        activeSlot = 0,
        speciesId = "cobblemon:test",
        formId = null,
        level = 50,
        hpFraction = 1.0,
        statusId = null,
        statStages = emptyMap(),
        knownMoveIds = emptySet(),
        knownAbilityId = ability,
        knownHeldItemId = item,
        fainted = false,
        knownTypeIds = setOf("normal"),
        combatStats = BattleCombatStatRangesView(
            maxHp = BattleIntegerRange(200, 200),
            attack = BattleIntegerRange(100, 100),
            defence = BattleIntegerRange(100, 100),
            specialAttack = BattleIntegerRange(100, 100),
            specialDefence = BattleIntegerRange(100, 100),
            speed = BattleIntegerRange(speed.first, speed.last),
            knowledge = knowledge,
        ),
    )

    private companion object {
        val BATTLE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000ca00")
        val REQUEST_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000ca01")
        val ALLY_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000ca02")
        val OPPONENT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000ca03")
    }
}
