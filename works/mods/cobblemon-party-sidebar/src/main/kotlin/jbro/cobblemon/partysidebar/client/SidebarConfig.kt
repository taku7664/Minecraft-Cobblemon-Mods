package jbro.cobblemon.partysidebar.client

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import jbro.cobblemon.partysidebar.CobblemonPartySidebar
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Local display preferences; the held-item rules stay with the server. */
internal object SidebarConfig {
    var enabled = true
        private set
    var showTooltip = true
        private set
    private val path get() = FabricLoader.getInstance().configDir.resolve("cobblemon_party_sidebar.json")

    fun load() {
        enabled = true
        showTooltip = true
        if (!Files.exists(path)) return
        try {
            val json = Files.newBufferedReader(path).use { GsonBuilder().create().fromJson(it, JsonObject::class.java) }
            enabled = json?.flag("enabled") ?: true
            showTooltip = json?.flag("showTooltip") ?: true
        } catch (failure: Exception) {
            CobblemonPartySidebar.LOGGER.warn("Could not read party sidebar settings; using defaults", failure)
        }
    }

    fun save(enabled: Boolean, showTooltip: Boolean) {
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, "cobblemon-party-sidebar-", ".tmp")
        try {
            val json = JsonObject().apply {
                addProperty("enabled", enabled)
                addProperty("showTooltip", showTooltip)
            }
            Files.newBufferedWriter(temporary).use { GsonBuilder().setPrettyPrinting().create().toJson(json, it) }
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
            this.enabled = enabled
            this.showTooltip = showTooltip
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun JsonObject.flag(name: String): Boolean? {
        val value = get(name) ?: return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "$name must be true or false" }
        return value.asBoolean
    }
}
