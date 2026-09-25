package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * A mechanic candidate is an attack, not a blank.
 *
 * Every one of the battle tower's 694 sets carries a Mega, Tera or Dynamax option, and every one of
 * those candidates used to project no damage at all - not a rough number, nothing. That is not the
 * same as being unknown: it enters the ranking as an attack that does nothing, so it loses to the
 * plain move every time and the AI can never use the mechanic it was given.
 */
class LocalMechanicCandidateTest {
    @Test
    fun `a mechanic candidate projects damage instead of nothing`() {
        val plain = facts(mechanic = null)
        val tera = facts(mechanic = mechanic("tera", types = setOf("water")))
        assertTrue((plain?.standardDamageFractionRange?.maximum ?: 0.0) > 0.0, "The plain move works.")
        assertTrue(
            (tera?.standardDamageFractionRange?.maximum ?: 0.0) > 0.0,
            "So must the mechanic version, or the AI can never choose it.",
        )
    }

    @Test
    fun `terastallising into the move's type doubles the same-type bonus`() {
        // Water attacker, Water move: already same-type. Terastallising into Water as well is the case
        // the rules make 2.0x rather than another 1.5x.
        val ordinary = facts(mechanic = null)?.baseSameTypeAttackBonus
        val teraSameType = facts(mechanic = mechanic("tera", types = setOf("water")))?.baseSameTypeAttackBonus
        val teraOtherType = facts(mechanic = mechanic("tera", types = setOf("fire")))?.baseSameTypeAttackBonus
        assertEquals(1.5, ordinary, "Water move from a Water attacker.")
        assertEquals(2.0, teraSameType, "Original type and Tera type both match.")
        assertEquals(
            1.5, teraOtherType,
            "Terastallising elsewhere keeps the bonus the original type already gave.",
        )
    }

    @Test
    fun `an already terastallized actor keeps base stab and distinguishes matching tera stab`() {
        assertEquals(
            1.5,
            facts(
                mechanic = null,
                actorTypes = setOf("fire"),
                baseStabTypes = setOf("water"),
                activeTeraType = "fire",
                moveType = "water",
            )?.baseSameTypeAttackBonus,
        )
        assertEquals(
            1.5,
            facts(
                mechanic = null,
                actorTypes = setOf("fire"),
                baseStabTypes = setOf("water"),
                activeTeraType = "fire",
                moveType = "fire",
            )?.baseSameTypeAttackBonus,
        )
        assertEquals(
            2.0,
            facts(
                mechanic = null,
                actorTypes = setOf("fire"),
                baseStabTypes = setOf("fire"),
                activeTeraType = "fire",
                moveType = "fire",
            )?.baseSameTypeAttackBonus,
        )
    }

    @Test
    fun `Stellar uses its first type boost once and then returns to ordinary stab`() {
        assertEquals(
            2.0,
            facts(
                mechanic = mechanic("tera", types = setOf("stellar")),
                baseStabTypes = setOf("water"),
                moveType = "water",
            )?.baseSameTypeAttackBonus,
            "The move that activates Stellar still receives the unused base-type boost.",
        )
        assertEquals(
            2.0,
            facts(
                mechanic = null,
                actorTypes = setOf("water"),
                baseStabTypes = setOf("water"),
                activeTeraType = "stellar",
                stellarBoostedTypes = emptySet(),
                moveType = "water",
            )?.baseSameTypeAttackBonus,
        )
        assertEquals(
            1.5,
            facts(
                mechanic = null,
                actorTypes = setOf("water"),
                baseStabTypes = setOf("water"),
                activeTeraType = "stellar",
                stellarBoostedTypes = setOf("water"),
                moveType = "water",
            )?.baseSameTypeAttackBonus,
        )
        assertEquals(
            4915.0 / 4096.0,
            facts(
                mechanic = null,
                actorTypes = setOf("water"),
                baseStabTypes = setOf("water"),
                activeTeraType = "stellar",
                stellarBoostedTypes = emptySet(),
                moveType = "fire",
            )?.baseSameTypeAttackBonus,
        )
        assertEquals(
            1.0,
            facts(
                mechanic = null,
                actorTypes = setOf("water"),
                baseStabTypes = setOf("water"),
                activeTeraType = "stellar",
                stellarBoostedTypes = setOf("fire"),
                moveType = "fire",
            )?.baseSameTypeAttackBonus,
        )
    }

    @Test
    fun `unknown Stellar consumption uses the public lower bound instead of inventing a fresh boost`() {
        assertEquals(
            1.5,
            facts(
                mechanic = null,
                actorTypes = setOf("water"),
                baseStabTypes = setOf("water"),
                activeTeraType = "stellar",
                stellarBoostedTypes = null,
                moveType = "water",
            )?.baseSameTypeAttackBonus,
        )
        assertEquals(
            1.0,
            facts(
                mechanic = null,
                actorTypes = setOf("water"),
                baseStabTypes = setOf("water"),
                activeTeraType = "stellar",
                stellarBoostedTypes = null,
                moveType = "fire",
            )?.baseSameTypeAttackBonus,
        )
    }

    @Test
    fun `dynamax uses the doubled health it is given`() {
        val doubled = BattleCombatStatRangesView.exact(320, 130, 100, 110, 100, 100)
        val facts = facts(mechanic = mechanic("dynamax", stats = doubled))
        assertTrue(
            (facts?.standardDamageFractionRange?.maximum ?: 0.0) > 0.0,
            "A Dynamax candidate still projects the damage its Max move deals.",
        )
    }

    private fun mechanic(
        id: String,
        types: Set<String> = emptySet(),
        stats: BattleCombatStatRangesView? = null,
    ) = BattleMechanicCandidate(
        mechanicId = id, target = null, publicCost = null,
        transformedActorTypeIds = types, transformedActorCombatStats = stats,
    )

    private fun facts(
        mechanic: BattleMechanicCandidate?,
        actorTypes: Set<String> = setOf("water"),
        baseStabTypes: Set<String> = actorTypes,
        activeTeraType: String? = null,
        stellarBoostedTypes: Set<String>? = null,
        moveType: String = "water",
    ): BattleCandidateFactsView? {
        val ally = mon(BattleSide.ALLY, actorTypes, baseStabTypes, activeTeraType, stellarBoostedTypes)
        val opponent = mon(BattleSide.OPPONENT, setOf("normal"))
        val move = BattleActionCandidate(
            actionId = "surf", kind = BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
            moveId = "cobblemon:surf", targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
            mechanic = mechanic,
            moveDetails = BattleMoveCandidateView(
                typeId = moveType, damageCategory = BattleMoveDamageCategory.SPECIAL, power = 90.0,
                accuracy = 100.0, priority = 0, currentPp = 10,
                targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
            ),
        )
        val context = BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = BattleStateView(
                battleId = UUID.randomUUID(), format = BattleFormat.SINGLE, turn = 2,
                pokemon = listOf(ally, opponent), field = BattleFieldStateView.empty(),
                remainingPokemonBySide = BattleSide.entries.associateWith { 2 },
                observedEvents = emptyList(), inferences = emptyList(),
            ),
            candidates = listOf(move), deadlineEpochMillis = Long.MAX_VALUE,
            memory = BattleTacticalMemoryView.empty(),
            publicActionCatalog = BattlePublicActionCatalogView(emptyList()),
        )
        return PublicBattleTacticalCalculator.calculate(context).candidates.single().facts
    }

    private fun mon(
        side: BattleSide,
        types: Set<String>,
        baseStabTypes: Set<String> = types,
        activeTeraType: String? = null,
        stellarBoostedTypes: Set<String>? = null,
    ) = BattlePokemonStateView(
        battlePokemonId = UUID.randomUUID(), side = side, activeSlot = 0,
        speciesId = "cobblemon:probe", formId = null, level = 50, hpFraction = 1.0,
        statusId = null, statStages = emptyMap(), knownMoveIds = emptySet(),
        knownAbilityId = null, knownHeldItemId = null, fainted = false, knownTypeIds = types,
        combatStats = if (side == BattleSide.ALLY) {
            BattleCombatStatRangesView.exact(160, 130, 100, 130, 100, 100)
        } else {
            BattleCombatStatRangesView(
                BattleIntegerRange(150, 190), BattleIntegerRange(90, 150), BattleIntegerRange(80, 135),
                BattleIntegerRange(90, 150), BattleIntegerRange(80, 135), BattleIntegerRange(80, 130),
                BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
            )
        },
        knownVolatileEffectIds = emptySet(),
        knownBaseStabTypeIds = baseStabTypes,
        knownTeraTypeId = activeTeraType,
        knownStellarBoostedTypeIds = stellarBoostedTypes,
    )
}
