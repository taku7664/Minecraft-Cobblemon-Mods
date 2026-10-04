package jbro.cobblemon.customspecies.service

import jbro.cobblemon.customspecies.config.CustomSpeciesConfig

/**
 * Cobblemon rebuilds every Species from JSON before this addon's reload listener runs, so each
 * reload builds a fresh catalog and baseline instead of holding references to the old objects.
 * A rejected config reapplies the last accepted one, because the rebuild already discarded it.
 */
class OverrideReloader(private val newCatalog: () -> SpeciesCatalog) {
    sealed interface Outcome {
        data class Applied(val overrides: Int) : Outcome
        data class Rejected(val error: Throwable, val restoredOverrides: Int, val restoreError: Throwable? = null) : Outcome
    }

    private var lastAccepted: CustomSpeciesConfig? = null

    @Synchronized
    fun reload(load: () -> CustomSpeciesConfig): Outcome {
        val rejection = try {
            val config = load()
            val applied = AtomicOverrideService(newCatalog()).apply(config)
            lastAccepted = config
            return Outcome.Applied(applied)
        } catch (error: Throwable) {
            if (error is VirtualMachineError) throw error
            error
        }
        val previous = lastAccepted ?: return Outcome.Rejected(rejection, 0)
        return try {
            Outcome.Rejected(rejection, AtomicOverrideService(newCatalog()).apply(previous))
        } catch (restoreError: Throwable) {
            if (restoreError is VirtualMachineError) throw restoreError
            Outcome.Rejected(rejection, 0, restoreError)
        }
    }
}
