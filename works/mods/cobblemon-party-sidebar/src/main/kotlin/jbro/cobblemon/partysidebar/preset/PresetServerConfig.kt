package jbro.cobblemon.partysidebar.preset

import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import jbro.cobblemon.partysidebar.CobblemonPartySidebar
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

/**
 * Server settings in `config/cobblemon_party_sidebar_server.json`, read at startup.
 *
 * `consumeStoredItemsInBattle` (default true): a borrowed storage item that a battle uses up or that is stolen is gone,
 * as any held item would be. When false, battles leave borrowed items on the Pokemon.
 */
object PresetServerConfig {
    private const val FILE = "cobblemon_party_sidebar_server.json"
    private const val CONSUME = "consumeStoredItemsInBattle"

    var consumeStoredItemsInBattle = true
        private set

    /** A battle wants [pokemon]'s held item changed; false keeps a borrowed storage item where it is. */
    @JvmStatic fun battleMayChange(pokemon: BattlePokemon): Boolean =
        consumeStoredItemsInBattle || !StoredMark.isStored(pokemon.effectedPokemon.heldItem())

    fun load() {
        val path = FabricLoader.getInstance().configDir.resolve(FILE)
        try {
            if (Files.exists(path)) {
                val json = GsonBuilder().create().fromJson(Files.readString(path), JsonObject::class.java)
                if (json != null && json.has(CONSUME)) consumeStoredItemsInBattle = json.get(CONSUME).asBoolean
            }
            val out = JsonObject().apply { addProperty(CONSUME, consumeStoredItemsInBattle) }
            Files.writeString(path, GsonBuilder().setPrettyPrinting().create().toJson(out))
        } catch (failure: Exception) {
            CobblemonPartySidebar.LOGGER.warn("Could not read {}; using defaults", FILE, failure)
        }
    }
}
