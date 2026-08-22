package jbro.cobblemon.customspecies

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import jbro.cobblemon.customspecies.compat.CobblemonSpeciesCatalog
import jbro.cobblemon.customspecies.config.CustomSpeciesConfigParser
import jbro.cobblemon.customspecies.service.AtomicOverrideService
import net.minecraft.server.MinecraftServer

object CustomSpeciesReloadController {
    data class Status(val successful: Boolean, val appliedOverrides: Int, val message: String)

    @Volatile
    var server: MinecraftServer? = null

    @Volatile
    var status = Status(false, 0, "Not loaded yet")
        private set

    private var service: AtomicOverrideService? = null
    private val parser = CustomSpeciesConfigParser()

    @Synchronized
    fun reload() {
        try {
            val candidate = parser.parse(CustomSpeciesConfigFile.readOrCreate())
            val activeService = service ?: AtomicOverrideService(CobblemonSpeciesCatalog()).also { service = it }
            val applied = activeService.apply(candidate)
            server?.playerList?.players?.forEach(PokemonSpecies::sync)
            status = Status(true, applied, "Applied $applied override(s)")
            CobblemonCustomSpecies.LOGGER.info(
                "Applied {} custom species override(s) from {}",
                applied,
                CustomSpeciesConfigFile.path
            )
        } catch (error: Throwable) {
            if (error is VirtualMachineError) throw error
            status = Status(false, status.appliedOverrides, error.message ?: error.javaClass.simpleName)
            CobblemonCustomSpecies.LOGGER.error(
                "Rejected custom species config at {}; keeping the previous active state: {}",
                CustomSpeciesConfigFile.path,
                error.message,
                error
            )
        }
    }
}
