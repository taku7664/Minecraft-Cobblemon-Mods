package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicTurnOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Held items reach the damage and the turn order.
 *
 * Only the Utility Umbrella ever did, through the weather, while the battle tower hands items out on
 * nearly every set. A Choice Band is half again the attack the damage is computed from and a Choice
 * Scarf is half again the Speed the order is decided by - and the AI holds them itself, where the
 * item is never hidden, so it was wrong about its own numbers before it was wrong about anyone's.
 */
class LocalHeldItemMechanicsTest {
    @Test
    fun `a choice band raises the physical damage it is computed from`() {
        val plain = damage(item = null)
        val banded = damage(item = "cobblemon:choice_band")
        assertTrue(banded > plain * 1.3, "Half again the attack has to show up as materially more damage. $plain -> $banded")
    }

    @Test
    fun `a life orb raises damage and an expert belt only raises it against a weakness`() {
        val plain = damage(item = null)
        assertTrue(damage(item = "cobblemon:life_orb") > plain, "Life Orb applies unconditionally.")
        assertEquals(
            plain, damage(item = "cobblemon:expert_belt"), 1e-9,
            "An Expert Belt does nothing at neutral effectiveness.",
        )
        assertTrue(
            damage(item = "cobblemon:expert_belt", defenderTypes = setOf("rock")) >
                damage(item = null, defenderTypes = setOf("rock")),
            "Against a weakness it does.",
        )
    }

    @Test
    fun `a choice scarf changes who is expected to act first`() {
        val plain = actsFirst(item = null)
        val scarfed = actsFirst(item = "cobblemon:choice_scarf")
        assertTrue(
            scarfed > plain,
            "Half again the Speed has to move the order. $plain -> $scarfed",
        )
    }

    private fun damage(item: String?, defenderTypes: Set<String> = setOf("normal")): Double {
        val ally = mon(BattleSide.ALLY, item, 1.0, setOf("water"))
        val opponent = mon(BattleSide.OPPONENT, null, 1.0, defenderTypes)
        val move = attack("waterpulse", "water")
        val facts = PublicBattleTacticalCalculator
            .calculate(context(listOf(move), ally, opponent))
            .candidates.single().facts
        return facts?.standardDamageFractionRange?.let { (it.minimum + it.maximum) / 2.0 } ?: 0.0
    }

    private fun actsFirst(item: String?): Double {
        val ally = mon(BattleSide.ALLY, item, 1.0, setOf("water"))
        val opponent = mon(BattleSide.OPPONENT, null, 1.0, setOf("normal"))
        val state = context(listOf(attack("waterpulse", "water")), ally, opponent).state
        return LocalPublicTurnOrder.actsFirstProbability(
            state = state, actorSide = BattleSide.ALLY, actorSlot = 0,
            actorPriority = 0, opponentPriority = 0,
        ) ?: 0.0
    }

    private fun attack(id: String, type: String) = BattleActionCandidate(
        actionId = id, kind = BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
        moveId = "cobblemon:$id", targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
        moveDetails = BattleMoveCandidateView(
            typeId = type, damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 80.0,
            accuracy = 100.0, priority = 0, currentPp = 10,
            targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
        ),
    )

    private fun context(
        candidates: List<BattleActionCandidate>,
        ally: BattlePokemonStateView,
        opponent: BattlePokemonStateView,
    ) = BattleDecisionContext(
        requestId = UUID.randomUUID(),
        state = BattleStateView(
            battleId = UUID.randomUUID(), format = BattleFormat.SINGLE, turn = 2,
            pokemon = listOf(ally, opponent), field = BattleFieldStateView.empty(),
            remainingPokemonBySide = BattleSide.entries.associateWith { 2 },
            observedEvents = emptyList(), inferences = emptyList(),
        ),
        candidates = candidates, deadlineEpochMillis = Long.MAX_VALUE,
        memory = BattleTacticalMemoryView.empty(),
        publicActionCatalog = BattlePublicActionCatalogView(emptyList()),
    )

    private fun mon(side: BattleSide, item: String?, hpFraction: Double, types: Set<String>) =
        BattlePokemonStateView(
            battlePokemonId = UUID.randomUUID(), side = side, activeSlot = 0,
            speciesId = "cobblemon:probe", formId = null, level = 50, hpFraction = hpFraction,
            statusId = null, statStages = emptyMap(), knownMoveIds = emptySet(),
            knownAbilityId = null, knownHeldItemId = item, fainted = false, knownTypeIds = types,
            combatStats = if (side == BattleSide.ALLY) {
                BattleCombatStatRangesView.exact(160, 130, 100, 110, 100, 100)
            } else {
                BattleCombatStatRangesView(
                    BattleIntegerRange(150, 190), BattleIntegerRange(90, 150), BattleIntegerRange(80, 135),
                    BattleIntegerRange(90, 150), BattleIntegerRange(80, 135), BattleIntegerRange(80, 130),
                    BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
                )
            },
        )
}
