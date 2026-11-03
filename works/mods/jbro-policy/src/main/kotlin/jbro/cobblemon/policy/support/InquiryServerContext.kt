package jbro.cobblemon.policy.support

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.item.PokemonItem
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.pokemon.Pokemon
import com.google.gson.Gson
import jbro.cobblemon.policy.JbroPolicy
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/** Reads only the asking player's live stores, and public installed reference material. No mutation tools. */
internal object InquiryServerContext {
    private val translations: Map<String, String> by lazy {
        buildMap {
            for (mod in FabricLoader.getInstance().allMods) for (root in mod.rootPaths) {
                val assets = root.resolve("assets")
                if (!Files.isDirectory(assets)) continue
                Files.list(assets).use { namespaces -> namespaces.forEach { namespace ->
                    val file = namespace.resolve("lang/ko_kr.json")
                    runCatching {
                        if (Files.isRegularFile(file) && Files.size(file) <= 4_000_000) {
                            JsonParser.parseString(Files.readString(file)).asJsonObject.entrySet().forEach { (key, value) ->
                                if (value.isJsonPrimitive && value.asJsonPrimitive.isString) put(key, value.asString)
                            }
                        }
                    }
                } }
            }
        }
    }

    fun reference(server: MinecraftServer, gameDir: Path, question: String): String {
        val terms = InquiryReferences.terms(question, translations)
        val documents = mutableListOf<InquiryReferences.Document>()
        // Only explicit public configuration files, never Discord/auth/server.properties or arbitrary paths.
        for (relative in listOf("config/more-cobblemon-contents/bp-shop.json")) {
            val file = gameDir.resolve(relative)
            if (Files.isRegularFile(file) && Files.size(file) <= 1_000_000) {
                val json = JsonParser.parseString(Files.readString(file))
                if (relative.endsWith("bp-shop.json")) {
                    json.asJsonObject.getAsJsonArray("entries")?.forEachIndexed { index, entry ->
                        documents += InquiryReferences.Document("$relative entries[$index]", entry.toString())
                    }
                } else documents += InquiryReferences.Document(relative, json.toString())
            }
        }
        val loadedPolicy = Gson().toJsonTree(JbroPolicy.config).asJsonObject.apply {
            remove("tips")
            remove("tipIntervalSeconds")
        }
        documents += InquiryReferences.Document(PichuRules.current().text("ai.loadedPolicy"), loadedPolicy.toString())
        for (item in BuiltInRegistries.ITEM) {
            val id = BuiltInRegistries.ITEM.getKey(item).toString()
            val name = translations[item.descriptionId].orEmpty()
            if (terms.any { id.contains(it, true) || name.contains(it, true) }) {
                documents += InquiryReferences.Document("registry:item:$id", PichuRules.current().text("ai.registryItem", "id" to id, "name" to name))
            }
        }
        if (terms.isNotEmpty()) {
            for (directory in listOf("recipe", "spawn_pool_world", "loot_table", "worldgen/structure")) {
                var remaining = 8_000_000
                val resources = server.resourceManager.listResources(directory) { it.path.endsWith(".json") }
                for ((id, resource) in resources.entries.sortedWith(compareByDescending<Map.Entry<net.minecraft.resources.ResourceLocation, net.minecraft.server.packs.resources.Resource>> { entry -> terms.any { entry.key.toString().contains(it, true) } }.thenBy { it.key.toString() })) {
                    if (remaining <= 0) break
                    resource.open().use { input ->
                        val cap = minOf(remaining, 2_000_000)
                        val bytes = input.readNBytes(cap + 1)
                        remaining -= bytes.size
                        if (bytes.size > cap) return@use
                        val text = String(bytes, Charsets.UTF_8)
                        if (InquiryReferences.matches(id.toString() + " " + text, terms)) {
                            // Large spawn tables are split into individual entries so one matching species fits.
                            val json = runCatching { JsonParser.parseString(text) }.getOrNull()
                            val spawns = json?.takeIf { it.isJsonObject }?.asJsonObject?.get("spawns")?.takeIf { it.isJsonArray }?.asJsonArray
                            if (spawns != null) for (entry in spawns) {
                                documents += InquiryReferences.Document("resource:$id", entry.toString())
                            } else documents += InquiryReferences.Document("resource:$id", text)
                        }
                    }
                }
            }
        }
        return InquiryReferences.select(documents, terms)
    }

    fun player(server: MinecraftServer, playerId: UUID, question: String): String {
        val terms = InquiryReferences.terms(question, translations)
        return server.submit(Supplier {
            JsonObject().apply {
                addProperty("observed_at", Instant.now().toString())
                addProperty("scope", PichuRules.current().text("ai.playerScope"))
                val player = server.playerList.getPlayer(playerId)
                addProperty("online", player != null)
                if (player == null) return@apply
                addProperty("dimension", player.level().dimension().location().toString())
                addProperty("position", "${player.blockX},${player.blockY},${player.blockZ}")
                addProperty("day_time", player.level().dayTime % 24_000)
                addProperty("biome", player.level().getBiome(player.blockPosition()).unwrapKey().map { it.location().toString() }.orElse("unknown"))
                fun list(name: String, all: List<Pokemon>, boxes: Map<UUID, Int> = emptyMap()) {
                    addProperty(name + "_total", all.size)
                    val selected = all.sortedByDescending { pokemon -> terms.any { pokemon.species.resourceIdentifier.path.contains(it, true) } }.take(80)
                    addProperty(name + "_truncated", selected.size < all.size)
                    add(name, JsonArray().apply { selected.forEach { value -> add(pokemon(value).apply { boxes[value.uuid]?.let { addProperty("box", it) } }) } })
                }
                list("party", Cobblemon.storage.getParty(player).toList())
                runCatching {
                    val pc = Cobblemon.storage.getPC(player)
                    val boxes = pc.boxes.flatMapIndexed { index, box -> box.map { it.uuid to index + 1 } }.toMap()
                    list("pc", pc.toList(), boxes)
                }.onFailure { addProperty("pc_error", PichuRules.current().text("ai.pcError")) }
                add("inventory", JsonArray().apply {
                    for (stack in player.inventory.items + player.inventory.offhand + player.inventory.armor + (0 until player.enderChestInventory.containerSize).map { player.enderChestInventory.getItem(it) }) {
                        if (stack.isEmpty) continue
                        add(JsonObject().apply {
                            addProperty("item", BuiltInRegistries.ITEM.getKey(stack.item).toString())
                            addProperty("count", stack.count)
                            // Read the known itemized format directly; never deserialize/spawn a Pokemon.
                            val tag = stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getCompound("cobblemon_to_item")
                            if (stack.item is PokemonItem && stack.count == 1 && tag?.getInt("version") == 1) {
                                val saved = tag.getCompound("pokemon")
                                addProperty("pokemon_species", saved.getString("Species"))
                                addProperty("pokemon_form_id", saved.getString("FormId"))
                                val features = saved.getList("Features", 10)
                                for (index in 0 until features.size) {
                                    val feature = features.getCompound(index)
                                    if (feature.getString("cobblemon:feature_id") == "region_bias") {
                                        addProperty("pokemon_region_bias", feature.getString("region_bias"))
                                    }
                                }
                                if (saved.hasUUID("UUID")) addProperty("pokemon_uuid", saved.getUUID("UUID").toString())
                            }
                        })
                    }
                })
                addProperty("inventory_scope", PichuRules.current().text("ai.inventoryScope"))
                add("nearby_pokemon", JsonArray().apply {
                    val entities = player.serverLevel().getEntitiesOfClass(PokemonEntity::class.java, player.boundingBox.inflate(96.0))
                    entities.filter { it.pokemon.getOwnerUUID() == null }.sortedBy { it.distanceToSqr(player) }.take(32).forEach { entity ->
                        add(pokemon(entity.pokemon).apply {
                            addProperty("distance", entity.distanceTo(player).toInt())
                            addProperty("position", "${entity.blockX},${entity.blockY},${entity.blockZ}")
                        })
                    }
                })
                addProperty("nearby_scope", PichuRules.current().text("ai.nearbyScope"))
            }.toString()
        }).get(10, TimeUnit.SECONDS)
    }

    private fun pokemon(pokemon: Pokemon) = JsonObject().apply {
        addProperty("uuid", pokemon.uuid.toString())
        addProperty("species", pokemon.species.resourceIdentifier.toString())
        addProperty("form", pokemon.form.name)
        addProperty("level", pokemon.level)
        addProperty("shiny", pokemon.shiny)
    }
}
