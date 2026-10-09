package jbro.cobblemon.mcc.betterai.evaluation

import jbro.cobblemon.mcc.internal.ai.PublicIds
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleObservedEventKind
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide

/**
 * Utility moves that cannot accomplish anything in the position they are being considered from.
 *
 * Every status move the evaluator does not recognise falls through to one flat value. That is a sound
 * default - a move whose effect is not described should not be assumed worthless - and it is a poor
 * one for the large family of moves whose entire worth is conditional on the board. Sleep Talk while
 * awake, Substitute at a quarter health, Heal Bell with nobody statused, Leech Seed into a Grass
 * type: each of them simply fails, and each was priced as an ordinary play that could beat a real
 * attack whenever the attacks looked mediocre.
 *
 * These are recognised by move id, which is not the shape this wants. The contract has no effect kind
 * for clearing a side, for draining a party status, or for the dozen other things listed here, so the
 * choice is between naming the moves and leaving the holes open. Naming them errs only in the
 * direction of doing too little: a move absent from these tables keeps exactly the behaviour it has
 * today, and adding one is a line.
 *
 * Everything here is checked against public state only. Nothing consults a hidden set.
 */
internal object LocalIdleUtilityMoveRules {
    /** Whether this move, played now, cannot change the public state at all. */
    fun isIdle(candidate: BattleActionCandidate, context: BattleDecisionContext): Boolean {
        val moveId = candidate.moveId?.let(::canonical) ?: return false
        val actor = actor(candidate, context) ?: return false
        val target = opposingActive(context, actor.side).firstOrNull()
        if (failsForInsufficientHp(candidate, context)) return true
        return when (moveId) {
            in HAZARD_REMOVAL -> noEntryHazardsAnywhere(context)
            in STAT_STAGE_RESET -> nobodyActiveIsBoosted(context)
            in SLEEP_DEPENDENT -> !isAsleep(actor)
            in PARTY_STATUS_CURES -> partyOf(context, actor.side).none { it.statusId != null }
            in ITEM_SWAPS -> actor.canonicalKnownHeldItemId == null
            in FORCED_ROTATIONS -> (context.state.remainingPokemonBySide[opposing(actor.side)] ?: 0) <= 1
            LEECH_SEED -> target != null && target.knownTypeIds.any { canonical(it) == "grass" }
            TAUNT -> target?.actionConstraints?.taunted == true
            ENCORE -> target?.actionConstraints?.encoreMoveId != null
            YAWN -> target?.statusId != null
            in HAZARD_LAYERS.keys -> hazardIsFull(context, opposing(actor.side), moveId) ||
                LocalHazardSwitchAvailability.fraction(context.state, opposing(actor.side), context) == 0.0
            else -> false
        }
    }

    /**
     * A singles Protect or Detect that gains nothing: the foe's next attack only waits a turn. It earns its turn
     * when the wait itself pays: the foe takes residual damage (poison, burn, Leech Seed, Salt Cure, a trap,
     * Yawn), the user heals or speeds up (Leftovers, Black Sludge, Poison Heal, Ingrain, Aqua Ring, Grassy
     * Terrain, Speed Boost), or its own Wish lands. A one-turn search could not tell the difference: a dodged
     * hit looked like a free turn, so a factory Alomomola protected turn after turn instead of attacking.
     * Doubles keep Protect for the partner; the shields with a contact or forme effect keep their own gain.
     */
    fun purposelessProtect(candidate: BattleActionCandidate, context: BattleDecisionContext): Boolean {
        if (context.state.format != BattleFormat.SINGLE || candidate.mechanic != null) return false
        if (candidate.moveId?.let(::canonical) !in PLAIN_PROTECTS) return false
        val actor = actor(candidate, context) ?: return false
        val foe = opposingActive(context, actor.side).singleOrNull() ?: return false
        val foeResidual = canonical(foe.statusId.orEmpty()) in RESIDUAL_STATUSES ||
            foe.knownVolatileEffectIds.any { canonical(it) in RESIDUAL_VOLATILES }
        val item = actor.knownHeldItemId?.let(::canonical)
        val ability = actor.knownAbilityId?.let(::canonical)
        val ownRecovery = item == LEFTOVERS ||
            item == BLACK_SLUDGE && actor.knownTypeIds.any { canonical(it) == "poison" } ||
            ability == SPEED_BOOST ||
            ability == POISON_HEAL && canonical(actor.statusId.orEmpty()) in POISON_STATUSES ||
            actor.knownVolatileEffectIds.any { canonical(it) in RECOVERY_VOLATILES } ||
            context.state.field.terrain?.effectId?.let(::canonical) == GRASSY_TERRAIN &&
            jbro.cobblemon.mcc.betterai.mechanics.LocalPublicTurnOrder.grounded(context.state, actor)
        return !foeResidual && !ownRecovery && !ownWishPending(context)
    }

    /** A Wish from this side last turn lands at the end of this one. */
    private fun ownWishPending(context: BattleDecisionContext): Boolean {
        val own = context.state.pokemon.filter { it.side == BattleSide.ALLY }.mapTo(hashSetOf()) { it.battlePokemonId }
        return context.state.observedEvents.any {
            it.kind == BattleObservedEventKind.MOVE_USED && it.actorPokemonId in own &&
                it.publicValueId?.let(::canonical) == WISH && it.turn >= context.state.turn - 1
        }
    }

    /** Current public HP requirement only; does not remove legal actions or generalize other idle rules. */
    fun failsForInsufficientHp(candidate: BattleActionCandidate, context: BattleDecisionContext): Boolean {
        // A mechanic may heal first or replace the move; its sequence is not described by this rule.
        if (candidate.mechanic != null) return false
        val moveId = candidate.moveId?.let(::canonical) ?: return false
        val actor = actor(candidate, context) ?: return false
        return when (moveId) {
            SUBSTITUTE -> actor.hpFraction <= SUBSTITUTE_HP_COST
            in HALF_HEALTH_BOOSTS -> actor.hpFraction <= HALF_HEALTH_COST
            else -> false
        }
    }

    private fun noEntryHazardsAnywhere(context: BattleDecisionContext): Boolean =
        context.state.field.sideConditions.values.all { conditions ->
            conditions.none { canonical(it.effectId) in HAZARD_LAYERS.keys }
        }

    private fun nobodyActiveIsBoosted(context: BattleDecisionContext): Boolean =
        context.state.pokemon
            .filter { it.activeSlot != null && !it.fainted }
            .all { active -> active.statStages.values.none { it != 0 } }

    /** A hazard already stacked as high as it goes cannot be laid again. */
    private fun hazardIsFull(context: BattleDecisionContext, side: BattleSide, moveId: String): Boolean {
        val maximum = HAZARD_LAYERS[moveId] ?: return false
        val present = context.state.field.sideConditions.getValue(side)
            .firstOrNull { canonical(it.effectId) == moveId } ?: return false
        return (present.stacks ?: 1) >= maximum
    }

    private fun isAsleep(actor: BattlePokemonStateView): Boolean = canonical(actor.statusId.orEmpty()) in SLEEP_IDS

    private fun partyOf(context: BattleDecisionContext, side: BattleSide): List<BattlePokemonStateView> =
        context.state.pokemon.filter { it.side == side && !it.fainted }

    private fun opposingActive(context: BattleDecisionContext, side: BattleSide): List<BattlePokemonStateView> =
        context.state.pokemon.filter {
            it.side == opposing(side) && it.activeSlot != null && !it.fainted
        }

    private fun opposing(side: BattleSide): BattleSide =
        if (side == BattleSide.ALLY) BattleSide.OPPONENT else BattleSide.ALLY

    private fun actor(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
    ): BattlePokemonStateView? = context.state.pokemon.firstOrNull {
        it.activeSlot == candidate.actorSlot && !it.fainted && it.side == BattleSide.ALLY
    }

    private fun canonical(value: String): String =
        PublicIds.canonical(value)

    private const val SUBSTITUTE = "substitute"
    private const val LEECH_SEED = "leechseed"
    private const val TAUNT = "taunt"
    private const val ENCORE = "encore"
    private const val YAWN = "yawn"

    /** Separate requirements: Substitute costs a quarter, Belly Drum/Fillet Away require over half HP. */
    private const val SUBSTITUTE_HP_COST = 0.25
    private const val HALF_HEALTH_COST = 0.5

    private val HAZARD_REMOVAL = setOf("defog", "rapidspin", "mortalspin", "tidyup", "courtchange")
    private val STAT_STAGE_RESET = setOf("haze", "clearsmog", "topsyturvy", "spectralthief")
    private val SLEEP_DEPENDENT = setOf("sleeptalk", "snore")
    private val PARTY_STATUS_CURES = setOf("healbell", "aromatherapy")
    private val ITEM_SWAPS = setOf("trick", "switcheroo")
    private val FORCED_ROTATIONS = setOf("roar", "whirlwind", "dragontail", "circlethrow")
    private val HALF_HEALTH_BOOSTS = setOf("bellydrum", "filletaway")
    private val SLEEP_IDS = setOf("slp", "sleep", "asleep")

    private val PLAIN_PROTECTS = setOf("protect", "detect")
    private val RESIDUAL_STATUSES = setOf("psn", "tox", "brn")
    private val POISON_STATUSES = setOf("psn", "tox")
    private val RESIDUAL_VOLATILES = setOf("leechseed", "saltcure", "partiallytrapped", "curse", "nightmare", "yawn",
        "octolock", "syrupbomb")
    private val RECOVERY_VOLATILES = setOf("ingrain", "aquaring")
    private const val LEFTOVERS = "leftovers"
    private const val BLACK_SLUDGE = "blacksludge"
    private const val SPEED_BOOST = "speedboost"
    private const val POISON_HEAL = "poisonheal"
    private const val GRASSY_TERRAIN = "grassyterrain"
    private const val WISH = "wish"

    /** How many times each entry hazard can be stacked before another use does nothing. */
    private val HAZARD_LAYERS = mapOf(
        "spikes" to 3,
        "toxicspikes" to 2,
        "stealthrock" to 1,
        "stickyweb" to 1,
        "steelsurge" to 1,
    )
}
