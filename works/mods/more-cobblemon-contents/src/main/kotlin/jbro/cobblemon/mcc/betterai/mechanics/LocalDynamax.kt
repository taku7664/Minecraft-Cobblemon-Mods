package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.betterai.engine.Js
import jbro.cobblemon.mcc.betterai.engine.sim.BattleActions
import jbro.cobblemon.mcc.betterai.simulation.EngineRuntimeDex
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleMoveCandidateView
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
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

    /** Whether [mechanicId] is Dynamax (the request's `dynamax`, the move response's `max`). */
    fun isMechanic(mechanicId: String?): Boolean = PublicIds.canonical(mechanicId ?: return false) in MECHANIC_IDS

    /**
     * [action] as the move its user actually uses: the Max Move when it Dynamaxes with it ([isMechanic]) or is
     * Dynamaxed already, else itself. For calculating its damage; the candidate keeps its own id.
     */
    fun asUsed(action: BattleActionCandidate, user: BattlePokemonStateView): BattleActionCandidate =
        if (!isMechanic(action.mechanic?.mechanicId)) asMaxMove(action, user)
        else asMaxMove(action, start(user, isGMaxMove(action.mechanic?.transformedMoveId) || gigantamax(user)))

    fun active(pokemon: BattlePokemonStateView?): Boolean =
        pokemon?.knownVolatileEffectIds?.any { PublicIds.canonical(it) == VOLATILE } == true

    /** Whether a side has publicly spent Dynamax, including after it ended or its user fainted. */
    fun used(state: BattleStateView, side: BattleSide): Boolean = state.pokemon.any { pokemon ->
        pokemon.side == side && pokemon.knownVolatileEffectIds.any {
            it == USED || it == GIGANTAMAX || PublicIds.canonical(it) == VOLATILE
        }
    }

    /** [DAMAGE_SHARE] while [pokemon] is Dynamaxed, otherwise 1. */
    fun damageShare(pokemon: BattlePokemonStateView?): Double = if (active(pokemon)) DAMAGE_SHARE else 1.0

    /** Whether [pokemon] is known to have the Gigantamax factor: its own build says so, or it Gigantamaxed in public. */
    fun gigantamax(pokemon: BattlePokemonStateView?): Boolean = pokemon?.knownVolatileEffectIds?.contains(GIGANTAMAX) == true

    /** [pokemon] Dynamaxed from now, for [DURATION] turns, Gigantamaxed when [gigantamax]; unchanged when it already is. */
    fun start(pokemon: BattlePokemonStateView, gigantamax: Boolean = false): BattlePokemonStateView =
        if (active(pokemon)) pokemon
        else pokemon.copyState(knownVolatileEffectIds = pokemon.knownVolatileEffectIds.filterNot { it.startsWith(TURNS) }.toSet() +
            VOLATILE + "$TURNS$DURATION" + if (gigantamax) setOf(GIGANTAMAX) else emptySet())

    /** Whether [moveId] is a Max or G-Max Move (Showdown's `isMax`, `true` or the species). */
    fun isMaxMove(moveId: String?): Boolean = PublicIds.canonical(moveId ?: return false) in maxMoveIds

    /**
     * Every Max and G-Max Move id, read once from the bundled dex: the runtime dex only overlays species and abilities,
     * and reading it hashes every Cobblemon species, too costly for a test the matchup table runs on every move.
     */
    private val maxMoveIds: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching {
            jbro.cobblemon.mcc.betterai.engine.dex.EngineDex.bundled().allMoves
                .filter { it.data("isMax").let { flag -> flag == true || flag is String } }
                .mapTo(hashSetOf()) { PublicIds.canonical(it.id) }
        }.getOrDefault(emptySet())
    }

    /** Whether [moveId] is a G-Max Move: Showdown names the species on it (`isMax: "Charizard"`), a Max Move has `true`. */
    fun isGMaxMove(moveId: String?): Boolean {
        val dex = runCatching { EngineRuntimeDex.current().second }.getOrNull() ?: return false
        return dex.move(PublicIds.canonical(moveId ?: return false))?.data("isMax") is String
    }

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
     * A Gigantamax user's attack of its G-Max Move's type is that G-Max Move instead (Showdown's `getActiveMaxMove`), at
     * the same power unless the G-Max Move's data sets its own (the ones Showdown keeps at 160). A status move, a Max Move
     * already, or an attack without that data is left as it is.
     */
    fun asMaxMove(action: BattleActionCandidate, user: BattlePokemonStateView): BattleActionCandidate {
        if (!active(user)) return action
        val details = action.moveDetails ?: return action
        if (details.damageCategory == BattleMoveDamageCategory.STATUS) return action
        val dex = runCatching { EngineRuntimeDex.current().second }.getOrNull() ?: return action
        val base = dex.move(PublicIds.canonical(action.moveId ?: return action)) ?: return action
        if (Js.truthy(base.data("isMax"))) return action
        val gMax = if (!gigantamax(user)) null else LocalPublicSpeciesData.species(user)?.let { BattleActions.GMAX_MAP[Js.toID(it.name)] }
            ?.let(dex::move)?.takeIf { it.type == base.type }
        val maxId = gMax?.id ?: BattleActions.MAX_MOVES[base.type]?.let(Js::toID) ?: return action
        val maxDetails = maxMoveDetails(base.id, details, maxId) ?: return action
        return BattleActionCandidate(
            actionId = "${action.actionId}:max", kind = action.kind, actorSlot = action.actorSlot, moveSlot = action.moveSlot,
            moveId = maxId, targets = action.targets, mechanic = action.mechanic,
            moveDetails = maxDetails,
            tags = action.tags + "$BASE_MOVE_TAG${PublicIds.canonical(action.moveId)}",
        )
    }

    /**
     * [details] of [baseMoveId] as the Max or G-Max Move [maxMoveId] it turns into: the Max Move's own effects and flags,
     * no miss, and Showdown's power rule (`getActiveMaxMove`): the base move's `maxMove.basePower`, unless the G-Max
     * Move's data keeps its own (the ones Showdown fixes at 160). A status move becomes Max Guard and keeps no power.
     * Null when the data for either move is missing.
     */
    fun maxMoveDetails(baseMoveId: String, details: BattleMoveCandidateView, maxMoveId: String): BattleMoveCandidateView? {
        val dex = runCatching { EngineRuntimeDex.current().second }.getOrNull() ?: return null
        val base = dex.move(PublicIds.canonical(baseMoveId)) ?: return null
        val max = dex.move(PublicIds.canonical(maxMoveId)) ?: return null
        val effects = BattleShowdownMoveEffects.resolve(max.id) ?: return null
        if (details.damageCategory == BattleMoveDamageCategory.STATUS) return details.copy(power = 0.0, accuracy = 100.0, effects = effects)
        val power = Js.num(max.data("basePower") ?: 0).takeIf { it > G_MAX_PLACEHOLDER_POWER && max.data("isMax") is String }
            ?: (base.data("maxMove") as? Map<*, *>)?.get("basePower")?.let { Js.num(it) }?.takeIf { it > 0.0 } ?: return null
        return details.copy(power = power, accuracy = 100.0, effects = effects)
    }

    /**
     * The id and tags a Dynamax choice of [baseMoveId] is ranked under, its details [details] already the Max Move
     * [maxMoveId]'s (null when the choice is no Max Move). A status move is Max Guard and nothing of itself: ranked under
     * its own id, a Dynamax Leech Seed took Leech Seed's drain (31.25) and a Dynamax Stealth Rock with the rocks up was
     * idle, both as Max Guard (G-948 ①). An attack keeps its base id: the matchup table turns it into its Max Move itself
     * ([asMaxMove]) and keys its scores by the base move (G-946).
     */
    fun choiceIdentity(baseMoveId: String, details: BattleMoveCandidateView?, maxMoveId: String?): Pair<String, Set<String>> =
        if (maxMoveId == null || details?.damageCategory != BattleMoveDamageCategory.STATUS) baseMoveId to emptySet()
        else PublicIds.canonical(maxMoveId) to setOf("$BASE_MOVE_TAG${PublicIds.canonical(baseMoveId)}")

    /** The move [action] was before it became a Max Move ([asMaxMove], [choiceIdentity]), or its own. */
    fun baseMoveId(action: BattleActionCandidate): String? =
        action.tags.firstOrNull { it.startsWith(BASE_MOVE_TAG) }?.removePrefix(BASE_MOVE_TAG) ?: action.moveId?.let(PublicIds::canonical)

    private const val BASE_MOVE_TAG = "maxmovebase:"
    private val MECHANIC_IDS = setOf("dynamax", "max")
    private const val G_MAX_PLACEHOLDER_POWER = 10.0
    /** Kept on a Pokemon once its Gigantamax factor is known. */
    const val GIGANTAMAX = "gigantamax"
    /** Public bookkeeping retained on the Pokemon that spent its side's once-per-battle Dynamax. */
    const val USED = "better_ai:dynamax_used"
    /** The observer's id for a `-start|...|Dynamax|Gmax` message. */
    const val GIGANTAMAX_START = "dynamax:gmax"
}
