package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.PublicIds
import java.util.Locale
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattlePublicStabRules

/** One public STAB interpretation for every local fallback consumer. */
internal object LocalPublicStab {
    fun multiplier(
        candidate: BattleActionCandidate,
        actor: BattlePokemonStateView?,
        moveTypeId: String,
    ): Double? {
        actor ?: return null
        val transformedTypes = LocalMechanicFormResolution.transformedTypeIds(candidate, actor)
        if (transformedTypes.isNotEmpty() &&
            LocalMechanicFormResolution.replacesOriginalTypes(candidate, actor)
        ) {
            return if (transformedTypes.any { sameId(it, moveTypeId) }) 1.5 else 1.0
        }

        val candidateTeraType = candidate.mechanic
            ?.takeIf { canonical(it.mechanicId) == TERA }
            ?.let { transformedTypes.singleOrNull() }
        val activeTeraType = candidateTeraType ?: actor.knownTeraTypeId
        val consumedTypes = if (candidateTeraType != null) {
            // A legal Tera candidate is the activation turn, before any type boost has been consumed.
            emptySet()
        } else {
            actor.knownStellarBoostedTypeIds
        }
        return BattlePublicStabRules.conservativeMultiplier(
            actor.knownBaseStabTypeIds,
            activeTeraType,
            consumedTypes,
            moveTypeId,
        )
    }

    private fun sameId(left: String, right: String): Boolean = canonical(left) == canonical(right)
    private fun canonical(value: String): String =
        PublicIds.canonical(value)

    private const val TERA = "tera"
}
