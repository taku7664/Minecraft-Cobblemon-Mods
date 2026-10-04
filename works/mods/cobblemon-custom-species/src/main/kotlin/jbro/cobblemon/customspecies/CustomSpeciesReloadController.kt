package jbro.cobblemon.customspecies

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import jbro.cobblemon.customspecies.compat.CobblemonSpeciesCatalog
import jbro.cobblemon.customspecies.config.CustomSpeciesConfigParser
import jbro.cobblemon.customspecies.service.OverrideReloader
import net.minecraft.server.MinecraftServer

object CustomSpeciesReloadController {
    data class Status(val successful: Boolean, val appliedOverrides: Int, val message: String)

    @Volatile
    var server: MinecraftServer? = null

    @Volatile
    var status = Status(false, 0, "Not loaded yet")
        private set

    private val reloader = OverrideReloader { CobblemonSpeciesCatalog() }
    private val parser = CustomSpeciesConfigParser()

    @Synchronized
    fun reload() {
        when (val outcome = reloader.reload { parser.parse(CustomSpeciesConfigFile.readOrCreate()) }) {
            is OverrideReloader.Outcome.Applied -> {
                status = Status(true, outcome.overrides, "Applied ${outcome.overrides} override(s)")
                CobblemonCustomSpecies.LOGGER.info(
                    "Applied {} custom species override(s) from {}",
                    outcome.overrides,
                    CustomSpeciesConfigFile.path
                )
            }
            is OverrideReloader.Outcome.Rejected -> {
                val reason = outcome.error.message ?: outcome.error.javaClass.simpleName
                val kept = when {
                    outcome.restoreError != null -> "could not restore the last accepted config, no overrides are active"
                    outcome.restoredOverrides == 0 -> "no overrides are active"
                    else -> "kept the last accepted config (${outcome.restoredOverrides} override(s))"
                }
                status = Status(false, outcome.restoredOverrides, "$reason; $kept")
                CobblemonCustomSpecies.LOGGER.error(
                    "Rejected custom species config at {}; {}: {}",
                    CustomSpeciesConfigFile.path,
                    kept,
                    reason,
                    outcome.error
                )
                outcome.restoreError?.let {
                    CobblemonCustomSpecies.LOGGER.error("Could not restore the last accepted custom species config", it)
                }
            }
        }
        server?.playerList?.players?.forEach(PokemonSpecies::sync)
    }
}
