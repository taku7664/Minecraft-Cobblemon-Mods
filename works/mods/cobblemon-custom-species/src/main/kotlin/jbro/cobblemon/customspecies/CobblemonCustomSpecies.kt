package jbro.cobblemon.customspecies

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.minecraft.server.packs.PackType
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object CobblemonCustomSpecies : ModInitializer {
    const val MOD_ID = "cobblemon_custom_species"
    val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)

    override fun onInitialize() {
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(CustomSpeciesReloadListener())
        ServerLifecycleEvents.SERVER_STARTING.register { CustomSpeciesReloadController.server = it }
        ServerLifecycleEvents.SERVER_STOPPED.register { server ->
            if (CustomSpeciesReloadController.server === server) CustomSpeciesReloadController.server = null
        }
        CustomSpeciesCommands.register()
    }
}
