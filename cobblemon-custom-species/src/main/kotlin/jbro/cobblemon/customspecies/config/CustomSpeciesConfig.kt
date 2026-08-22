package jbro.cobblemon.customspecies.config

data class CustomSpeciesConfig(
    val schema: Int,
    val overrides: List<SpeciesOverride>
)

data class SpeciesOverride(
    val species: String,
    val form: FormSelector,
    val baseStats: Map<StatKey, Int>,
    val abilities: AbilityOverride,
    val moves: MoveOverride
)

sealed interface FormSelector {
    data object Base : FormSelector
    data object All : FormSelector
    data class Named(val id: String) : FormSelector
}

enum class StatKey(val jsonName: String) {
    HP("hp"),
    ATTACK("attack"),
    DEFENCE("defence"),
    SPECIAL_ATTACK("special_attack"),
    SPECIAL_DEFENCE("special_defence"),
    SPEED("speed");

    companion object {
        fun fromJsonName(value: String): StatKey? = entries.firstOrNull { it.jsonName == value }
    }
}

data class AbilityOverride(
    val add: List<String> = emptyList(),
    val remove: List<String> = emptyList(),
    val replace: List<String>? = null
)

data class MoveOverride(
    val add: List<String> = emptyList(),
    val remove: List<String> = emptyList(),
    val removeMoves: List<String> = emptyList()
)

class ConfigValidationException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
