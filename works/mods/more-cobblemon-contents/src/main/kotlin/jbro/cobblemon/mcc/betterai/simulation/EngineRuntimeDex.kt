package jbro.cobblemon.mcc.betterai.simulation

import com.google.gson.JsonParser
import java.security.MessageDigest
import jbro.cobblemon.mcc.betterai.engine.dex.EngineDex
import org.slf4j.LoggerFactory

/**
 * The engine dex the game's battles actually use. Cobblemon replaces Showdown's species with its own data
 * (`receiveSpeciesData`), which is where Z-A Megas and datapack species come from, so the bundled dex is
 * overlaid with the same data read from Cobblemon's species registry. Outside the game (tests, tools) there
 * is no registry and the bundled dex is used as it is.
 */
internal object EngineRuntimeDex {
    private const val SPECIES_CLASS = "com.cobblemon.mod.common.api.pokemon.PokemonSpecies"
    private val logger = LoggerFactory.getLogger("more_cobblemon_contents/ai_engine")

    @Volatile
    private var cached: Pair<String, EngineDex>? = null

    /** The dex with the current Cobblemon species, and a digest of that species data. */
    fun current(): Pair<String, EngineDex> {
        val data = cobblemonSpecies() ?: return "bundled" to EngineDex.bundled()
        val digest = digest(data)
        cached?.takeIf { it.first == digest }?.let { return it }
        return synchronized(this) {
            cached?.takeIf { it.first == digest } ?: (digest to EngineDex.bundled().withSpecies(data.map { JsonParser.parseString(it).asJsonObject }))
                .also {
                    cached = it
                    logger.info("AI engine dex overlaid with {} Cobblemon species", data.size)
                }
        }
    }

    /**
     * The JSON Cobblemon sends Showdown for every species and form, in registration order: the internal
     * `PokemonSpecies.allShowdownSpecies`, whose compiled name carries the module suffix.
     */
    private fun cobblemonSpecies(): List<String>? {
        val type = try {
            Class.forName(SPECIES_CLASS)
        } catch (_: ClassNotFoundException) {
            return null
        } catch (_: LinkageError) {
            return null
        }
        return try {
            val method = type.methods.firstOrNull { it.name.startsWith("allShowdownSpecies") && it.parameterCount == 0 }
                ?: return null.also { logger.warn("Cobblemon has no allShowdownSpecies; the AI engine uses its bundled species") }
            val raw = method.invoke(type.getField("INSTANCE").get(null)) as? Map<*, *> ?: return null
            raw.values.mapNotNull { it as? String }.takeIf { it.isNotEmpty() }
        } catch (failure: ReflectiveOperationException) {
            logger.warn("Could not read Cobblemon species for the AI engine", failure)
            null
        }
    }

    private fun digest(data: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        data.forEach { md.update(it.toByteArray(Charsets.UTF_8)); md.update(0) }
        return md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }
}
