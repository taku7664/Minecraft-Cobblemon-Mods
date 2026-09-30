package jbro.cobblemon.mcc.internal.hub

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.mcc.api.terminal.HoloTerminals
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.loader.api.FabricLoader

/**
 * Which hub tabs each way into the hub shows: [command] for `/mcc`, [terminals] per hologram terminal block ID.
 * Entries for terminals whose mod is not installed are kept, so removing a mod does not lose them.
 */
internal data class BattleHubTabConfig(val command: List<String>, val terminals: Map<String, List<String>>) {
    /** A read config, and whether the file lacked entries it should be rewritten with. */
    data class Read(val config: BattleHubTabConfig, val incomplete: Boolean, val problems: List<String>)

    companion object {
        val DEFAULT_COMMAND = listOf(BattleHubIds.DASHBOARD, BattleHubIds.SHOP, ManagedBattleContentIds.PVP)

        fun defaults(terminalDefaults: Map<String, List<String>>) = BattleHubTabConfig(DEFAULT_COMMAND, terminalDefaults)

        /**
         * Reads [json] over [terminalDefaults]. A missing entry takes its default; an invalid one also does, and is
         * reported in [Read.problems]. Malformed JSON throws.
         */
        fun read(json: String, terminalDefaults: Map<String, List<String>>): Read {
            val root = JsonParser.parseString(json).asJsonObject
            val problems = ArrayList<String>()
            var incomplete = false
            fun tabs(element: JsonElement?, name: String, default: List<String>): List<String> {
                if (element == null) {
                    incomplete = true
                    return default
                }
                val parsed = parseTabs(element)
                if (parsed == null) problems += "$name must be a non-empty list of distinct content IDs; using $default"
                return parsed ?: default
            }
            val command = tabs(root.get(COMMAND), COMMAND, DEFAULT_COMMAND)
            val listed = root.get(TERMINALS)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject().also {
                if (root.has(TERMINALS)) problems += "$TERMINALS must be an object" else incomplete = true
            }
            val terminals = LinkedHashMap<String, List<String>>()
            terminalDefaults.forEach { (id, default) -> terminals[id] = tabs(listed.get(id), "$TERMINALS.$id", default) }
            listed.entrySet().filter { it.key !in terminalDefaults }.forEach { (id, element) ->
                parseTabs(element)?.let { terminals[id] = it }
            }
            return Read(BattleHubTabConfig(command, terminals), incomplete, problems)
        }

        fun write(config: BattleHubTabConfig): String {
            val root = JsonObject()
            root.add(COMMAND, array(config.command))
            root.add(TERMINALS, JsonObject().also { terminals -> config.terminals.forEach { (id, tabs) -> terminals.add(id, array(tabs)) } })
            return GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n"
        }

        private fun parseTabs(element: JsonElement): List<String>? {
            if (!element.isJsonArray) return null
            val tabs = element.asJsonArray.map { if (it.isJsonPrimitive && it.asJsonPrimitive.isString) it.asString else return null }
            return tabs.takeIf { it.isNotEmpty() && it.distinct().size == it.size && it.all(ManagedBattleContentIds::isValid) }
        }

        private fun array(values: List<String>) = JsonArray().also { array -> values.forEach(array::add) }

        private const val COMMAND = "command"
        private const val TERMINALS = "terminals"
    }
}

/** The server's hub tab config, read from `config/more-cobblemon-contents/hub_tabs.json` at start and on `/reload`. */
internal object BattleHubTabConfigFile {
    @Volatile
    var current: BattleHubTabConfig = BattleHubTabConfig.defaults(emptyMap())
        private set

    fun register() {
        ServerLifecycleEvents.SERVER_STARTING.register { load() }
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register { _, _, success -> if (success) load() }
    }

    fun load(path: Path = FabricLoader.getInstance().configDir.resolve("more-cobblemon-contents").resolve("hub_tabs.json")) {
        val defaults = HoloTerminals.all().associate { it.id.toString() to it.defaultTabs }
        current = try {
            if (Files.notExists(path)) {
                BattleHubTabConfig.defaults(defaults).also { save(path, it) }
            } else {
                val read = BattleHubTabConfig.read(Files.readString(path), defaults)
                read.problems.forEach { MoreCobblemonContents.LOGGER.warn("Hub tab config {}: {}", path, it) }
                // Only fill in missing entries; a file with mistakes is left for the admin to fix.
                if (read.incomplete && read.problems.isEmpty()) save(path, read.config)
                read.config
            }
        } catch (failure: Exception) {
            MoreCobblemonContents.LOGGER.error("Hub tab config {} could not be read; using the defaults", path, failure)
            BattleHubTabConfig.defaults(defaults)
        }
    }

    private fun save(path: Path, config: BattleHubTabConfig) {
        Files.createDirectories(path.parent)
        Files.writeString(path, BattleHubTabConfig.write(config))
    }
}
