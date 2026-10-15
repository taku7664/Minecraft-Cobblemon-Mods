package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.betterai.engine.Js
import jbro.cobblemon.mcc.betterai.engine.sim.BattleActions
import jbro.cobblemon.mcc.betterai.simulation.EngineRuntimeDex
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleShowdownMoveEffects
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * A Dynamaxed Pokemon as the legacy calculation reads it: the `dynamax` volatile Showdown starts and ends publicly, and
 * a `dynamaxturns:N` count of the turns it has left (this one included), set at the start and lowered after each turn
 * ([LocalPersistentMoveState.afterResidual], the observer's `advance`).
 *
 * Dynamax doubles maximum and current HP and keeps the share when it ends (Showdown's `getUndynamaxedHP`), so in the
 * HP-share units every calculation uses, a Dynamaxed Pokemon takes half of each hit, for as long as it lasts.
 */
internal object LocalDynamax {
    const val VOLATILE = "dynamax"
    const val TURNS = "dynamaxturns:"
    /** Turns a Dynamax lasts, the one it starts on included. */
    const val DURATION = 3
    /** The share of a hit's HP fraction a Dynamaxed Pokemon takes: its maximum HP is doubled. */
    const val DAMAGE_SHARE = 0.5

    fun active(pokemon: BattlePokemonStateView?): Boolean =
        pokemon?.knownVolatileEffectIds?.any { PublicIds.canonical(it) == VOLATILE } == true

    /** [DAMAGE_SHARE] while [pokemon] is Dynamaxed, otherwise 1. */
    fun damageShare(pokemon: BattlePokemonStateView?): Double = if (active(pokemon)) DAMAGE_SHARE else 1.0

    /** [pokemon] Dynamaxed from now, for [DURATION] turns; unchanged when it already is. */
    fun start(pokemon: BattlePokemonStateView): BattlePokemonStateView =
        if (active(pokemon)) pokemon
        else pokemon.copyState(knownVolatileEffectIds = pokemon.knownVolatileEffectIds.filterNot { it.startsWith(TURNS) }.toSet() +
            VOLATILE + "$TURNS$DURATION")

    /**
     * Whether [pokemon] can Dynamax at all, from its species data: a species or form that cannot (Showdown's
     * `cannotDynamax`, the box Legendaries) and a Mega form do not. Unknown species count as able.
     */
    fun canDynamax(pokemon: BattlePokemonStateView): Boolean {
        val species = LocalPublicSpeciesData.species(pokemon) ?: return true
        return !species.cannotDynamax && !species.isMega && !species.isPrimal
    }

    /**
     * [action] as the Max Move it becomes while its user is Dynamaxed: the Max Move of its type (Showdown's table, as the
     * engine ports it), the power its data gives (`maxMove.basePower`), the Max Move's own effects and flags, and no miss.
     * A status move, a Max Move already, or an attack without that data is left as it is. A Gigantamax user's G-Max Move
     * is read as the plain Max Move of its type.
     */
    fun asMaxMove(action: BattleActionCandidate, user: BattlePokemonStateView): BattleActionCandidate {
        if (!active(user)) return action
        val details = action.moveDetails ?: return action
        if (details.damageCategory == BattleMoveDamageCategory.STATUS) return action
        val dex = runCatching { EngineRuntimeDex.current().second }.getOrNull() ?: return action
        val base = dex.move(PublicIds.canonical(action.moveId ?: return action)) ?: return action
        if (Js.truthy(base.data("isMax"))) return action
        val power = (base.data("maxMove") as? Map<*, *>)?.get("basePower")?.let { Js.num(it) }?.takeIf { it > 0.0 } ?: return action
        val maxId = BattleActions.MAX_MOVES[base.type]?.let(Js::toID) ?: return action
        val effects = BattleShowdownMoveEffects.resolve(maxId) ?: return action
        return BattleActionCandidate(
            actionId = "${action.actionId}:max", kind = action.kind, actorSlot = action.actorSlot, moveSlot = action.moveSlot,
            moveId = maxId, targets = action.targets, mechanic = action.mechanic,
            moveDetails = details.copy(power = power, accuracy = 100.0, effects = effects),
            tags = action.tags + "$BASE_MOVE_TAG${PublicIds.canonical(action.moveId)}",
        )
    }

    /** The move [action] was before it became a Max Move ([asMaxMove]), or its own. */
    fun baseMoveId(action: BattleActionCandidate): String? =
        action.tags.firstOrNull { it.startsWith(BASE_MOVE_TAG) }?.removePrefix(BASE_MOVE_TAG) ?: action.moveId?.let(PublicIds::canonical)

    private const val BASE_MOVE_TAG = "maxmovebase:"
}
