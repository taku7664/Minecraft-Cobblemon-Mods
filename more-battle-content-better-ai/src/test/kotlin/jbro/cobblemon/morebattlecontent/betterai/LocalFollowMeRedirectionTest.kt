package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.outcome.PublicSingleTurnProjector
import jbro.cobblemon.morebattlecontent.betterai.state.RecursiveActionHistory
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Follow Me takes the turn's attack onto itself, and the projector has to see it happen.
 *
 * Redirection by ability is resolved where targets are resolved, because it is true before anyone
 * acts. Follow Me is not: it becomes true partway through the turn, when the move resolves. So it
 * belongs to the projector, which is the only thing that walks a turn in order.
 *
 * Leaving it out did not make Follow Me cheap, it made it free of consequence - the projector showed
 * the partner taking the hit either way, so the search valued the move at nothing and a trainer built
 * around it would never press it. The same shape as a mechanic candidate that projects no damage:
 * not mispriced, priced at zero, so it loses to everything forever.
 */
class LocalFollowMeRedirectionTest {
    @Test
    fun `the partner is not hit when the ally draws the attack`() {
        val plain = partnerHealthAfterTurn(allyUsesFollowMe = false)
        assertTrue(plain < 0.999, "Without the draw the partner takes the hit, was $plain.")

        val drawn = partnerHealthAfterTurn(allyUsesFollowMe = true)
        assertTrue(
            drawn > plain,
            "Follow Me has to move the damage off the partner, or the search has no reason to " +
                "ever press it: drawn=$drawn plain=$plain",
        )
    }

    @Test
    fun `the redirector takes the hit instead`() {
        val plain = redirectorHealthAfterTurn(allyUsesFollowMe = false)
        val drawn = redirectorHealthAfterTurn(allyUsesFollowMe = true)
        assertTrue(
            drawn < plain,
            "The damage is moved, not deleted. A projector that only spared the partner would be " +
                "inventing a free turn: drawn=$drawn plain=$plain",
        )
    }

    /** Health of the slot the opponent actually aimed at. */
    private fun partnerHealthAfterTurn(allyUsesFollowMe: Boolean): Double =
        healthAfterTurn(allyUsesFollowMe, slot = 1)

    /** Health of the slot that draws the attack. */
    private fun redirectorHealthAfterTurn(allyUsesFollowMe: Boolean): Double =
        healthAfterTurn(allyUsesFollowMe, slot = 0)

    private fun healthAfterTurn(allyUsesFollowMe: Boolean, slot: Int): Double {
        val drawer = mon(BattleSide.ALLY, 0)
        val partner = mon(BattleSide.ALLY, 1)
        val attacker = mon(BattleSide.OPPONENT, 0)
        val idle = mon(BattleSide.OPPONENT, 1)
        val state = BattleStateView(
            battleId = UUID.randomUUID(), format = BattleFormat.DOUBLE, turn = 2,
            pokemon = listOf(drawer, partner, attacker, idle),
            field = BattleFieldStateView.empty(),
            remainingPokemonBySide = BattleSide.entries.associateWith { 4 },
            observedEvents = emptyList(), inferences = emptyList(),
        )
        val allyAction = if (allyUsesFollowMe) {
            statusMove("followme", actorSlot = 0)
        } else {
            statusMove("splash", actorSlot = 0)
        }
        // The opponent aims squarely at the partner, which is the whole point: an attack already
        // pointed at the drawer would prove nothing.
        val opponentAction = attack(actorSlot = 0, targetSlot = 1)
        val context = BattleDecisionContext(
            requestId = UUID.randomUUID(), state = state,
            candidates = listOf(allyAction), deadlineEpochMillis = Long.MAX_VALUE,
            memory = BattleTacticalMemoryView.empty(),
            publicActionCatalog = BattlePublicActionCatalogView(emptyList()),
        )
        val projections = PublicSingleTurnProjector.project(
            state, allyAction, opponentAction, context, RecursiveActionHistory(),
        )
        val outcome = projections.maxByOrNull { it.probability } ?: error("The turn produced no projection.")
        val subject = if (slot == 0) drawer else partner
        return outcome.state.pokemon.single { it.battlePokemonId == subject.battlePokemonId }.hpFraction
    }

    private fun statusMove(id: String, actorSlot: Int) = BattleActionCandidate(
        actionId = id, kind = BattleActionKind.USE_MOVE, actorSlot = actorSlot, moveSlot = 0,
        moveId = "cobblemon:$id", targets = emptyList(),
        moveDetails = BattleMoveCandidateView(
            typeId = "normal", damageCategory = BattleMoveDamageCategory.STATUS, power = 0.0,
            accuracy = 100.0, priority = 2, currentPp = 10,
            targetPattern = BattleMoveTargetPattern.SELF,
        ),
    )

    private fun attack(actorSlot: Int, targetSlot: Int) = BattleActionCandidate(
        actionId = "strike", kind = BattleActionKind.USE_MOVE, actorSlot = actorSlot, moveSlot = 0,
        moveId = "cobblemon:strike", targets = listOf(BattleTargetSlot(BattleSide.ALLY, targetSlot)),
        moveDetails = BattleMoveCandidateView(
            typeId = "normal", damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 90.0,
            accuracy = 100.0, priority = 0, currentPp = 10,
            targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
        ),
    )

    private fun mon(side: BattleSide, slot: Int) = BattlePokemonStateView(
        battlePokemonId = UUID.randomUUID(), side = side, activeSlot = slot,
        speciesId = "cobblemon:probe_${side.name.lowercase()}_$slot", formId = null, level = 50,
        hpFraction = 1.0, statusId = null, statStages = emptyMap(), knownMoveIds = emptySet(),
        knownAbilityId = null, knownHeldItemId = null, fainted = false, knownTypeIds = setOf("normal"),
        combatStats = if (side == BattleSide.ALLY) {
            BattleCombatStatRangesView.exact(200, 100, 100, 100, 100, 100)
        } else {
            publicExactStats(200, 140, 100, 100, 100, 120)
        },
    )
}
