package jbro.cobblemon.mcc.pwt.server

import jbro.cobblemon.mcc.pwt.MoreCobblemonContentsPwt as Mod
import jbro.cobblemon.mcc.pwt.system.PwtCatalog
import jbro.cobblemon.mcc.pwt.system.PwtCatalogParser
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.ResourceManager

/** The trainers under `data/<namespace>/pwt/trainers/`; a rejected reload keeps the previous roster. */
object PwtCatalogResources : SimpleSynchronousResourceReloadListener {
    @Volatile var current: PwtCatalog? = null
        private set

    override fun getFabricId(): ResourceLocation = ResourceLocation.fromNamespaceAndPath(Mod.MOD_ID, "pwt_catalog")
    fun register() = ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(this)
    fun clear() { current = null }

    override fun onResourceManagerReload(manager: ResourceManager) {
        try {
            val prefix = "pwt/trainers/"
            val files = manager.listResources(prefix.removeSuffix("/")) { it.path.endsWith(".json") }.map { (id, resource) ->
                "${id.namespace}:${id.path.removePrefix(prefix).removeSuffix(".json")}" to
                    resource.openAsReader().use { it.readText() }.also { require(it.length <= 65536) { "PWT trainer $id is too large" } }
            }.toMap()
            current = PwtCatalogParser.parse(files)
            Mod.LOGGER.info("Loaded {} PWT trainers", current?.trainers?.size)
        } catch (failure: RuntimeException) {
            Mod.LOGGER.error("PWT reload rejected; keeping the previous roster", failure)
        } catch (failure: java.io.IOException) {
            Mod.LOGGER.error("PWT trainer read failed; keeping the previous roster", failure)
        }
    }
}
