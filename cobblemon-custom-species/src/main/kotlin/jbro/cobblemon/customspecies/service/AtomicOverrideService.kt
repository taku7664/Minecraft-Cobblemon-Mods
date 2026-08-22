package jbro.cobblemon.customspecies.service

import jbro.cobblemon.customspecies.config.CustomSpeciesConfig

class AtomicOverrideService(private val catalog: SpeciesCatalog) {
    private val baseline = mutableMapOf<SpeciesTargetKey, SpeciesTargetState>()
    private var appliedKeys: Set<SpeciesTargetKey> = emptySet()

    @Synchronized
    fun apply(config: CustomSpeciesConfig): Int {
        val candidate = mutableMapOf<SpeciesTargetKey, SpeciesTargetState>()
        val touchedKeys = linkedSetOf<SpeciesTargetKey>()
        try {
            for (override in config.overrides) {
                val targets = catalog.resolve(override.species, override.form)
                if (targets.isEmpty()) throw OverrideApplicationException("Unknown target ${override.species}#${override.form}")
                for (entry in override.moves.add + override.moves.remove) {
                    if (!catalog.validateMove(entry)) throw OverrideApplicationException("Unknown or invalid move entry: $entry")
                }
                for (entry in override.abilities.add + override.abilities.remove + (override.abilities.replace ?: emptyList())) {
                    if (!catalog.validateAbility(entry)) throw OverrideApplicationException("Unknown or invalid ability entry: $entry")
                }
                for (target in targets) {
                    touchedKeys += target
                    val original = baseline.getOrPut(target) { catalog.read(target).deepCopy() }
                    val state = candidate[target]?.deepCopy() ?: original.deepCopy()
                    override.baseStats.forEach { (stat, amount) -> state.baseStats[stat.jsonName] = amount }
                    override.abilities.replace?.map(catalog::canonicalAbility)?.let {
                        state.abilities.clear()
                        state.abilities.addAll(it)
                    }
                    state.abilities.removeAll(override.abilities.remove.map(catalog::canonicalAbility).toSet())
                    state.abilities.addAll(override.abilities.add.map(catalog::canonicalAbility))
                    if (state.abilities.isEmpty()) throw OverrideApplicationException("Ability pool cannot be empty: $target")
                    state.moves.removeAll(override.moves.remove.map(catalog::canonicalMove).toSet())
                    if (override.moves.removeMoves.isNotEmpty()) {
                        state.moves.removeIf { moveEntry -> moveName(moveEntry) in override.moves.removeMoves }
                    }
                    state.moves.addAll(override.moves.add.map(catalog::canonicalMove))
                    candidate[target] = state
                }
            }
        } catch (error: OverrideApplicationException) {
            throw error
        } catch (error: Exception) {
            throw OverrideApplicationException("Could not build candidate snapshot", error)
        }

        val publishKeys = appliedKeys + touchedKeys
        val liveBefore = publishKeys.associateWith { catalog.read(it).deepCopy() }
        try {
            publishKeys.forEach { key ->
                catalog.write(key, (candidate[key] ?: baseline.getValue(key)).deepCopy())
            }
        } catch (error: Exception) {
            liveBefore.forEach { (key, value) -> catalog.write(key, value.deepCopy()) }
            throw OverrideApplicationException("Could not publish candidate snapshot; previous active state restored", error)
        }
        appliedKeys = touchedKeys
        return config.overrides.size
    }

    private fun moveName(entry: String): String = entry.substringAfter(':').lowercase()
}

class OverrideApplicationException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
