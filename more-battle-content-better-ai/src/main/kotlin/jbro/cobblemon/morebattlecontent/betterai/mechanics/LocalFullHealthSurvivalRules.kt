package jbro.cobblemon.morebattlecontent.betterai.mechanics

import jbro.cobblemon.morebattlecontent.api.ai.BattleAbilityAvailability
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
 * be the species' only ordinary one, which is the same standard the type-immunity reading uses.
 */
internal object LocalFullHealthSurvivalRules {
    fun survivesAnySingleHit(state: BattleStateView, target: BattlePokemonStateView): Boolean {
        if (target.hpFraction < FULL_HEALTH) return false
        if (canonical(target.knownHeldItemId) == FOCUS_SASH) return true
        val known = canonical(target.knownAbilityId)
        if (known != null) return known == STURDY
        val ordinary = ordinaryAbilities(state, target)
        return ordinary.isNotEmpty() && ordinary.all { it == STURDY }
    }

    /**
     * Every ability the species can ordinarily have, as the public pool reports it.
     *
     * Hidden entries are excluded on purpose: they are the exception rather than the expectation, and
     * letting one sit in the pool is what previously turned "this species always has X" into "nothing
     * can be concluded".
     */
    fun ordinaryAbilities(state: BattleStateView, target: BattlePokemonStateView): List<String> =
        state.inferences.asSequence()
            .filter { it.subjectPokemonId == target.battlePokemonId && canonical(it.categoryId) == ABILITY }
            .filter { it.confidence != BattleInferenceConfidence.RULED_OUT }
            .filter { it.abilityAvailability != BattleAbilityAvailability.HIDDEN }
            .mapNotNull { canonical(it.candidateId) }
            .distinct()
            .toList()

    private fun canonical(value: String?): String? =
        value?.substringAfter(':')?.lowercase()?.filter(Char::isLetterOrDigit)

    /** Health is reported as a fraction, so full health has to tolerate the rounding it arrives with. */
    private const val FULL_HEALTH = 0.999
    private const val FOCUS_SASH = "focussash"
    private const val STURDY = "sturdy"
    private const val ABILITY = "ability"
}
