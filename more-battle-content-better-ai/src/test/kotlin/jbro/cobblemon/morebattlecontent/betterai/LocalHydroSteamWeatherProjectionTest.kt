package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicMechanicsKernel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalHydroSteamWeatherProjectionTest {
    @Test
    fun `hydro steam reverses ordinary water weakening in regular sun`() {
        assertEquals(1.0, multiplier("hydrosteam"))
        assertEquals(0.5, multiplier("surf", weather = "sunnyday"))
        assertEquals(1.5, multiplier("hydrosteam", weather = "sunnyday"))
    }

    @Test
    fun `utility umbrella suppresses hydro steam's regular sun boost`() {
        assertEquals(
            1.0,
            multiplier("hydrosteam", weather = "sunnyday", actorItem = "utilityumbrella"),
        )
    }

    @Test
    fun `hydro steam remains suppressed by harsh sunlight and boosted by rain`() {
        assertEquals(0.0, multiplier("hydrosteam", weather = "desolateland"))
        assertEquals(1.5, multiplier("hydrosteam", weather = "raindance"))
    }

    private fun multiplier(
        moveId: String,
        weather: String? = null,
        actorItem: String? = null,
    ): Double {
        val candidate = BattleActionCandidate(
            actionId = moveId,
            kind = BattleActionKind.USE_MOVE,
            actorSlot = 0,
            moveSlot = 0,
            moveId = "cobblemon:$moveId",
            targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
            moveDetails = BattleMoveCandidateView(
                typeId = "water",
                damageCategory = BattleMoveDamageCategory.SPECIAL,
                power = 80.0,
                accuracy = 100.0,
                priority = 0,
                currentPp = 10,
                targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
            ),
        )
        val context = BattleDecisionContext(
            requestId = REQUEST_ID,
            state = BattleStateView(
                battleId = BATTLE_ID,
                format = BattleFormat.SINGLE,
                turn = 1,
                pokemon = listOf(
                    pokemon(ALLY_ID, BattleSide.ALLY, actorItem),
                    pokemon(OPPONENT_ID, BattleSide.OPPONENT, null),
                ),
                field = BattleFieldStateView(
                    weather = weather?.let { BattleTimedEffectView("cobblemon:$it", 3) },
                    terrain = null,
                    roomEffects = emptyList(),
                    globalEffects = emptyList(),
                    sideConditions = BattleSide.entries.associateWith { emptyList() },
                ),
                remainingPokemonBySide = mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1),
                observedEvents = emptyList(),
                inferences = emptyList(),
            ),
            candidates = listOf(candidate),
            deadlineEpochMillis = Long.MAX_VALUE,
        )
        return LocalPublicMechanicsKernel.projectMove(candidate, context).knownDamageMultiplier
    }

    private fun pokemon(id: UUID, side: BattleSide, item: String?) = BattlePokemonStateView(
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
        knownAbilityId = null,
        knownHeldItemId = item,
        fainted = false,
        knownTypeIds = setOf("normal"),
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
        val BATTLE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000cd00")
        val REQUEST_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000cd01")
        val ALLY_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000cd02")
        val OPPONENT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000cd03")
    }
}
