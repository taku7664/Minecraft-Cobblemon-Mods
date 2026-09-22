package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalAcrobaticsPowerProjectionTest {
    @Test
    fun `acrobatics doubles only when the held-item slot is empty`() {
        assertEquals(
            damage(state(), fixedPower(110)),
            damage(state(), acrobatics()),
        )
        assertEquals(
            damage(state(allyItem = "leftovers"), fixedPower(55)),
            damage(state(allyItem = "leftovers"), acrobatics()),
        )
    }

    @Test
    fun `klutz and magic room do not make an existing item absent for acrobatics`() {
        for (heldItemState in listOf(
            state(allyAbility = "klutz", allyItem = "leftovers"),
            state(allyItem = "leftovers", magicRoom = true),
        )) {
            assertEquals(
                damage(heldItemState, fixedPower(55)),
                damage(heldItemState, acrobatics()),
            )
        }
    }

    private fun damage(state: BattleStateView, action: BattleActionCandidate): BattleDamageFractionRange {
        val calculated = PublicBattleTacticalCalculator.calculate(
            BattleDecisionContext(REQUEST_ID, state, listOf(action), Long.MAX_VALUE),
        ).candidates.single()
        return requireNotNull(calculated.facts?.standardDamageFractionRange) {
            "missing projected damage for ${action.moveId}"
        }
    }

    private fun acrobatics() = move("acrobatics", 55.0, dynamic = true)

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
            damageCategory = BattleMoveDamageCategory.PHYSICAL,
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
        allyAbility: String? = null,
        allyItem: String? = null,
        magicRoom: Boolean = false,
    ) = BattleStateView(
        battleId = BATTLE_ID,
        format = BattleFormat.SINGLE,
        turn = 1,
        pokemon = listOf(
            pokemon(ALLY_ID, BattleSide.ALLY, allyAbility, allyItem),
            pokemon(OPPONENT_ID, BattleSide.OPPONENT),
        ),
        field = BattleFieldStateView(
            weather = null,
            terrain = null,
            roomEffects = if (magicRoom) {
                listOf(BattleTimedEffectView("cobblemon:magic_room", 3))
            } else {
                emptyList()
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
            speed = BattleIntegerRange(100, 100),
            knowledge = if (side == BattleSide.ALLY) {
                BattleCombatStatKnowledge.EXACT_OWN
            } else {
                BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE
            },
        ),
    )

    private companion object {
        val BATTLE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000cb00")
        val REQUEST_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000cb01")
        val ALLY_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000cb02")
        val OPPONENT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000cb03")
    }
}
