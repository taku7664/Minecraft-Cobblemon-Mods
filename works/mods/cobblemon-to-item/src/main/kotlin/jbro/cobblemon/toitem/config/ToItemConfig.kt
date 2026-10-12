package jbro.cobblemon.toitem.config

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object ToItemConfig {
    var permissionLevel = 0
        private set
    var showTooltip = true
        private set
    private val path get() = FabricLoader.getInstance().configDir.resolve("cobblemon_to_item.json")

    fun load() {
        try {
            if (!Files.exists(path)) { save(0, true); return }
            val json = JsonParser.parseString(Files.readString(path)).asJsonObject
            val value = json.get("commandPermissionLevel")
            require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
            val level = value.asBigDecimal.intValueExact()
            require(level in 0..4)
            val enabled = json.get("showTooltip")
            require(enabled != null && enabled.isJsonPrimitive && enabled.asJsonPrimitive.isBoolean)
            permissionLevel = level
            showTooltip = enabled.asBoolean
        } catch (failure: Exception) {
            permissionLevel = 4
            showTooltip = true
            LoggerFactory.getLogger("cobblemon_to_item").warn("Invalid configuration; keeping file and requiring permission 4", failure)
        }
    }

    fun save(level: Int, tooltip: Boolean) {
        require(level in 0..4)
        val json = JsonObject().apply { addProperty("commandPermissionLevel", level); addProperty("showTooltip", tooltip) }
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, "cobblemon_to_item-", ".tmp")
        try {
            Files.writeString(temporary, json.toString() + "\n")
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            permissionLevel = level
            showTooltip = tooltip
        } finally { Files.deleteIfExists(temporary) }
    }
}
