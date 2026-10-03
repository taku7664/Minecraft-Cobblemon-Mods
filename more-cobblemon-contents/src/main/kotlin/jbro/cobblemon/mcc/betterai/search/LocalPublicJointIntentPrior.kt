package jbro.cobblemon.mcc.betterai.search

import jbro.cobblemon.mcc.betterai.matchup.IntentKind
import jbro.cobblemon.mcc.betterai.matchup.OpponentIntent
import jbro.cobblemon.mcc.internal.ai.*
import java.util.UUID

/** Conditions only the focus/split share; unrelated responses retain their independent probability. */
internal object LocalPublicJointIntentPrior {
    fun condition(
        actions: List<BattleActionCandidate>,
        intents: List<OpponentIntent>,
        state: BattleStateView,
        prior: List<Double>,
    ): List<Double> {
        if (state.format != BattleFormat.DOUBLE) return prior
        val actors = state.pokemon.filter { it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted }
        val targets = state.pokemon.filter { it.side == BattleSide.ALLY && it.activeSlot != null && !it.fainted }
        if (actors.size != 2 || targets.size != 2) return prior
        // Public events have no historic stage/volatile snapshots to match to this current condition.
        if ((actors + targets).any { it.statStages.values.any { stage -> stage != 0 } ||
                it.knownVolatileEffectIds.isNotEmpty() }) return prior
        val bySlot = actors.associate { actor ->
            val slot = requireNotNull(actor.activeSlot)
            val intent = intents.singleOrNull { it.activeSlot == slot && it.pokemonId == actor.battlePokemonId }
                ?: return prior
            slot to intent
        }
        val targetIds = targets.mapTo(HashSet()) { it.battlePokemonId }
        val patterns = actions.map { action -> pattern(action, bySlot, targetIds, state) }
        val focusMass = prior.indices.filter { patterns[it] == true }.sumOf { prior[it] }
        val splitMass = prior.indices.filter { patterns[it] == false }.sumOf { prior[it] }
        if (focusMass <= 0.0 || splitMass <= 0.0) return prior

        val relevantIds = actors.mapTo(HashSet()) { it.battlePokemonId }.apply { addAll(targetIds) }
        val boundary = state.observedEvents.lastOrNull { event ->
            event.kind in TEAM_CHANGE_EVENTS || event.kind == BattleObservedEventKind.FIELD_EFFECT_CHANGED ||
                event.kind in CONDITION_CHANGE_EVENTS &&
                (event.actorPokemonId in relevantIds || event.targetPokemonIds.any(relevantIds::contains))
        }?.turn ?: -1
        val byActor = bySlot.values.associateBy { it.pokemonId }
        val pairs = state.observedEvents.filter { event ->
            event.turn > boundary && event.turn < state.turn && event.kind == BattleObservedEventKind.MOVE_USED &&
                event.actorPokemonId in byActor
        }.groupBy { it.turn }.values.mapNotNull { events ->
            // A truncated queue prefix, a skipped action or a repeated actor is not a completed pair.
            if (events.size != 2 || events.map { it.actorPokemonId }.toSet() != byActor.keys) return@mapNotNull null
            val aimed = events.map { event ->
                val target = event.targetPokemonIds.singleOrNull()?.takeIf(targetIds::contains)
                    ?: return@mapNotNull null
                val intent = byActor[event.actorPokemonId] ?: return@mapNotNull null
                val move = event.publicValueId?.let(PublicIds::canonical) ?: return@mapNotNull null
                if (intent.options.none { it.kind == IntentKind.ATTACK && it.moveId?.let(PublicIds::canonical) == move &&
                        it.targetId == target }) return@mapNotNull null
                target
            }
            aimed[0] == aimed[1]
        }
        if (pairs.size < MINIMUM_COMPLETE_PAIRS) return prior
        val attackMass = focusMass + splitMass
        // One prior sample keeps an unseen pattern possible without inventing observations.
        val posteriorFocus = (pairs.count { it } + focusMass / attackMass) / (pairs.size + 1.0)
        val posteriorSplit = (pairs.count { !it } + splitMass / attackMass) / (pairs.size + 1.0)
        return prior.mapIndexed { index, probability -> when (patterns[index]) {
            true -> probability * attackMass * posteriorFocus / focusMass
            false -> probability * attackMass * posteriorSplit / splitMass
            null -> probability
        } }
    }

    private fun pattern(
        action: BattleActionCandidate,
        bySlot: Map<Int, OpponentIntent>,
        targetIds: Set<UUID>,
        state: BattleStateView,
    ): Boolean? {
        if (action.kind != BattleActionKind.COMPOSITE) return null
        val parts = action.componentActions.filter { it.kind != BattleActionKind.WAIT }
        if (parts.size != 2 || parts.map { it.actorSlot }.toSet() != bySlot.keys) return null
        val targets = parts.map { part ->
            if (part.kind != BattleActionKind.USE_MOVE) return null
            val targetSlot = part.targets.singleOrNull() ?: return null
            val target = state.pokemon.singleOrNull {
                it.side == targetSlot.side && it.activeSlot == targetSlot.slot && it.battlePokemonId in targetIds
            }?.battlePokemonId ?: return null
            val intent = bySlot[part.actorSlot] ?: return null
            val move = part.moveId?.let(PublicIds::canonical) ?: return null
            if (intent.options.none { it.kind == IntentKind.ATTACK && it.moveId?.let(PublicIds::canonical) == move &&
                    it.targetId == target }) return null
            target
        }
        return targets[0] == targets[1]
    }

    // A minimum evidence gate, not a measured fit or a claim of improved prediction accuracy.
    private const val MINIMUM_COMPLETE_PAIRS = 3
    private val TEAM_CHANGE_EVENTS = setOf(BattleObservedEventKind.SWITCHED, BattleObservedEventKind.FAINTED)
    private val CONDITION_CHANGE_EVENTS = setOf(
        BattleObservedEventKind.STATUS_CHANGED,
        BattleObservedEventKind.ABILITY_REVEALED,
        BattleObservedEventKind.HELD_ITEM_REVEALED,
        BattleObservedEventKind.TERA_TYPE_REVEALED,
    )
}
