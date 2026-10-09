package jbro.cobblemon.mcc.league.trainer

import com.cobblemon.mod.common.api.spawning.SpawnLoader
import com.cobblemon.mod.common.api.spawning.detail.NPCSpawnDetail
import com.cobblemon.mod.common.api.spawning.detail.SpawnDetail
import com.cobblemon.mod.common.api.spawning.position.SpawnablePosition
import com.cobblemon.mod.common.entity.npc.NPCEntity
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import jbro.cobblemon.mcc.league.MoreCobblemonContentsLeagueChallenge as Mod
import kotlin.random.Random
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.ResourceManager

/**
 * Which wild NPC turns up when the world spawns one. The world's spawn pool holds one stand-in entry per group (the
 * battling trainers, the role NPCs), so how often a group appears is that entry's weight alone and does not grow with
 * the number of kinds in it. When a stand-in spawns, it becomes one of its group's kinds whose own conditions (biome,
 * time of day, ...) hold at that spot, picked by the kinds' weights, before it joins the world.
 *
 * The kinds are listed in `league-challenge/wild_spawns/<group>.json` as `{"placeholder": <NPC class>, "spawns": [...]}`,
 * each spawn written like a Cobblemon spawn pool entry; its `bucket` only scales its weight ([BUCKET_SHARE]).
 */
object WildNpcSpawns {
    /**
     * A kind's weight within its group by the bucket it was written for, as the world draws those buckets
     * (uncommon 5, rare 0.5): a rare kind turns up a tenth as often as an uncommon one of the same weight.
     */
    private val BUCKET_SHARE = mapOf("common" to 1f, "uncommon" to 1f, "rare" to 0.1f, "ultra-rare" to 0.04f)

    /** The group files by stand-in class; their spawn details are made on first use, once a server is there. */
    @Volatile
    private var groups: Map<String, List<JsonObject>> = emptyMap()
    @Volatile
    private var details: Map<String, List<SpawnDetail>>? = null

    fun register() {
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(Resources)
    }

    fun isPlaceholder(npc: NPCEntity): Boolean = npc.npc.id.toString() in groups

    /**
     * Turns the stand-in [npc] into a kind of its group that may spawn at [position] and that [accept]s it once its
     * class is set, trying the kinds in weighted random order. False when none may, and nothing should spawn.
     */
    fun become(npc: NPCEntity, position: SpawnablePosition, accept: (NPCEntity) -> Boolean): Boolean {
        val group = loaded(position.world.server)[npc.npc.id.toString()] ?: return false
        val candidates = group.filter { detail -> runCatching { detail.isSatisfiedBy(position) }.getOrDefault(false) }
            .map { it to it.weight * (BUCKET_SHARE[it.bucket] ?: 1f) }
            .filter { it.second > 0f }
            .toMutableList()
        while (candidates.isNotEmpty()) {
            val (detail, _) = candidates.removeAt(pick(candidates))
            npc.npc = (detail as NPCSpawnDetail).npcClass
            if (accept(npc)) {
                npc.initialize(1)
                return true
            }
        }
        return false
    }

    private fun pick(weighted: List<Pair<SpawnDetail, Float>>): Int {
        var roll = Random.nextFloat() * weighted.sumOf { it.second.toDouble() }.toFloat()
        weighted.forEachIndexed { index, (_, weight) ->
            if (roll < weight) return index
            roll -= weight
        }
        return weighted.lastIndex
    }

    private fun loaded(server: MinecraftServer): Map<String, List<SpawnDetail>> = details ?: synchronized(this) {
        details ?: groups.mapValues { (placeholder, spawns) ->
            spawns.mapNotNull { json ->
                try {
                    SpawnLoader.gson.fromJson(json, SpawnDetail::class.java)
                        ?.takeIf { it is NPCSpawnDetail && it.isModDependencySatisfied() }
                        ?.also { it.onServerLoad(server) }
                } catch (failure: RuntimeException) {
                    Mod.LOGGER.error("Wild NPC spawn {} in group {} could not be read", json.get("id"), placeholder, failure)
                    null
                }
            }
        }.also { details = it }
    }

    private object Resources : SimpleSynchronousResourceReloadListener {
        override fun getFabricId(): ResourceLocation = ResourceLocation.fromNamespaceAndPath(Mod.MOD_ID, "wild_spawns")

        override fun onResourceManagerReload(manager: ResourceManager) {
            try {
                val read = manager.listResources("league-challenge/wild_spawns") { it.path.endsWith(".json") }.values.map { resource ->
                    resource.openAsReader().use { JsonParser.parseReader(it).asJsonObject }
                }
                require(read.map { it.get("placeholder").asString }.distinct().size == read.size) { "Two wild spawn groups share a placeholder" }
                groups = read.associate { file -> file.get("placeholder").asString to file.getAsJsonArray("spawns").map { it.asJsonObject } }
                details = null
                Mod.LOGGER.info("Loaded wild NPC spawn groups: {}", groups.mapValues { it.value.size })
            } catch (failure: RuntimeException) {
                Mod.LOGGER.error("Wild NPC spawn reload rejected; keeping the previous groups", failure)
            }
        }
    }
}
