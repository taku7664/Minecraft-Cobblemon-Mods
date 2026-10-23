package jbro.cobblemon.mcc.betterai.simulation

import com.google.gson.JsonParser
import java.security.MessageDigest
import java.util.HexFormat
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

    /**
     * Outside the game only (no Cobblemon registry): species JSON in Showdown's form to overlay the same way. Tests read
     * Mega Showdown's species additions here so a Z-A Mega exists as it does in the game; the game never sets it.
     */
    @Volatile
    internal var outsideGameSpecies: (() -> List<String>?)? = null

    private class DecisionSnapshot(var dex: Pair<String, EngineDex>? = null)
    private val decisionSnapshot = ThreadLocal<DecisionSnapshot?>()

    /** A reload is observed at the next decision; every lookup within this one reads the same species generation. */
    fun <T> withDecisionSnapshot(block: () -> T): T {
        val previous = decisionSnapshot.get()
        decisionSnapshot.set(DecisionSnapshot())
        try { return block() } finally {
            if (previous == null) decisionSnapshot.remove() else decisionSnapshot.set(previous)
        }
    }

    /**
     * The dex with the current Cobblemon species and the runtime abilities ([RuntimeAbilityScripts]), and a digest of
     * that data ("bundled" when there is none).
     */
    fun current(): Pair<String, EngineDex> {
        val snapshot = decisionSnapshot.get()
        snapshot?.dex?.let { return it }
        return readCurrent().also { snapshot?.dex = it }
    }

    private fun readCurrent(): Pair<String, EngineDex> {
        val abilityDigest = RuntimeAbilityScripts.refresh()
        val abilities = RuntimeAbilityScripts.current()
        val data = cobblemonSpecies() ?: outsideGameSpecies?.invoke()
        if (data == null && abilities.isEmpty()) return "bundled" to EngineDex.bundled()
        val digest = (data?.let(::digest) ?: "bundled") + if (abilities.isEmpty()) "" else "+abilities=$abilityDigest"
        cached?.takeIf { it.first == digest }?.let { return it }
        return synchronized(this) {
            cached?.takeIf { it.first == digest } ?: run {
                var dex = EngineDex.bundled()
                if (data != null) dex = dex.withSpecies(data.map { JsonParser.parseString(it).asJsonObject })
                if (abilities.isNotEmpty()) dex = dex.withAbilities(abilities.values)
                (digest to dex).also {
                    cached = it
                    logger.info("AI engine dex overlaid with {} Cobblemon species and {} runtime abilities", data?.size ?: 0, abilities.size)
                }
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
        return HexFormat.of().formatHex(md.digest(), 0, 8)
    }
}
