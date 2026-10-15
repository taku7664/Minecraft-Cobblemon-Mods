package jbro.cobblemon.mcc.league.server

import jbro.cobblemon.mcc.league.MoreCobblemonContentsLeagueChallenge as Mod
import jbro.cobblemon.mcc.league.system.*
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.ResourceManager

object LeagueCatalogResources : SimpleSynchronousResourceReloadListener {
    private data class Loaded(val primary: LeagueCatalog, val catalogs: Map<String, LeagueCatalog>)
    @Volatile private var loaded: Loaded? = null
    val current: LeagueCatalog? get() = loaded?.primary
    val catalogs: Map<String, LeagueCatalog> get() = loaded?.catalogs.orEmpty()
    fun find(id: String): LeagueCatalog? = catalogs[id]
    @Volatile var revision: Long = 0
        private set
    /** Why the last reload was rejected, or null when it was accepted; the previous catalog stays in use. */
    @Volatile var lastFailure: String? = null
        private set
    override fun getFabricId(): ResourceLocation = ResourceLocation.fromNamespaceAndPath(Mod.MOD_ID, "league_catalog")
    fun register() = ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(this)
    fun clear() { loaded = null; revision = 0; lastFailure = null }

    override fun onResourceManagerReload(manager: ResourceManager) {
        try {
            val input = LeagueCatalogParser.directories.associateWith { directory ->
                val prefix = "league-challenge/$directory/"
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
            val all = input.getValue("leagues").keys.map { LeagueCatalogParser.parse(input, it) }
            require(all.map { it.id }.distinct().size == all.size) { "Duplicate league progress ID" }
            val byId = all.associateBy { it.id }
            require(all.all { it.unlockLeague == null || it.unlockLeague in byId && it.unlockLeague != it.id }) { "Unknown league prerequisite" }
            all.forEach { candidate ->
                val seen = mutableSetOf(candidate.id)
                var parent = candidate.unlockLeague
                while (parent != null) {
                    require(seen.add(parent)) { "Cyclic league prerequisite" }
                    parent = byId.getValue(parent).unlockLeague
                }
            }
            loaded = Loaded(next, byId)
            revision++
            lastFailure = null
            Mod.LOGGER.info("Loaded League {} at revision {}", next.id, revision)
        } catch (failure: RuntimeException) {
            lastFailure = failure.message ?: failure.javaClass.simpleName
            Mod.LOGGER.error("League reload rejected; retaining previous valid catalog", failure)
        } catch (failure: java.io.IOException) {
            lastFailure = failure.message ?: failure.javaClass.simpleName
            Mod.LOGGER.error("League resource read failed; retaining previous valid catalog", failure)
        }
    }
}
