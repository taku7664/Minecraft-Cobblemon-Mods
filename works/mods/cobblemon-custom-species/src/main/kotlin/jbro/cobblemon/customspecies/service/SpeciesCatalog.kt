package jbro.cobblemon.customspecies.service

import jbro.cobblemon.customspecies.config.FormSelector

data class SpeciesTargetState(
    val baseStats: MutableMap<String, Int>,
    val abilities: MutableSet<String>,
    val moves: MutableSet<String>
) {
    fun deepCopy(): SpeciesTargetState = SpeciesTargetState(baseStats.toMutableMap(), abilities.toMutableSet(), moves.toMutableSet())

    fun changedFields(from: SpeciesTargetState): Set<TargetField> = buildSet {
        if (baseStats != from.baseStats) add(TargetField.BASE_STATS)
        if (abilities != from.abilities) add(TargetField.ABILITIES)
        if (moves != from.moves) add(TargetField.MOVES)
    }
}

data class SpeciesTargetKey(val species: String, val form: String)

enum class TargetField {
    BASE_STATS,
    ABILITIES,
    MOVES;

    companion object {
        val ALL: Set<TargetField> = entries.toSet()
    }
}

interface SpeciesCatalog {
    fun resolve(species: String, selector: FormSelector): List<SpeciesTargetKey>
    fun read(key: SpeciesTargetKey): SpeciesTargetState

    /** Writes only [fields], so a form keeps inheriting every value the config did not change. */
    fun write(key: SpeciesTargetKey, state: SpeciesTargetState, fields: Set<TargetField> = TargetField.ALL)
    fun validateMove(entry: String): Boolean = true
    fun validateMoveName(name: String): Boolean = true
    fun validateAbility(entry: String): Boolean = true
    fun canonicalMove(entry: String): String = entry
    fun canonicalAbility(entry: String): String = entry
}
