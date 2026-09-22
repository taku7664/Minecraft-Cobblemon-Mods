package jbro.cobblemon.morebattlecontent.betterai.mechanics

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectKind
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView

/** Shared public rule for effects that bypass a target's ability. */
internal object LocalPublicAbilityMechanics {
    fun ignoresTargetAbility(
        candidate: BattleActionCandidate,
        actor: BattlePokemonStateView?,
        target: BattlePokemonStateView?,
        state: BattleStateView,
    ): Boolean {
        val abilityShieldActive = canonical(target?.knownHeldItemId) == ABILITY_SHIELD &&
            !LocalPublicFieldMechanics.magicRoomActive(state)
        if (abilityShieldActive) return false

        val actorAbility = canonical(actor?.knownAbilityId)
        return actorAbility in ABILITY_IGNORING_ABILITIES ||
            actorAbility == MYCELIUM_MIGHT &&
                candidate.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS ||
            candidate.moveDetails?.effects?.effects.orEmpty().any {
                it.kind == BattleMoveEffectKind.IGNORE_ABILITY
            }
    }

    private fun canonical(value: String?): String = value
        ?.substringAfter(':')
        ?.lowercase()
        ?.filter(Char::isLetterOrDigit)
        .orEmpty()

    private const val ABILITY_SHIELD = "abilityshield"
    private const val MYCELIUM_MIGHT = "myceliummight"
    private val ABILITY_IGNORING_ABILITIES = setOf("moldbreaker", "teravolt", "turboblaze")
}
