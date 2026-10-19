package jbro.cobblemon.mcc.betterai.evaluation

import jbro.cobblemon.mcc.internal.ai.PublicIds
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.betterai.mechanics.copyState

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
    /**
     * Whether this move fails outright now: Showdown rejects it and nothing of it happens (no damage, no other effect).
     * The ranking reads this one as publicly inert; [isIdle] is wider, for moves with nothing left worth pricing (a
     * Rapid Spin with no hazards still hits, a Trick with no item may still take one, and those must keep their value).
     */
    fun fails(candidate: BattleActionCandidate, context: BattleDecisionContext): Boolean {
        val moveId = candidate.moveId?.let(::canonical) ?: return false
        val actor = actor(candidate, context) ?: return false
        if (failsForInsufficientHp(candidate, context)) return true
        return failsFor(moveId, candidate.mechanic != null, actor, opposingActive(context, actor.side).firstOrNull(), context)
    }

    /**
     * [fails] for any [actor], either side: [moveId] used by [actor] on [target] without ([withMechanic] false) a mechanic
     * first. The opponent intent predictor reads an opposing Pokemon's revealed moves through this, so a move that cannot
     * work is not predicted (a Stealth Rock with Stealth Rock up was read at 32% and never used, league runs 2026-10-19).
     */
    fun failsFor(moveId: String, withMechanic: Boolean, actor: BattlePokemonStateView, target: BattlePokemonStateView?,
        context: BattleDecisionContext): Boolean {
        if (!withMechanic) when (moveId) {
            SUBSTITUTE -> if (actor.hpFraction <= SUBSTITUTE_HP_COST) return true
            in HALF_HEALTH_BOOSTS -> if (actor.hpFraction <= HALF_HEALTH_COST) return true
        }
        return when (moveId) {
            in SLEEP_DEPENDENT -> !isAsleep(actor)
            // Roar and Whirlwind fail with nobody to bring in; Dragon Tail and Circle Throw still hit.
            in PHAZING_STATUS -> (context.state.remainingPokemonBySide[opposing(actor.side)] ?: 0) <= 1
            LEECH_SEED -> target != null && (target.knownTypeIds.any { canonical(it) == "grass" } ||
                target.knownVolatileEffectIds.any { canonical(it) == LEECH_SEED })
            POLTERGEIST -> target?.knownHeldItemId?.isBlank() == true
            in DYNAMAX_FAILING_ATTACKS -> !withMechanic && !jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax.active(actor) &&
                jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax.active(target)
            TAUNT -> target?.actionConstraints?.taunted == true
            ENCORE -> target?.actionConstraints?.encoreMoveId != null
            YAWN -> target?.statusId != null
            REST -> restFails(withMechanic, context, actor)
            in HAZARD_LAYERS.keys -> hazardIsFull(context, opposing(actor.side), moveId)
            else -> false
        }
    }

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
            // A Grass type, or one already seeded (Ferrothorn's Leech Seed failed into a seeded Grimmsnarl, league run).
            LEECH_SEED -> target != null && (target.knownTypeIds.any { canonical(it) == "grass" } ||
                target.knownVolatileEffectIds.any { canonical(it) == LEECH_SEED })
            // An item the target is known to have lost (blank): Poltergeist fails.
            POLTERGEIST -> target?.knownHeldItemId?.isBlank() == true
            // A weight-based attack fails on a Dynamaxed target (Showdown's onTryHit), unless its user's move is a Max Move
            // itself. A Copperajah's Heat Crash into a Dynamaxed Eldegoss ranked first at 203 (league run, 2026-10-19).
            in DYNAMAX_FAILING_ATTACKS -> candidate.mechanic == null && !jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax.active(actor) &&
                jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax.active(target)
            TAUNT -> target?.actionConstraints?.taunted == true
            ENCORE -> target?.actionConstraints?.encoreMoveId != null
            YAWN -> target?.statusId != null
            // Rest fails at full HP, asleep, with an ability that keeps it awake, or where it cannot fall asleep (Electric or
            // Misty Terrain, Sweet Veil); its other status is no bar. **Hard-coded** like the rest of this table: Rest by id.
            // A mechanic with it (Terastallization) may change the user's typing first, and with it whether it is grounded:
            // that candidate is left to the turn's projection, which applies the mechanic before the move.
            REST -> restFails(candidate.mechanic != null, context, actor)
            in HAZARD_LAYERS.keys -> hazardIsFull(context, opposing(actor.side), moveId) ||
                LocalHazardSwitchAvailability.fraction(context.state, opposing(actor.side), context) == 0.0
            else -> false
        }
    }

    /** A move that only resets stat stages (Haze's kind, by id) with nobody active holding a stage: it does nothing. */
    fun resetsNothing(moveId: String, context: BattleDecisionContext): Boolean =
        canonical(moveId) in STAT_STAGE_RESET && nobodyActiveIsBoosted(context)

    private fun restFails(withMechanic: Boolean, context: BattleDecisionContext, actor: BattlePokemonStateView): Boolean =
        !withMechanic && (actor.hpFraction >= 1.0 || isAsleep(actor) ||
            jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAbilityState.effectiveKnownAbility(context.state, actor)
                ?.let(::canonical) in SLEEPLESS ||
            jbro.cobblemon.mcc.betterai.mechanics.LocalPublicStatusImmunity.blocked(context.state,
                actor.copyState(statusId = null), "slp", actor, byMove = false))

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
    private const val REST = "rest"
    private const val POLTERGEIST = "poltergeist"
    /** **Hard-coded**: Showdown fails these on a Dynamaxed target in each move's own script; no data flag says so. */
    private val DYNAMAX_FAILING_ATTACKS = setOf("heavyslam", "heatcrash", "lowkick", "grassknot")
    private val SLEEPLESS = setOf("insomnia", "vitalspirit", "comatose", "purifyingsalt")

    /** Separate requirements: Substitute costs a quarter, Belly Drum/Fillet Away require over half HP. */
    private const val SUBSTITUTE_HP_COST = 0.25
    private const val HALF_HEALTH_COST = 0.5

    // Tidy Up is not here: it raises Attack and Speed with no hazard to clear, and read as idle it lost its raise.
    private val HAZARD_REMOVAL = setOf("defog", "rapidspin", "mortalspin", "courtchange")
    private val PHAZING_STATUS = setOf("roar", "whirlwind")
    private val STAT_STAGE_RESET = setOf("haze", "clearsmog", "topsyturvy", "spectralthief")
    private val SLEEP_DEPENDENT = setOf("sleeptalk", "snore")
    private val PARTY_STATUS_CURES = setOf("healbell", "aromatherapy")
    private val ITEM_SWAPS = setOf("trick", "switcheroo")
    private val FORCED_ROTATIONS = setOf("roar", "whirlwind", "dragontail", "circlethrow")
    private val HALF_HEALTH_BOOSTS = setOf("bellydrum", "filletaway")
    private val SLEEP_IDS = setOf("slp", "sleep", "asleep")

    /** How many times each entry hazard can be stacked before another use does nothing. */
    private val HAZARD_LAYERS = mapOf(
        "spikes" to 3,
        "toxicspikes" to 2,
        "stealthrock" to 1,
        "stickyweb" to 1,
        "steelsurge" to 1,
    )
}
