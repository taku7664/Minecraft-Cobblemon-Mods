package jbro.cobblemon.morebattlecontent.betterai.state

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.Locale
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import org.slf4j.LoggerFactory

/** Marginal probability that a species carries a move; it is not a turn-choice probability. */
internal fun interface LocalMoveUsageLookup {
    fun rate(speciesId: String, formId: String?, moveId: String): Double?
}

internal data class LocalMoveUsageSource(
    val provider: String,
    val format: String,
    val month: String,
    val cutoff: Int,
    val battleCount: Int,
    val url: String,
    val rawSha256: String,
)

internal class LocalMoveUsageTable private constructor(
    val source: LocalMoveUsageSource?,
    private val ratesBySpecies: Map<String, Map<String, Double>>,
    val loaded: Boolean,
) : LocalMoveUsageLookup {
    val speciesCount: Int get() = ratesBySpecies.size

    override fun rate(speciesId: String, formId: String?, moveId: String): Double? {
        val species = canonical(speciesId)
        val form = canonical(formId.orEmpty())
        val move = canonical(moveId)
        if (species.isEmpty() || move.isEmpty()) return null
        val keys = if (form.isEmpty() || form in GENERIC_FORM_IDS) {
            listOf(species)
        } else {
            listOf(species + form, species)
        }
        return keys.firstNotNullOfOrNull { ratesBySpecies[it]?.get(move) }
    }

    companion object {
        val EMPTY = LocalMoveUsageTable(null, emptyMap(), false)

        fun parse(reader: Reader): LocalMoveUsageTable {
            val root = JsonParser.parseReader(reader).takeIf { it.isJsonObject }?.asJsonObject
                ?: throw IllegalArgumentException("Move usage snapshot root must be an object")
            require(root.requiredInt("schemaVersion") == 1) { "Unsupported move usage snapshot schema" }
            val sourceJson = root.requiredObject("source")
            val source = LocalMoveUsageSource(
                provider = sourceJson.requiredString("provider"),
                format = sourceJson.requiredString("format"),
                month = sourceJson.requiredString("month"),
                cutoff = sourceJson.requiredInt("cutoff"),
                battleCount = sourceJson.requiredInt("battleCount"),
                url = sourceJson.requiredString("url"),
                rawSha256 = sourceJson.requiredString("rawSha256"),
            )
            require(source.cutoff >= 0 && source.battleCount > 0) { "Invalid move usage source counts" }
            require(source.month.matches(Regex("\\d{4}-\\d{2}"))) { "Invalid move usage source month" }
            require(source.rawSha256.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid source SHA-256" }

            val speciesJson = root.requiredObject("species")
            val rates = linkedMapOf<String, Map<String, Double>>()
            for ((rawSpecies, movesElement) in speciesJson.entrySet().sortedBy { it.key }) {
                val species = canonical(rawSpecies)
                require(species.isNotEmpty()) { "Empty canonical species id: $rawSpecies" }
                require(species !in rates) { "Canonical species collision: $rawSpecies" }
                require(movesElement.isJsonObject) { "Move usage entry must be an object: $rawSpecies" }
                val moves = linkedMapOf<String, Double>()
                for ((rawMove, rateElement) in movesElement.asJsonObject.entrySet().sortedBy { it.key }) {
                    val move = canonical(rawMove)
                    require(move.isNotEmpty()) { "Empty canonical move id for $rawSpecies" }
                    require(move !in moves) { "Canonical move collision for $rawSpecies: $rawMove" }
                    require(rateElement.isJsonPrimitive && rateElement.asJsonPrimitive.isNumber) {
                        "Move usage rate must be numeric: $rawSpecies/$rawMove"
                    }
                    val rate = rateElement.asDouble
                    require(rate.isFinite() && rate > 0.0 && rate <= 1.0) {
                        "Move usage rate outside (0, 1]: $rawSpecies/$rawMove"
                    }
                    moves[move] = rate
                }
                require(moves.isNotEmpty()) { "Move usage entry is empty: $rawSpecies" }
                rates[species] = Collections.unmodifiableMap(moves)
            }
            require(rates.isNotEmpty()) { "Move usage snapshot has no species" }
            return LocalMoveUsageTable(source, Collections.unmodifiableMap(rates), true)
        }

        private fun JsonObject.requiredObject(name: String): JsonObject = get(name)
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: throw IllegalArgumentException("Missing object: $name")

        private fun JsonObject.requiredString(name: String): String = get(name)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Missing string: $name")

        private fun JsonObject.requiredInt(name: String): Int = get(name)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asInt
            ?: throw IllegalArgumentException("Missing integer: $name")

        private val GENERIC_FORM_IDS = setOf("normal", "default", "base", "standard")

        private fun canonical(value: String): String = value.substringAfter(':').lowercase(Locale.ROOT)
            .filter(Char::isLetterOrDigit)
    }
}

/** One fail-closed, immutable snapshot per battle format, loaded once per server process. */
internal object LocalOpponentMoveUsage {
    private const val SINGLES_RESOURCE =
        "/data/cobblemon_more_battle_content_better_ai/opponent_move_usage/gen9bssregj-2025-12-1500.json"
    private const val DOUBLES_RESOURCE =
        "/data/cobblemon_more_battle_content_better_ai/opponent_move_usage/gen9vgc2025regj-2025-12-1500.json"
    private val logger = LoggerFactory.getLogger("cobblemon_more_battle_content_better_ai")

    private val singlesTable: LocalMoveUsageTable by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        load(SINGLES_RESOURCE, "singles")
    }
    private val doublesTable: LocalMoveUsageTable by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        load(DOUBLES_RESOURCE, "doubles")
    }

    fun forFormat(format: BattleFormat): LocalMoveUsageTable = when (format) {
        BattleFormat.SINGLE -> singlesTable
        BattleFormat.DOUBLE -> doublesTable
    }

    private fun load(resource: String, formatLabel: String): LocalMoveUsageTable {
        val stream = LocalOpponentMoveUsage::class.java.getResourceAsStream(resource)
        if (stream == null) {
            logger.warn(
                "Better AI {} opponent move usage snapshot is missing; hidden move branches stay unknown",
                formatLabel,
            )
            return LocalMoveUsageTable.EMPTY
        } else {
            return try {
                stream.use { input ->
                    InputStreamReader(input, StandardCharsets.UTF_8).use(LocalMoveUsageTable::parse)
                }
            } catch (exception: RuntimeException) {
                logger.warn(
                    "Better AI {} opponent move usage snapshot is invalid; hidden move branches stay unknown: {}",
                    formatLabel,
                    exception.javaClass.name,
                )
                LocalMoveUsageTable.EMPTY
            }
        }
    }
}
