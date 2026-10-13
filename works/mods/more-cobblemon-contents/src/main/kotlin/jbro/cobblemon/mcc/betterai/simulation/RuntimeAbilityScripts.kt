package jbro.cobblemon.mcc.betterai.simulation

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import jbro.cobblemon.mcc.betterai.engine.hooks.EngineHooks
import jbro.cobblemon.mcc.betterai.mechanics.DeclaredAbilityScripts
import org.slf4j.LoggerFactory

/**
 * The abilities the game's Showdown runs that the AI's own dex does not have, read from their scripts
 * ([DeclaredAbilityScripts]): the installed `showdown/data/abilities.js` (which Mega Showdown rewrites) and the ability
 * scripts an addon registers at runtime (Mega Showdown's datapack ones, read where its native rules are read). The
 * AI's ported handlers stay as they are; only a damage step it has no code for is taken from here.
 */
internal object RuntimeAbilityScripts {
    private val logger = LoggerFactory.getLogger("more_cobblemon_contents/ai_engine")

    private class Snapshot(val digest: String, val abilities: Map<String, DeclaredAbilityScripts.Ability>)

    @Volatile
    private var snapshot = Snapshot("", emptyMap())

    @Volatile
    private var lastRefresh = 0L

    @Volatile
    private var fileStamp: Pair<Long, Long>? = null

    @Volatile
    private var fileText: String? = null

    /** Set by tests in place of the game's sources: the scripts by id, or null for the game's. */
    @Volatile
    internal var override: Map<String, DeclaredAbilityScripts.Ability>? = null

    /** The abilities read so far, by id. Cheap: [refresh] does the reading. */
    fun current(): Map<String, DeclaredAbilityScripts.Ability> = override ?: snapshot.abilities

    fun ability(id: String?): DeclaredAbilityScripts.Ability? = id?.let { refresh(); current()[it] }

    /**
     * Reads the sources again and returns a digest of what was read. Called once per decision with the engine's dex
     * ([EngineRuntimeDex]); a source that has not changed is not parsed again.
     */
    fun refresh(): String {
        override?.let { return "override:${it.keys.sorted().joinToString(",")}" }
        // Asked for on every dex lookup; the sources change only on a reload, so they are looked at now and then.
        val now = System.nanoTime()
        if (snapshot.digest.isNotEmpty() && now - lastRefresh < REFRESH_NANOS) return snapshot.digest
        lastRefresh = now
        val file = installedFile()
        val datapack = datapackScripts()
        val md = MessageDigest.getInstance("SHA-256")
        file?.let { md.update(it.toByteArray(Charsets.UTF_8)) }
        datapack.toSortedMap().forEach { (id, script) -> md.update(0); md.update(id.toByteArray()); md.update(0); md.update(script.toByteArray()) }
        val digest = md.digest().joinToString("") { "%02x".format(it) }.take(16)
        if (digest == snapshot.digest) return digest
        val read = synchronized(this) {
            if (digest == snapshot.digest) return digest
            val parsed = LinkedHashMap<String, DeclaredAbilityScripts.Ability>()
            file?.let { parsed.putAll(DeclaredAbilityScripts.parse(it)) }
            // Registered after the file's, so they replace an ability of the same id, as in the game's Showdown.
            datapack.forEach { (id, script) ->
                parsed.putAll(DeclaredAbilityScripts.parse(script, singleId = id.substringAfterLast('/').substringAfterLast(':').removeSuffix(".js")))
            }
            Snapshot(digest, unported(parsed)).also { snapshot = it }
        }
        logger.info("AI engine read {} runtime abilities with damage modifiers: {}", read.abilities.size, read.abilities.keys.sorted())
        return digest
    }

    /**
     * Of each ability, the handlers the AI has no port for: a ported one (the AI's own Ice Scales, Steelworker) stays
     * the port's, and an ability left with none is dropped. The AI's dex may know an ability's name and data and still
     * have no code for it (Aura Guard, 2026-10-13).
     */
    internal fun unported(abilities: Map<String, DeclaredAbilityScripts.Ability>): Map<String, DeclaredAbilityScripts.Ability> =
        abilities.mapValues { (id, ability) ->
            ability.copy(handlers = ability.handlers.filter { EngineHooks.get("ability:$id", it.step.callback) == null })
        }.filterValues { it.handlers.isNotEmpty() }

    /** The installed `showdown/data/abilities.js`, read again only when it changes. */
    private fun installedFile(): String? {
        val path = try {
            net.fabricmc.loader.api.FabricLoader.getInstance().gameDir.resolve(ABILITIES_FILE)
        } catch (_: RuntimeException) {
            return null
        } catch (_: LinkageError) {
            return null
        }
        return read(path)
    }

    private fun read(path: Path): String? = try {
        if (!Files.isRegularFile(path)) null else {
            val stamp = Files.getLastModifiedTime(path).toMillis() to Files.size(path)
            if (stamp != fileStamp || fileText == null) {
                fileText = Files.readString(path)
                fileStamp = stamp
            }
            fileText
        }
    } catch (failure: java.io.IOException) {
        logger.warn("Could not read {} for the AI engine", path, failure)
        null
    }

    /** The ability scripts an addon registers at runtime, by id; none when no such addon is loaded. */
    private fun datapackScripts(): Map<String, String> = try {
        val descriptors = MegaShowdownNativeRulesProvider.descriptors.filter { it.registry == NativeRuleRegistry.ABILITY }
        ReflectiveNativeRulesProvider(descriptors).readSources(javaClass.classLoader).associate { it.id to it.javaScript }
    } catch (_: NativeRulesUnavailableException) {
        emptyMap()
    }

    private const val REFRESH_NANOS = 5_000_000_000L
    private const val ABILITIES_FILE = "showdown/data/abilities.js"
}
