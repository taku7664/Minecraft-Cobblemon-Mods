package jbro.cobblemon.morebattlecontent.betterai.mechanics

import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView

/** Checks only status immunities proven by public type or a known/confirmed ability. */
internal object LocalPublicStatusImmunity {
    fun blocked(state: BattleStateView, target: BattlePokemonStateView, statusId: String?): Boolean {
        if (target.statusId != null) return true
        val status = canonical(statusId)
        val types = target.knownTypeIds.mapTo(hashSetOf(), ::canonical)
        val ability = canonical(target.knownAbilityId) ?: state.inferences.asSequence()
            .filter { it.subjectPokemonId == target.battlePokemonId && canonical(it.categoryId) == "ability" }
            .mapNotNull { canonical(it.candidateId) }
            .distinct()
            .singleOrNull()
        return when (status) {
            "psn", "poison", "poisoned", "tox", "toxic", "badlypoisoned" ->
                "poison" in types || "steel" in types || ability == "immunity"
            "brn", "burn", "burned", "burnt" ->
                "fire" in types || ability == "waterveil" || ability == "waterbubble"
            "par", "paralysis", "paralyzed", "paralysed" ->
                "electric" in types || ability == "limber"
            "slp", "sleep", "asleep" -> ability in setOf("insomnia", "vitalspirit", "sweetveil")
            "frz", "freeze", "frozen" -> "ice" in types || ability == "magmaarmor"
            else -> false
        }
    }

    private fun canonical(value: String?): String? = value?.substringAfter(':')?.lowercase()?.filter(Char::isLetterOrDigit)
}
