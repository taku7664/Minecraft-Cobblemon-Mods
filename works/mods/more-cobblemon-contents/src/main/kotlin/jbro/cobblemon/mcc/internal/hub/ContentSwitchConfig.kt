package jbro.cobblemon.mcc.internal.hub

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Path
import jbro.cobblemon.mcc.api.reload.MccConfigReloads
import jbro.cobblemon.mcc.api.reload.MccReloadResult
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.mcc.internal.reload.ConfigRead
import jbro.cobblemon.mcc.internal.reload.loadConfigFile
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.loader.api.FabricLoader

/**
 * Which contents are open, by hub content ID. A content not listed is on, so a missing file or entry changes nothing.
 * Entries of contents whose mod is not installed are kept, so removing a mod does not lose them.
 */
internal data class ContentSwitchConfig(val contents: Map<String, Boolean>) {
    fun enabled(contentId: String): Boolean = contents[contentId] ?: true

    companion object {
        /** Every content in [contentIds] on. */
        fun defaults(contentIds: Collection<String>) = ContentSwitchConfig(contentIds.associateWith { true })

        /**
         * Reads [json] over [contentIds], the contents installed now. A missing entry is on; an entry that is not
         * true or false is on too, and is reported. Malformed JSON throws.
         */
        fun read(json: String, contentIds: Collection<String>): ConfigRead<ContentSwitchConfig> {
            val root = JsonParser.parseString(json).asJsonObject
            val problems = ArrayList<String>()
            var incomplete = false
            val listed = root.get(CONTENTS)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject().also {
                if (root.has(CONTENTS)) problems += "$CONTENTS must be an object of content IDs to true or false" else incomplete = true
            }
            val contents = LinkedHashMap<String, Boolean>()
            contentIds.forEach { id ->
                val element = listed.get(id)
                contents[id] = when {
                    element == null -> true.also { incomplete = true }
                    element.isJsonPrimitive && element.asJsonPrimitive.isBoolean -> element.asBoolean
                    else -> true.also { problems += "$CONTENTS.$id must be true or false; using true" }
                }
            }
            listed.entrySet().filter { it.key !in contentIds }.forEach { (id, element) ->
                if (ManagedBattleContentIds.isValid(id) && element.isJsonPrimitive && element.asJsonPrimitive.isBoolean) {
                    contents[id] = element.asBoolean
                } else {
                    problems += "$CONTENTS.$id must be a content ID set to true or false"
                }
            }
            return ConfigRead(ContentSwitchConfig(contents), incomplete, problems)
        }

        fun write(config: ContentSwitchConfig): String {
            val contents = JsonObject().also { json -> config.contents.forEach { (id, on) -> json.addProperty(id, on) } }
            return GsonBuilder().setPrettyPrinting().create().toJson(JsonObject().also { it.add(CONTENTS, contents) }) + "\n"
        }

        private const val CONTENTS = "contents"
    }
}

/**
 * The server's content switches, from `config/more-cobblemon-contents/content_switches.json`: read at start, on
 * `/reload` and on `/mcc reload`. The listed contents come from the hub entries the installed mods registered.
 */
internal object ContentSwitchConfigFile {
    const val FILE = "content_switches.json"

    @Volatile
    var current: ContentSwitchConfig = ContentSwitchConfig(emptyMap())
        private set
    private var loaded = false

    fun register() {
        ServerLifecycleEvents.SERVER_STARTING.register { load() }
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register { server, _, success ->
            if (!success) return@register
            load()
            BattleHubNetworking.refreshOpenHubs(server)
        }
        MccConfigReloads.register(FILE) { listOf(load()) }
    }

    fun load(
        path: Path = FabricLoader.getInstance().configDir.resolve("more-cobblemon-contents").resolve(FILE),
        contentIds: Collection<String> = switchableContentIds(),
    ): MccReloadResult {
        val load = loadConfigFile(path, FILE, current.takeIf { loaded }, { ContentSwitchConfig.defaults(contentIds) },
            ContentSwitchConfig::write) { json -> ContentSwitchConfig.read(json, contentIds) }
        current = load.config
        loaded = true
        return load.result
    }

    /** The hub entries that are contents, not the hub's own screens such as the shop. */
    fun switchableContentIds(): List<String> = BattleHubEntries.all().map { it.contentId }.filter(::switchable)

    fun switchable(contentId: String): Boolean = contentId !in BattleHubIds.OWNED && BattleHubEntries.get(contentId) != null
}
