package jbro.cobblemon.customspecies

import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager

class CustomSpeciesReloadListener : SimpleSynchronousResourceReloadListener {
    override fun getFabricId(): ResourceLocation = LISTENER_ID

    override fun getFabricDependencies(): Collection<ResourceLocation> = listOf(COBBLEMON_DATA_ID)

    override fun onResourceManagerReload(resourceManager: ResourceManager) {
        CustomSpeciesReloadController.reload()
    }

    private companion object {
        val LISTENER_ID = ResourceLocation.fromNamespaceAndPath(CobblemonCustomSpecies.MOD_ID, "species_overrides")
        val COBBLEMON_DATA_ID = ResourceLocation.fromNamespaceAndPath("cobblemon", "data_resources")
    }
}
