package jbro.cobblemon.morebattlecontent.betterai.evaluation

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide

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
        return when (moveId) {
            in HAZARD_REMOVAL -> noEntryHazardsAnywhere(context)
            in STAT_STAGE_RESET -> nobodyActiveIsBoosted(context)
            in SLEEP_DEPENDENT -> !isAsleep(actor)
            in PARTY_STATUS_CURES -> partyOf(context, actor.side).none { it.statusId != null }
            in ITEM_SWAPS -> actor.knownHeldItemId == null
            in FORCED_ROTATIONS -> (context.state.remainingPokemonBySide[opposing(actor.side)] ?: 0) <= 1
            SUBSTITUTE -> actor.hpFraction <= SUBSTITUTE_HP_COST
            in HALF_HEALTH_BOOSTS -> actor.hpFraction <= SUBSTITUTE_HP_COST
            LEECH_SEED -> target != null && target.knownTypeIds.any { canonical(it) == "grass" }
            TAUNT -> target?.actionConstraints?.taunted == true
            ENCORE -> target?.actionConstraints?.encoreMoveId != null
            YAWN -> target?.statusId != null
            in HAZARD_LAYERS.keys -> hazardIsFull(context, opposing(actor.side), moveId)
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
        value.substringAfter(':').lowercase().filter { it.isLetterOrDigit() }

    private const val SUBSTITUTE = "substitute"
    private const val LEECH_SEED = "leechseed"
    private const val TAUNT = "taunt"
    private const val ENCORE = "encore"
    private const val YAWN = "yawn"

    /** Substitute and the half-health boosts all fail outright at or below a quarter of maximum. */
    private const val SUBSTITUTE_HP_COST = 0.25

    private val HAZARD_REMOVAL = setOf("defog", "rapidspin", "mortalspin", "tidyup", "courtchange")
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
