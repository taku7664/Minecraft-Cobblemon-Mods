package jbro.cobblemon.customspecies.service

import jbro.cobblemon.customspecies.config.FormSelector

data class SpeciesTargetState(
    val baseStats: MutableMap<String, Int>,
    val abilities: MutableSet<String>,
    val moves: MutableSet<String>
) {
    fun deepCopy(): SpeciesTargetState = SpeciesTargetState(baseStats.toMutableMap(), abilities.toMutableSet(), moves.toMutableSet())
}

data class SpeciesTargetKey(val species: String, val form: String)

interface SpeciesCatalog {
    fun resolve(species: String, selector: FormSelector): List<SpeciesTargetKey>
    fun read(key: SpeciesTargetKey): SpeciesTargetState
    fun write(key: SpeciesTargetKey, state: SpeciesTargetState)
    fun validateMove(entry: String): Boolean = true
    fun validateAbility(entry: String): Boolean = true
    fun canonicalMove(entry: String): String = entry
    fun canonicalAbility(entry: String): String = entry
}
