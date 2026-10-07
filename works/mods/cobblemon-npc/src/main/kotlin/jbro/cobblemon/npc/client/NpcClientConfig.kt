package jbro.cobblemon.npc.client

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import jbro.cobblemon.npc.CobblemonNpc
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException

/** Local camera preference; NPC content remains controlled by the server. */
internal object NpcClientConfig {
    var dialogueCamera = true
        private set
    private val path get() = FabricLoader.getInstance().configDir.resolve("cobblemon_npc-client.json")

    fun load() {
        dialogueCamera = true
        if (!Files.exists(path)) return
        try {
            val json = Files.newBufferedReader(path).use { GsonBuilder().create().fromJson(it, JsonObject::class.java) }
            val value = json?.get("dialogueCamera")
            require(value == null || (value.isJsonPrimitive && value.asJsonPrimitive.isBoolean))
            dialogueCamera = value?.asBoolean ?: true
        } catch (failure: Exception) {
            CobblemonNpc.LOGGER.warn("Could not read NPC client settings; using defaults", failure)
        }
    }

    fun save(camera: Boolean) {
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, "cobblemon-npc-", ".tmp")
        try {
            val json = JsonObject().apply { addProperty("dialogueCamera", camera) }
            Files.newBufferedWriter(temporary).use { GsonBuilder().setPrettyPrinting().create().toJson(json, it) }
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
            dialogueCamera = camera
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
