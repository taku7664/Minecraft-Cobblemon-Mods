package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicMechanicsKernel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalTerrainDamageProjectionTest {
    @Test
    fun `terrain applies its public type boosts only to grounded attackers`() {
        for ((terrain, type) in listOf(
            "electricterrain" to "electric",
            "grassyterrain" to "grass",
            "psychicterrain" to "psychic",
        )) {
            val action = move("ordinary_$type", power = 80, type = type)
            assertEquals(1.0, multiplier(state(), action), terrain)
            assertEquals(5325.0 / 4096.0, multiplier(state(terrain = terrain), action), terrain)
            assertEquals(
                1.0,
                multiplier(state(terrain = terrain, allyTypes = setOf("flying")), action),
                terrain,
            )
        }
    }

    @Test
    fun `misty and grassy terrain apply their public grounded-target reductions`() {
        val dragon = move("dragonpulse", power = 85, type = "dragon")
        assertEquals(1.0, multiplier(state(), dragon))
        assertEquals(0.5, multiplier(state(terrain = "mistyterrain"), dragon))
        assertEquals(
            1.0,
            multiplier(state(terrain = "mistyterrain", opponentTypes = setOf("flying")), dragon),
        )

        val earthquake = move("earthquake", power = 100, type = "ground")
        assertEquals(1.0, multiplier(state(), earthquake))
        assertEquals(0.5, multiplier(state(terrain = "grassyterrain"), earthquake))
    }

    @Test
    fun `semi invulnerable battlers do not receive terrain power modifiers that explicitly exclude them`() {
        val electric = move("thunderbolt", power = 90, type = "electric")
        assertEquals(
            1.0,
            multiplier(state(terrain = "electricterrain", allyVolatiles = setOf("dig")), electric),
        )

        val dragon = move("dragonpulse", power = 85, type = "dragon")
        assertEquals(
            1.0,
            multiplier(state(terrain = "mistyterrain", opponentVolatiles = setOf("dig")), dragon),
        )

        val earthquake = move("earthquake", power = 100, type = "ground")
        assertEquals(
            1.0,
            multiplier(state(terrain = "grassyterrain", opponentVolatiles = setOf("dig")), earthquake),
        )
    }

    @Test
    fun `expanding force uses the actor grounding gate for its extra power`() {
        assertEquals(
            damage(state(), move("fixed_80", power = 80, type = "psychic")),
            damage(state(), move("expandingforce", power = 80, type = "psychic", dynamic = true)),
        )
        val terrain = state(terrain = "psychicterrain")
        assertEquals(
            damage(terrain, move("fixed_120", power = 120, type = "psychic")),
            damage(terrain, move("expandingforce", power = 80, type = "psychic", dynamic = true)),
        )
        val airborne = state(terrain = "psychicterrain", allyTypes = setOf("flying"))
        assertEquals(
            damage(airborne, move("fixed_80", power = 80, type = "psychic")),
            damage(airborne, move("expandingforce", power = 80, type = "psychic", dynamic = true)),
        )
    }

    @Test
    fun `rising voltage uses the target grounding gate for its extra power`() {
        val grounded = state(terrain = "electricterrain")
        assertEquals(
            damage(grounded, move("fixed_140", power = 140, type = "electric")),
            damage(grounded, move("risingvoltage", power = 70, type = "electric", dynamic = true)),
        )
        val airborneTarget = state(terrain = "electricterrain", opponentTypes = setOf("flying"))
        assertEquals(
            damage(airborneTarget, move("fixed_70", power = 70, type = "electric")),
            damage(airborneTarget, move("risingvoltage", power = 70, type = "electric", dynamic = true)),
        )
    }

    @Test
    fun `misty explosion gains power only for a grounded actor in misty terrain`() {
        val ordinary = state()
        assertEquals(
            damage(ordinary, move("fixed_100", power = 100, type = "fairy")),
            damage(ordinary, move("mistyexplosion", power = 100, type = "fairy", dynamic = true)),
        )

        val grounded = state(terrain = "mistyterrain")
        assertEquals(
            damage(grounded, move("fixed_150", power = 150, type = "fairy")),
            damage(grounded, move("mistyexplosion", power = 100, type = "fairy", dynamic = true)),
        )

        val airborne = state(terrain = "mistyterrain", allyTypes = setOf("flying"))
        assertEquals(
            damage(airborne, move("fixed_100", power = 100, type = "fairy")),
            damage(airborne, move("mistyexplosion", power = 100, type = "fairy", dynamic = true)),
        )
    }

    @Test
    fun `psyblade gains power in electric terrain even when the actor is airborne`() {
        val ordinary = state()
        assertEquals(
            damage(ordinary, move("fixed_80", power = 80, type = "psychic", category = BattleMoveDamageCategory.PHYSICAL)),
            damage(ordinary, move("psyblade", power = 80, type = "psychic", dynamic = true, category = BattleMoveDamageCategory.PHYSICAL)),
        )

        val airborne = state(terrain = "electricterrain", allyTypes = setOf("flying"))
        assertEquals(
            damage(airborne, move("fixed_120", power = 120, type = "psychic", category = BattleMoveDamageCategory.PHYSICAL)),
            damage(airborne, move("psyblade", power = 80, type = "psychic", dynamic = true, category = BattleMoveDamageCategory.PHYSICAL)),
        )
    }

    private fun damage(state: BattleStateView, action: BattleActionCandidate): BattleDamageFractionRange {
        val calculated = PublicBattleTacticalCalculator.calculate(
            BattleDecisionContext(REQUEST_ID, state, listOf(action), Long.MAX_VALUE),
        ).candidates.single()
        return requireNotNull(calculated.facts?.standardDamageFractionRange) {
            "missing projected damage for ${action.moveId}"
        }
    }

    private fun multiplier(state: BattleStateView, action: BattleActionCandidate): Double {
        val context = BattleDecisionContext(REQUEST_ID, state, listOf(action), Long.MAX_VALUE)
        return LocalPublicMechanicsKernel.projectMove(action, context).knownDamageMultiplier
    }

    private fun move(
        id: String,
        power: Int,
        type: String,
        dynamic: Boolean = false,
        category: BattleMoveDamageCategory = BattleMoveDamageCategory.SPECIAL,
    ) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = 0,
        moveSlot = 0,
        moveId = "cobblemon:$id",
        targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
        moveDetails = BattleMoveCandidateView(
            typeId = type,
            damageCategory = category,
            power = power.toDouble(),
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
        terrain: String? = null,
        allyTypes: Set<String> = setOf("normal"),
        opponentTypes: Set<String> = setOf("normal"),
        allyVolatiles: Set<String> = emptySet(),
        opponentVolatiles: Set<String> = emptySet(),
    ) = BattleStateView(
        battleId = BATTLE_ID,
        format = BattleFormat.SINGLE,
        turn = 1,
        pokemon = listOf(
            pokemon(ALLY_ID, BattleSide.ALLY, allyTypes, allyVolatiles),
            pokemon(OPPONENT_ID, BattleSide.OPPONENT, opponentTypes, opponentVolatiles),
        ),
        field = BattleFieldStateView(
            weather = null,
            terrain = terrain?.let { BattleTimedEffectView("cobblemon:$it", 3) },
            roomEffects = emptyList(),
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
        types: Set<String>,
        volatiles: Set<String>,
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
        knownAbilityId = null,
        knownHeldItemId = null,
        fainted = false,
        knownTypeIds = types,
        knownVolatileEffectIds = volatiles,
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
        val BATTLE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000cc00")
        val REQUEST_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000cc01")
        val ALLY_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000cc02")
        val OPPONENT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000cc03")
    }
}
