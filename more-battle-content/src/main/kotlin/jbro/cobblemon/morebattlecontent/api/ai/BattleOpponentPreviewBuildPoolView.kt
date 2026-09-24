package jbro.cobblemon.morebattlecontent.api.ai

import java.util.Collections
import kotlin.math.abs

data class BattleOpponentPreviewAbilityView(
    val abilityId: String,
    val availability: BattleAbilityAvailability,
) {
    init {
        require(abilityId.isNotBlank()) { "Public ability candidate ID cannot be blank" }
    }
}

/** Public species/form legality and gender priors without a live Pokemon or private set. */
class BattleOpponentPreviewBuildPoolView(
    val speciesId: String,
    val formId: String?,
    abilities: List<BattleOpponentPreviewAbilityView>,
    genderRates: Map<String, Double>,
    val sourceId: String,
) {
    val abilities: List<BattleOpponentPreviewAbilityView> =
        Collections.unmodifiableList(ArrayList(abilities))
    val genderRates: Map<String, Double> =
        Collections.unmodifiableMap(LinkedHashMap(genderRates))

    init {
        require(speciesId.isNotBlank())
        require(formId == null || formId.isNotBlank())
        require(sourceId.isNotBlank())
        require(this.abilities.isNotEmpty()) { "Public build pool requires at least one legal ability" }
        require(this.abilities.map { it.abilityId }.distinct().size == this.abilities.size) {
            "Public build pool cannot contain duplicate ability IDs"
        }
        require(this.genderRates.isNotEmpty() && this.genderRates.keys.all { it in setOf("M", "F", "N") }) {
            "Public build pool must use Showdown gender IDs"
        }
        require(this.genderRates.values.all { it.isFinite() && it > 0.0 && it <= 1.0 })
        require(abs(this.genderRates.values.sum() - 1.0) <= 1e-9) {
            "Public gender probabilities must sum to one"
        }
    }
}
