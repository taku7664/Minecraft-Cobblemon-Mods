package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.PublicIds
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleStateView

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

        val actorAbility = LocalPublicAbilityState.effectiveKnownAbility(state, actor)
        return actorAbility in ABILITY_IGNORING_ABILITIES ||
            actorAbility == MYCELIUM_MIGHT &&
                candidate.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS ||
            candidate.moveDetails?.effects?.effects.orEmpty().any {
                it.kind == BattleMoveEffectKind.IGNORE_ABILITY
            }
    }

    private fun canonical(value: String?): String = value?.let(PublicIds::canonical)
        .orEmpty()

    private const val ABILITY_SHIELD = "abilityshield"
    private const val MYCELIUM_MIGHT = "myceliummight"
    private val ABILITY_IGNORING_ABILITIES = setOf("moldbreaker", "teravolt", "turboblaze")
}
