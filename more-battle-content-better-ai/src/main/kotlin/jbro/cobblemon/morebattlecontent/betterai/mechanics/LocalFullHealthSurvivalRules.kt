package jbro.cobblemon.morebattlecontent.betterai.mechanics

import jbro.cobblemon.morebattlecontent.api.ai.BattleInferenceConfidence
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView

/**
 * Whether a target at full health is certain to survive one hit however hard it lands.
 *
 * Focus Sash and Sturdy are the two public reasons a knockout that the damage rolls call guaranteed
 * does not happen, and the difference matters more than it looks: almost everything this AI decides
 * from the front - whether to attack or set up, whether a patient line is affordable, whether to
 * switch - is built on the knockout assessment. A "guaranteed" that is wrong is not a small error in
 * one number, it is a wrong premise underneath the whole turn.
 *
 * The turn projector has always applied the Sash. The facts the ranking is built from did not, so the
 * two layers disagreed about the same position and the one that decides was the one that was wrong.
 *
 * Only public knowledge is used. The item has to have been revealed; the ability has to be revealed or
 * be the only remaining ability candidate, including publicly possible hidden abilities.
 */
internal object LocalFullHealthSurvivalRules {
    fun survivesAnySingleHit(
        state: BattleStateView,
        target: BattlePokemonStateView,
        ignoreTargetAbility: Boolean = false,
    ): Boolean {
        if (target.hpFraction < FULL_HEALTH) return false
        if (LocalPublicItemState.activeItemId(state, target) == FOCUS_SASH) return true
        if (ignoreTargetAbility) return false
        val known = LocalPublicAbilityState.effectiveKnownAbility(state, target)
        if (known != null) return known == STURDY
        val possible = possibleAbilities(state, target)
        return possible.isNotEmpty() && possible.all { it == STURDY }
    }

    /** Hidden is a species classification, not evidence that a candidate is impossible. */
    private fun possibleAbilities(state: BattleStateView, target: BattlePokemonStateView): List<String> =
        if (!LocalPublicAbilityState.isActive(state, target, STURDY)) emptyList() else
        state.inferences.asSequence()
            .filter { it.subjectPokemonId == target.battlePokemonId && canonical(it.categoryId) == ABILITY }
            .filter { it.confidence != BattleInferenceConfidence.RULED_OUT }
            .mapNotNull { canonical(it.candidateId) }
            .distinct()
            .toList()

    private fun canonical(value: String?): String? =
        value?.substringAfter(':')?.lowercase()?.filter(Char::isLetterOrDigit)

    /** Match direct-hit projection: tolerate floating-point noise, not actual projected HP loss. */
    private const val FULL_HEALTH = 1.0 - 1e-9
    private const val FOCUS_SASH = "focussash"
    private const val STURDY = "sturdy"
    private const val ABILITY = "ability"
}
