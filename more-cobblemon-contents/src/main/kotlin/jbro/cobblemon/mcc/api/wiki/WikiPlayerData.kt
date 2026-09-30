package jbro.cobblemon.mcc.api.wiki

import com.google.gson.JsonElement
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.server.MinecraftServer

/**
 * What the server wiki's `/api/me` answers about a player, beyond the core's BP and records. A content registers a
 * section under its own [key] (for example its mod ID) and the wiki page reads `me.sections[key]`. Sections are
 * built on the server thread, so they may read saved data directly; a section that throws is left out.
 */
object WikiPlayerData {
    fun interface Section {
        fun build(server: MinecraftServer, playerId: UUID): JsonElement?
    }

    private val sections = ConcurrentHashMap<String, Section>()

    fun register(key: String, section: Section) {
        require(key.matches(Regex("[a-z0-9_.-]+"))) { "Invalid wiki section key: $key" }
        sections[key] = section
    }

    fun all(): Map<String, Section> = sections.toMap()
}
