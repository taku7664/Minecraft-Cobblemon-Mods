package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.state.PublicTurnProjection

/** Public event ledger used by the randomized scenario harness between simulated turns. */
internal class LocalScenarioPublicEvidence {
    private val events = mutableListOf<BattleObservedEventView>()
    private var nextSequence = 1L

    fun recordTurn(
        turn: Int,
        before: BattleStateView,
        after: BattleStateView,
        allyAction: BattleActionCandidate,
        opponentAction: BattleActionCandidate,
        outcome: PublicTurnProjection,
    ) {
        val movesByActor = moveActions(before, BattleSide.ALLY, allyAction)
            .plus(moveActions(before, BattleSide.OPPONENT, opponentAction))
            .associateBy(ActionWithActor::actorPokemonId)
        outcome.actionOrderPokemonIds.forEach { actorId ->
            val moveId = outcome.executedMoveIdsByPokemon[actorId] ?: return@forEach
            val selected = movesByActor[actorId] ?: return@forEach
            append(
                turn = turn,
                kind = BattleObservedEventKind.ACTION_ORDER,
                actorPokemonId = actorId,
                publicValueId = moveId,
                baseMovePriority = selected.action.moveDetails?.priority ?: 0,
                actorSlot = selected.action.actorSlot,
            )
            append(
                turn = turn,
                kind = BattleObservedEventKind.MOVE_USED,
                actorPokemonId = actorId,
                targetPokemonIds = selected.action.targets.mapNotNull { target ->
                    after.pokemon.firstOrNull { it.side == target.side && it.activeSlot == target.slot }
                        ?.battlePokemonId
                }.distinct(),
                publicValueId = moveId,
                actorSlot = selected.action.actorSlot,
            )
        }

        recordActiveChanges(turn, before, after)
        before.pokemon.forEach { previous ->
            val current = after.pokemon.single { it.battlePokemonId == previous.battlePokemonId }
            val hpDelta = current.hpFraction - previous.hpFraction
            if (kotlin.math.abs(hpDelta) > HP_EPSILON) {
                append(
                    turn = turn,
                    kind = BattleObservedEventKind.HP_CHANGED,
                    actorPokemonId = current.battlePokemonId,
                    hpFractionDelta = hpDelta.coerceIn(-1.0, 1.0),
                    actorSlot = previous.activeSlot ?: current.activeSlot,
                )
            }
            if (current.statusId != previous.statusId) {
                append(
                    turn = turn,
                    kind = BattleObservedEventKind.STATUS_CHANGED,
                    actorPokemonId = current.battlePokemonId,
                    publicValueId = current.statusId,
                    actorSlot = previous.activeSlot ?: current.activeSlot,
                )
            }
            if (!previous.fainted && current.fainted) {
                append(
                    turn = turn,
                    kind = BattleObservedEventKind.FAINTED,
                    actorPokemonId = current.battlePokemonId,
                    actorSlot = previous.activeSlot,
                )
            }
        }
    }

    fun recordReplacement(
        turn: Int,
        incomingPokemonId: UUID,
        actorSlot: Int,
    ) {
        append(
            turn = turn,
            kind = BattleObservedEventKind.SWITCHED,
            actorPokemonId = incomingPokemonId,
            actorSlot = actorSlot,
        )
    }

    fun events(): List<BattleObservedEventView> = events.toList()

    fun counts(): Map<BattleObservedEventKind, Int> = events.groupingBy(BattleObservedEventView::kind).eachCount()

    fun inferences(pokemon: List<BattlePokemonStateView>, viewer: BattleSide): List<BattleInferenceView> {
        val canonicalSides = pokemon.associate { it.battlePokemonId to it.side }
        val evidenceByRelation = linkedMapOf<ActionOrderRelation, LinkedHashSet<Long>>()
        events.asSequence()
            .filter { it.kind == BattleObservedEventKind.ACTION_ORDER }
            .sortedBy(BattleObservedEventView::sequence)
            .groupBy(BattleObservedEventView::turn)
            .toSortedMap()
            .values
            .forEach { actions ->
                if (actions.groupingBy(BattleObservedEventView::actorPokemonId).eachCount().any { it.value > 1 }) {
                    return@forEach
                }
                val opponentActions = actions.filter { canonicalSides[it.actorPokemonId] != viewer }
                val allyActions = actions.filter { canonicalSides[it.actorPokemonId] == viewer }
                opponentActions.forEach { opponent ->
                    allyActions.filter { it.baseMovePriority == opponent.baseMovePriority }.forEach { ally ->
                        val relation = ActionOrderRelation(
                            subjectPokemonId = requireNotNull(opponent.actorPokemonId),
                            relatedPokemonId = requireNotNull(ally.actorPokemonId),
                            candidateId = if (opponent.sequence < ally.sequence) {
                                BEFORE_AT_SAME_BASE_PRIORITY
                            } else {
                                AFTER_AT_SAME_BASE_PRIORITY
                            },
                        )
                        evidenceByRelation.getOrPut(relation, ::linkedSetOf).apply {
                            add(opponent.sequence)
                            add(ally.sequence)
                        }
                    }
                }
            }
        return evidenceByRelation.map { (relation, evidence) ->
            BattleInferenceView(
                subjectPokemonId = relation.subjectPokemonId,
                categoryId = OBSERVED_ACTION_ORDER,
                candidateId = relation.candidateId,
                confidence = BattleInferenceConfidence.CONFIRMED,
                basis = setOf(BattleInferenceBasis.ACTION_ORDER),
                evidenceEventSequences = evidence.sorted(),
                relatedPokemonId = relation.relatedPokemonId,
            )
        }
    }

    private fun recordActiveChanges(turn: Int, before: BattleStateView, after: BattleStateView) {
        BattleSide.entries.forEach { side ->
            val previousBySlot = before.pokemon.filter { it.side == side && it.activeSlot != null }
                .associateBy { requireNotNull(it.activeSlot) }
            after.pokemon.filter { it.side == side && it.activeSlot != null }.forEach { incoming ->
                val slot = requireNotNull(incoming.activeSlot)
                if (previousBySlot[slot]?.battlePokemonId != incoming.battlePokemonId) {
                    recordReplacement(turn, incoming.battlePokemonId, slot)
                }
            }
        }
    }

    private fun moveActions(
        state: BattleStateView,
        side: BattleSide,
        submitted: BattleActionCandidate,
    ): List<ActionWithActor> {
        val actions = if (submitted.kind == BattleActionKind.COMPOSITE) submitted.componentActions else listOf(submitted)
        return actions.filter { it.kind == BattleActionKind.USE_MOVE }.mapNotNull { action ->
            val slot = action.actorSlot ?: return@mapNotNull null
            val actor = state.pokemon.firstOrNull {
                it.side == side && it.activeSlot == slot && !it.fainted && it.hpFraction > 0.0
            } ?: return@mapNotNull null
            ActionWithActor(actor.battlePokemonId, action)
        }
    }

    private fun append(
        turn: Int,
        kind: BattleObservedEventKind,
        actorPokemonId: UUID?,
        targetPokemonIds: List<UUID> = emptyList(),
        publicValueId: String? = null,
        hpFractionDelta: Double? = null,
        baseMovePriority: Int? = null,
        actorSlot: Int? = null,
    ) {
        events += BattleObservedEventView(
            sequence = nextSequence++,
            turn = turn,
            kind = kind,
            actorPokemonId = actorPokemonId,
            targetPokemonIds = targetPokemonIds,
            publicValueId = publicValueId,
            hpFractionDelta = hpFractionDelta,
            baseMovePriority = baseMovePriority,
            actorSlot = actorSlot,
        )
    }

    private data class ActionWithActor(
        val actorPokemonId: UUID,
        val action: BattleActionCandidate,
    )

    private data class ActionOrderRelation(
        val subjectPokemonId: UUID,
        val relatedPokemonId: UUID,
        val candidateId: String,
    )

    private companion object {
        const val HP_EPSILON = 1e-9
        const val OBSERVED_ACTION_ORDER = "observed_action_order"
        const val BEFORE_AT_SAME_BASE_PRIORITY = "BEFORE_AT_SAME_BASE_PRIORITY"
        const val AFTER_AT_SAME_BASE_PRIORITY = "AFTER_AT_SAME_BASE_PRIORITY"
    }
}
