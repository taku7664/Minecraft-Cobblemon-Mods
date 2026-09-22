package jbro.cobblemon.morebattlecontent.betterai.mechanics

import jbro.cobblemon.morebattlecontent.api.ai.*

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
            return listOfNotNull(declared?.let { redirectedAwayFrom(it, candidate, context) ?: it })
        }
        val targetSide = if (actingSide == BattleSide.ALLY) BattleSide.OPPONENT else BattleSide.ALLY
        val activeOpponents = context.state.pokemon
            .filter { it.side == targetSide && it.activeSlot != null && !it.fainted }
            .sortedBy { it.activeSlot }
        if (candidate.moveDetails?.targetPattern in SPREAD_PATTERNS) return activeOpponents
        // No guessed defender when a non-spread action leaves two opposing slots ambiguous.
        return listOfNotNull(activeOpponents.singleOrNull())
    }

    private fun redirectedAwayFrom(declared: BattlePokemonStateView, candidate: BattleActionCandidate,
        context: BattleDecisionContext): BattlePokemonStateView? {
        if (context.state.format != BattleFormat.DOUBLE) return null
        if (candidate.moveDetails?.targetPattern !in REDIRECTABLE_PATTERNS) return null
        val type = canonical(candidate.moveDetails?.typeId) ?: return null
        return context.state.pokemon.firstOrNull { other ->
            other.side == declared.side && other.activeSlot != null &&
                other.battlePokemonId != declared.battlePokemonId && !other.fainted && other.hpFraction > 0.0 &&
                redirectsPublicly(other, context, type)
        }
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

    private fun canonical(value: String?): String? = value?.substringAfter(':')?.lowercase()?.filter(Char::isLetterOrDigit)

    private val SPREAD_PATTERNS = setOf(BattleMoveTargetPattern.ALL_OPPONENTS,
        BattleMoveTargetPattern.ALL_ADJACENT, BattleMoveTargetPattern.ALL_ACTIVE)
    private val REDIRECTABLE_PATTERNS = setOf(BattleMoveTargetPattern.SELECTED, BattleMoveTargetPattern.SELECTED_OPPONENT)
    private val REDIRECTING_ABILITIES = mapOf("lightningrod" to "electric", "stormdrain" to "water")
}
