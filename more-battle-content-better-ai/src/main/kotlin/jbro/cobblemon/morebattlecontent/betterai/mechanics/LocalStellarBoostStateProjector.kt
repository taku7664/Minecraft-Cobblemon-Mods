package jbro.cobblemon.morebattlecontent.betterai.mechanics

import java.util.Locale
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView

/** Records the one-use Stellar type boost only after Showdown calculates damaging-move damage. */
internal object LocalStellarBoostStateProjector {
    fun afterSuccessfulDamageCalculation(
        state: BattleStateView,
        actorId: UUID,
        action: BattleActionCandidate,
    ): BattleStateView {
        val actor = state.pokemon.singleOrNull { it.battlePokemonId == actorId } ?: return state
        if (canonical(actor.knownTeraTypeId) != STELLAR || isTerapagosStellar(actor.speciesId, actor.formId)) {
            return state
        }
        val consumed = actor.knownStellarBoostedTypeIds ?: return state
        val details = action.moveDetails ?: return state
        if (details.damageCategory == BattleMoveDamageCategory.STATUS || details.power <= 0.0) {
            return state
        }
        val moveType = canonical(details.typeId) ?: return state
        if (consumed.any { canonical(it) == moveType }) return state
        val updated = actor.copyState(knownStellarBoostedTypeIds = consumed + moveType)
        return state.copyState(
            pokemon = state.pokemon.map { pokemon ->
                if (pokemon.battlePokemonId == actorId) updated else pokemon
            },
        )
    }

    private fun isTerapagosStellar(speciesId: String, formId: String?): Boolean {
        val species = canonical(speciesId).orEmpty()
        val form = canonical(formId).orEmpty()
        return species == TERAPAGOS_STELLAR || form == TERAPAGOS_STELLAR ||
            species == TERAPAGOS && form == STELLAR
    }

    private fun canonical(value: String?): String? = value
        ?.substringAfter(':')
        ?.lowercase(Locale.ROOT)
        ?.filter(Char::isLetterOrDigit)

    private const val STELLAR = "stellar"
    private const val TERAPAGOS = "terapagos"
    private const val TERAPAGOS_STELLAR = "terapagosstellar"
}
