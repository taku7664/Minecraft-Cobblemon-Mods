package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.*

/** Shared target interpretation for primary damage facts, modifiers and leaf HP caps.
 * Preserves the calculator's public ability rules; this is not a complete engine target resolver.
 * Implicit spread targets include opponents only; friendly collateral is evaluated separately.
 */
internal object LocalPublicMoveTargets {
    fun resolve(candidate: BattleActionCandidate, context: BattleDecisionContext,
        actingSide: BattleSide): List<BattlePokemonStateView> {
        val explicit = candidate.targets.singleOrNull()
        if (explicit != null) {
            val declared = context.state.pokemon.firstOrNull {
                it.side == explicit.side && it.activeSlot == explicit.slot && !it.fainted
            }
            return listOfNotNull(declared?.let { redirectedAwayFrom(it, candidate, context, actingSide) ?: it })
        }
        val targetSide = if (actingSide == BattleSide.ALLY) BattleSide.OPPONENT else BattleSide.ALLY
        val activeOpponents = context.state.pokemon
            .filter { it.side == targetSide && it.activeSlot != null && !it.fainted }
            .sortedBy { it.activeSlot }
        if (candidate.moveDetails?.targetPattern in SPREAD_PATTERNS) return activeOpponents
        // No guessed defender when a non-spread action leaves two opposing slots ambiguous.
        return listOfNotNull(activeOpponents.singleOrNull())
    }

    /**
     * Showdown's 0.75 spread reduction. It applies when the move lands on more than one Pokemon, the
     * user's partner included, so it is not the size of [resolve], which lists opponents only. A
     * spread move projected one target at a time carries [SPREAD_HIT_TAG] instead, because its
     * single explicit target no longer says how many it struck.
     */
    fun spreadMultiplier(candidate: BattleActionCandidate, context: BattleDecisionContext,
        actingSide: BattleSide): Double {
        if (SPREAD_HIT_TAG in candidate.tags) return SPREAD_DAMAGE_MULTIPLIER
        if (candidate.targets.isNotEmpty()) return 1.0
        val pattern = candidate.moveDetails?.targetPattern ?: return 1.0
        if (pattern !in SPREAD_PATTERNS) return 1.0
        val actor = candidate.actorSlot?.let { slot ->
            context.state.pokemon.firstOrNull { it.side == actingSide && it.activeSlot == slot }
        }
        val struck = context.state.pokemon.count { other ->
            other.activeSlot != null && !other.fainted && other.hpFraction > 0.0 &&
                other.battlePokemonId != actor?.battlePokemonId &&
                (other.side != actingSide || pattern != BattleMoveTargetPattern.ALL_OPPONENTS)
        }
        return if (struck > 1) SPREAD_DAMAGE_MULTIPLIER else 1.0
    }

    /** The candidate as it lands on [target] alone, as one hit of a spread move that struck several. */
    fun spreadHitOn(candidate: BattleActionCandidate, target: BattlePokemonStateView, actionId: String) =
        BattleActionCandidate(
            actionId = actionId,
            kind = candidate.kind,
            actorSlot = candidate.actorSlot,
            moveSlot = candidate.moveSlot,
            moveId = candidate.moveId,
            targets = listOf(BattleTargetSlot(target.side, requireNotNull(target.activeSlot))),
            mechanic = candidate.mechanic,
            moveDetails = candidate.moveDetails,
            tags = candidate.tags + SPREAD_HIT_TAG,
        )

    /** Marks one target's share of a spread move that struck more than one Pokemon. */
    const val SPREAD_HIT_TAG = "spread-hit"
    private const val SPREAD_DAMAGE_MULTIPLIER = 0.75

    /** All publicly possible first redirectors. Equal Speed keeps its genuine tie alternatives. */
    fun redirectOutcomes(candidate: BattleActionCandidate, context: BattleDecisionContext,
                         actingSide: BattleSide): List<Pair<BattleActionCandidate, Double>> {
        if (context.state.format != BattleFormat.DOUBLE || "resolved_ability_target" in candidate.tags || "turn_redirected" in candidate.tags ||
            candidate.moveDetails?.targetPattern !in REDIRECTABLE_PATTERNS) return listOf(candidate to 1.0)
        val declared = candidate.targets.singleOrNull() ?: return listOf(candidate to 1.0)
        val user = context.state.pokemon.firstOrNull { it.side == actingSide && it.activeSlot == candidate.actorSlot } ?: return listOf(candidate to 1.0)
        val type = canonical(LocalPublicMoveDamageInputs.resolvedTypeId(candidate, user, context.state))
            ?: return listOf(candidate to 1.0)
        val possible = context.state.pokemon.filter { other -> other.activeSlot != null && !other.fainted && other.hpFraction > 0.0 &&
            other.battlePokemonId != user.battlePokemonId && redirectsPublicly(other, context, type) &&
            !LocalPublicAbilityMechanics.ignoresTargetAbility(candidate, user, other, context.state) }
        if (possible.isEmpty()) return listOf(candidate to 1.0)
        fun speed(pokemon: BattlePokemonStateView) = LocalPublicTurnOrder.effectiveSpeed(context.state, pokemon)
        val leaders = possible.filter { other -> possible.all { rival ->
            rival.battlePokemonId == other.battlePokemonId || (speed(other)?.second ?: Int.MAX_VALUE) >= (speed(rival)?.first ?: 0)
        } }
        return leaders.map { redirector ->
            BattleActionCandidate(candidate.actionId, candidate.kind, actorSlot = candidate.actorSlot, moveSlot = candidate.moveSlot,
                moveId = candidate.moveId, targets = listOf(BattleTargetSlot(redirector.side, requireNotNull(redirector.activeSlot))),
                mechanic = candidate.mechanic, moveDetails = candidate.moveDetails, tags = candidate.tags + "resolved_ability_target") to 1.0 / leaders.size
        }
    }

    private fun redirectedAwayFrom(declared: BattlePokemonStateView, candidate: BattleActionCandidate,
        context: BattleDecisionContext, actingSide: BattleSide): BattlePokemonStateView? {
        val outcomes = redirectOutcomes(candidate, context, actingSide)
        val slot = outcomes.singleOrNull()?.first?.targets?.singleOrNull() ?: return null
        return context.state.pokemon.firstOrNull { it.side == slot.side && it.activeSlot == slot.slot }
    }

    private fun redirectsPublicly(pokemon: BattlePokemonStateView, context: BattleDecisionContext,
        type: String): Boolean {
        val revealed = LocalPublicAbilityState.effectiveKnownAbility(context.state, pokemon)
        if (revealed != null) return REDIRECTING_ABILITIES[revealed] == type
        val possible = context.state.inferences.asSequence()
            .filter { it.subjectPokemonId == pokemon.battlePokemonId && it.categoryId == "ability" }
            .filter { it.confidence != BattleInferenceConfidence.RULED_OUT }
            .mapNotNull { canonical(it.candidateId) }.distinct().toList()
        return LocalPublicAbilityState.isActive(context.state, pokemon, "levitate") &&
            possible.isNotEmpty() && possible.all { REDIRECTING_ABILITIES[it] == type }
    }

    private fun canonical(value: String?): String? = value?.let(PublicIds::canonical)

    private val SPREAD_PATTERNS = setOf(BattleMoveTargetPattern.ALL_OPPONENTS,
        BattleMoveTargetPattern.ALL_ADJACENT, BattleMoveTargetPattern.ALL_ACTIVE)
    private val REDIRECTABLE_PATTERNS = setOf(BattleMoveTargetPattern.SELECTED, BattleMoveTargetPattern.SELECTED_OPPONENT)
    private val REDIRECTING_ABILITIES = mapOf("lightningrod" to "electric", "stormdrain" to "water")
}
