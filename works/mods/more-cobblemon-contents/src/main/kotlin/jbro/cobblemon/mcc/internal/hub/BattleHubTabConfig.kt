package jbro.cobblemon.mcc.internal.hub

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Path
import jbro.cobblemon.mcc.api.reload.MccConfigReloads
import jbro.cobblemon.mcc.api.reload.MccReloadResult
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.mcc.api.terminal.HoloTerminals
import jbro.cobblemon.mcc.internal.reload.ConfigRead
import jbro.cobblemon.mcc.internal.reload.loadConfigFile
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.loader.api.FabricLoader

/**
 * Which hub tabs each way into the hub shows: [command] for `/mcc`, [terminals] per hologram terminal block ID.
 * [commandPermission] is the permission level `/mcc` needs to open the hub: 0 lets every player, 2 only operators;
 * targeting another player and the administrative commands still require operator permission.
 * Entries for terminals whose mod is not installed are kept, so removing a mod does not lose them.
 */
internal data class BattleHubTabConfig(
    val command: List<String>,
    val terminals: Map<String, List<String>>,
    val commandPermission: Int = DEFAULT_COMMAND_PERMISSION,
) {
    fun terminalTabs(id: String, defaults: List<String>): List<String> = terminals[id] ?: defaults

    /** A read config, and whether the file lacked entries it should be rewritten with. */
    data class Read(val config: BattleHubTabConfig, val incomplete: Boolean, val problems: List<String>)

    companion object {
        val DEFAULT_COMMAND = listOf(BattleHubIds.DASHBOARD, BattleHubIds.SHOP, ManagedBattleContentIds.PVP)
        const val DEFAULT_COMMAND_PERMISSION = 0

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
            val permission = when (val element = root.get(COMMAND_PERMISSION)) {
                null -> DEFAULT_COMMAND_PERMISSION.also { incomplete = true }
                else -> element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
                    ?.takeIf { it % 1.0 == 0.0 && it in 0.0..4.0 }?.toInt()
                    ?: DEFAULT_COMMAND_PERMISSION.also { problems += "$COMMAND_PERMISSION must be a whole number from 0 to 4; using $it" }
            }
            val listed = root.get(TERMINALS)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject().also {
                if (root.has(TERMINALS)) problems += "$TERMINALS must be an object" else incomplete = true
            }
            val terminals = LinkedHashMap<String, List<String>>()
            terminalDefaults.forEach { (id, default) -> terminals[id] = tabs(listed.get(id), "$TERMINALS.$id", default) }
            listed.entrySet().filter { it.key !in terminalDefaults }.forEach { (id, element) ->
                parseTabs(element)?.let { terminals[id] = it }
            }
            return Read(BattleHubTabConfig(command, terminals, permission), incomplete, problems)
        }

        fun write(config: BattleHubTabConfig): String {
            val root = JsonObject()
            root.add(COMMAND, array(config.command))
            root.addProperty(COMMAND_PERMISSION, config.commandPermission)
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
        private const val COMMAND_PERMISSION = "command_permission_level"
        private const val TERMINALS = "terminals"
    }
}

/**
 * The server's hub tab config, read from `config/more-cobblemon-contents/hub_tabs.json` at start, on `/reload` and on
 * `/mcc reload`. A file with mistakes keeps the tabs in use; see [loadConfigFile].
 */
internal object BattleHubTabConfigFile {
    const val FILE = "hub_tabs.json"

    @Volatile
    var current: BattleHubTabConfig = BattleHubTabConfig.defaults(emptyMap())
        private set
    private var loaded = false

    fun register() {
        ServerLifecycleEvents.SERVER_STARTING.register { load() }
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register { server, _, success ->
            if (!success) return@register
            load()
            // Who may run `/mcc` can change with the file; resend the tree so clients offer the command as it now is.
            server.playerList.players.forEach(server.commands::sendCommands)
        }
        // `/mcc reload` resends the command tree itself, once for every file.
        MccConfigReloads.register(FILE) { listOf(load()) }
    }

    fun load(path: Path = FabricLoader.getInstance().configDir.resolve("more-cobblemon-contents").resolve(FILE)): MccReloadResult {
        val defaults = HoloTerminals.all().associate { it.id.toString() to it.defaultTabs }
        val load = loadConfigFile(path, FILE, current.takeIf { loaded }, { BattleHubTabConfig.defaults(defaults) }, BattleHubTabConfig::write) { json ->
            BattleHubTabConfig.read(json, defaults).let { ConfigRead(it.config, it.incomplete, it.problems) }
        }
        current = load.config
        loaded = true
        return load.result
    }
}
