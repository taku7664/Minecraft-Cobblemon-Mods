package jbro.cobblemon.morebattlecontent.leaguechallenge.server

import jbro.cobblemon.morebattlecontent.leaguechallenge.MoreBattleContentLeagueChallenge as Mod
import jbro.cobblemon.morebattlecontent.leaguechallenge.system.*
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.ResourceManager

object LeagueCatalogResources : SimpleSynchronousResourceReloadListener {
    @Volatile var current: LeagueCatalog? = null
        private set
    @Volatile var revision: Long = 0
        private set
    override fun getFabricId(): ResourceLocation = ResourceLocation.fromNamespaceAndPath(Mod.MOD_ID, "league_catalog")
    fun register() = ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(this)
    fun clear() { current = null; revision = 0 }

    override fun onResourceManagerReload(manager: ResourceManager) {
        try {
            val input = LeagueCatalogParser.directories.associateWith { directory ->
                val prefix = "mbc-league-challenge/$directory/"
                manager.listResources(prefix.removeSuffix("/")) { it.path.endsWith(".json") }.map { (id, resource) ->
                    val key = "${id.namespace}:${id.path.removePrefix(prefix).removeSuffix(".json")}"
                    key to resource.openAsReader().use { reader ->
                        val buffer = CharArray(262145)
                        var count = 0
                        while (count < buffer.size) {
                            val read = reader.read(buffer, count, buffer.size - count)
                            if (read < 0) break
                            count += read
                        }
                        require(count <= 262144) { "League resource $id exceeds size limit" }
                        String(buffer, 0, count)
                    }
                }.toMap()
            }
            val next = LeagueCatalogParser.parse(input, "${Mod.MOD_ID}:active")
            current = next
            revision++
            Mod.LOGGER.info("Loaded League {} at revision {}", next.id, revision)
        } catch (failure: RuntimeException) {
            Mod.LOGGER.error("League reload rejected; retaining previous valid catalog", failure)
        } catch (failure: java.io.IOException) {
            Mod.LOGGER.error("League resource read failed; retaining previous valid catalog", failure)
        }
    }
}
