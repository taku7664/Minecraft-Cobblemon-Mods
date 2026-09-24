package jbro.cobblemon.morebattlecontent.betterai.state

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.Locale
import kotlin.math.abs
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import org.slf4j.LoggerFactory

internal data class LocalOpponentBuildUsageSource(
    val provider: String,
    val format: String,
    val month: String,
    val cutoff: Int,
    val battleCount: Int,
    val url: String,
    val rawSha256: String,
)

internal data class LocalOpponentSpreadUsage(
    val natureId: String,
    val evs: Map<String, Int>,
    val rate: Double,
)

/** Independent public marginals. They are priors, not evidence that fields co-occurred on one set. */
internal data class LocalOpponentBuildUsageEntry(
    val abilityRates: Map<String, Double>,
    val itemRates: Map<String, Double>,
    val noItemRate: Double,
    val spreads: List<LocalOpponentSpreadUsage>,
    val unresolvedSpreadRate: Double,
    val teraTypeRates: Map<String, Double>,
)

internal fun interface LocalOpponentBuildUsageLookup {
    fun forPokemon(speciesId: String, formId: String?): LocalOpponentBuildUsageEntry?
}

internal class LocalOpponentBuildUsageTable private constructor(
    val source: LocalOpponentBuildUsageSource?,
    private val usageBySpecies: Map<String, LocalOpponentBuildUsageEntry>,
    val loaded: Boolean,
) : LocalOpponentBuildUsageLookup {
    val speciesCount: Int get() = usageBySpecies.size

    override fun forPokemon(speciesId: String, formId: String?): LocalOpponentBuildUsageEntry? {
        val species = canonical(speciesId)
        val form = canonical(formId.orEmpty())
        if (species.isEmpty()) return null
        val keys = if (form.isEmpty() || form in GENERIC_FORM_IDS) {
            listOf(species)
        } else {
            listOf(species + form, species)
        }
        return keys.firstNotNullOfOrNull(usageBySpecies::get)
    }

    companion object {
        val EMPTY = LocalOpponentBuildUsageTable(null, emptyMap(), false)

        fun parse(reader: Reader): LocalOpponentBuildUsageTable {
            val root = JsonParser.parseReader(reader).takeIf { it.isJsonObject }?.asJsonObject
                ?: throw IllegalArgumentException("Build usage snapshot root must be an object")
            require(root.requiredInt("schemaVersion") == 1) { "Unsupported build usage snapshot schema" }
            val sourceJson = root.requiredObject("source")
            val source = LocalOpponentBuildUsageSource(
                provider = sourceJson.requiredString("provider"),
                format = sourceJson.requiredString("format"),
                month = sourceJson.requiredString("month"),
                cutoff = sourceJson.requiredInt("cutoff"),
                battleCount = sourceJson.requiredInt("battleCount"),
                url = sourceJson.requiredString("url"),
                rawSha256 = sourceJson.requiredString("rawSha256"),
            )
            require(source.cutoff >= 0 && source.battleCount > 0) { "Invalid build usage source counts" }
            require(source.month.matches(Regex("\\d{4}-\\d{2}"))) { "Invalid build usage source month" }
            require(source.rawSha256.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid source SHA-256" }

            val usage = linkedMapOf<String, LocalOpponentBuildUsageEntry>()
            for ((rawSpecies, element) in root.requiredObject("species").entrySet().sortedBy { it.key }) {
                val species = canonical(rawSpecies)
                require(species.isNotEmpty()) { "Empty canonical species id: $rawSpecies" }
                require(species !in usage) { "Canonical species collision: $rawSpecies" }
                require(element.isJsonObject) { "Build usage entry must be an object: $rawSpecies" }
                val entry = element.asJsonObject
                val abilities = entry.requiredDistribution("abilities", rawSpecies)
                val rawItems = entry.requiredDistribution("items", rawSpecies)
                val noItemRate = rawItems[NO_ITEM_ID] ?: 0.0
                val items = rawItems.filterKeys { it != NO_ITEM_ID }
                val teraTypes = entry.requiredDistribution("teraTypes", rawSpecies)
                val unresolvedSpreadRate = entry.requiredProbability("unresolvedSpreadRate")
                val spreads = parseSpreads(entry.requiredObject("spreads"), rawSpecies, unresolvedSpreadRate)
                usage[species] = LocalOpponentBuildUsageEntry(
                    abilityRates = immutableMap(abilities),
                    itemRates = immutableMap(items),
                    noItemRate = noItemRate,
                    spreads = Collections.unmodifiableList(spreads),
                    unresolvedSpreadRate = unresolvedSpreadRate,
                    teraTypeRates = immutableMap(teraTypes),
                )
            }
            require(usage.isNotEmpty()) { "Build usage snapshot has no species" }
            return LocalOpponentBuildUsageTable(source, Collections.unmodifiableMap(usage), true)
        }

        private fun parseSpreads(
            spreads: JsonObject,
            species: String,
            unresolvedRate: Double,
        ): List<LocalOpponentSpreadUsage> {
            val parsed = mutableListOf<LocalOpponentSpreadUsage>()
            val canonicalSpreads = hashSetOf<String>()
            for ((rawSpread, rateElement) in spreads.entrySet().sortedBy { it.key }) {
                val match = SPREAD_PATTERN.matchEntire(rawSpread)
                    ?: throw IllegalArgumentException("Invalid spread for $species: $rawSpread")
                val nature = canonical(match.groupValues[1])
                require(nature in NATURE_IDS) { "Invalid nature for $species: ${match.groupValues[1]}" }
                val evValues = match.groupValues.drop(2).map(String::toInt)
                require(evValues.all { it in 0..252 } && evValues.sum() <= 510) {
                    "Invalid EV spread for $species: $rawSpread"
                }
                val spreadKey = "$nature:${evValues.joinToString("/")}"
                require(canonicalSpreads.add(spreadKey)) { "Canonical spread collision for $species: $rawSpread" }
                parsed += LocalOpponentSpreadUsage(
                    natureId = nature,
                    evs = immutableMap(STAT_IDS.zip(evValues).toMap()),
                    rate = rateElement.requiredRate("$species.spreads.$rawSpread"),
                )
            }
            require(parsed.isNotEmpty()) { "Spread distribution is empty: $species" }
            requireNormalized(parsed.sumOf(LocalOpponentSpreadUsage::rate) + unresolvedRate, "$species.spreads")
            return parsed.sortedWith(compareByDescending<LocalOpponentSpreadUsage> { it.rate }
                .thenBy { it.natureId }
                .thenBy { spread -> STAT_IDS.joinToString("/") { spread.evs.getValue(it).toString() } })
        }

        private fun JsonObject.requiredDistribution(name: String, species: String): Map<String, Double> {
            val result = linkedMapOf<String, Double>()
            for ((rawId, rateElement) in requiredObject(name).entrySet().sortedBy { it.key }) {
                val id = canonical(rawId)
                require(id.isNotEmpty()) { "Empty canonical id: $species.$name.$rawId" }
                require(id !in result) { "Canonical id collision: $species.$name.$rawId" }
                result[id] = rateElement.requiredRate("$species.$name.$rawId")
            }
            require(result.isNotEmpty()) { "Distribution is empty: $species.$name" }
            requireNormalized(result.values.sum(), "$species.$name")
            return result.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }
                .thenBy { it.key }).associateTo(linkedMapOf()) { it.key to it.value }
        }

        private fun com.google.gson.JsonElement.requiredRate(label: String): Double {
            require(isJsonPrimitive && asJsonPrimitive.isNumber) { "Build usage rate must be numeric: $label" }
            return asDouble.also { rate ->
                require(rate.isFinite() && rate > 0.0 && rate <= 1.0) {
                    "Build usage rate outside (0, 1]: $label"
                }
            }
        }

        private fun JsonObject.requiredProbability(name: String): Double {
            val element = get(name)
                ?: throw IllegalArgumentException("Missing probability: $name")
            require(element.isJsonPrimitive && element.asJsonPrimitive.isNumber) {
                "Build usage probability must be numeric: $name"
            }
            return element.asDouble.also { probability ->
                require(probability.isFinite() && probability in 0.0..1.0) {
                    "Build usage probability outside [0, 1]: $name"
                }
            }
        }

        private fun requireNormalized(sum: Double, label: String) {
            require(abs(sum - 1.0) <= DISTRIBUTION_TOLERANCE) {
                "Build usage distribution must sum to one: $label=$sum"
            }
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

        private fun <K, V> immutableMap(source: Map<K, V>): Map<K, V> =
            Collections.unmodifiableMap(LinkedHashMap(source))

        private fun canonical(value: String): String = value.substringAfter(':')
            .lowercase(Locale.ROOT)
            .filter(Char::isLetterOrDigit)

        private const val NO_ITEM_ID = "nothing"
        private const val DISTRIBUTION_TOLERANCE = 0.00000002
        private val GENERIC_FORM_IDS = setOf("normal", "default", "base", "standard")
        private val STAT_IDS = listOf("hp", "atk", "def", "spa", "spd", "spe")
        private val NATURE_IDS = setOf(
            "adamant", "bashful", "bold", "brave", "calm", "careful", "docile", "gentle", "hardy",
            "hasty", "impish", "jolly", "lax", "lonely", "mild", "modest", "naive", "naughty",
            "quiet", "quirky", "rash", "relaxed", "sassy", "serious", "timid",
        )
        private val SPREAD_PATTERN = Regex("^([^:]+):(\\d+)/(\\d+)/(\\d+)/(\\d+)/(\\d+)/(\\d+)$")
    }
}

/** One fail-closed, immutable build-prior snapshot per battle format. */
internal object LocalOpponentBuildUsage {
    private const val SINGLES_RESOURCE =
        "/data/cobblemon_more_battle_content_better_ai/opponent_build_usage/gen9bssregj-2025-12-1500.json"
    private const val DOUBLES_RESOURCE =
        "/data/cobblemon_more_battle_content_better_ai/opponent_build_usage/gen9vgc2025regj-2025-12-1500.json"
    private val logger = LoggerFactory.getLogger("cobblemon_more_battle_content_better_ai")

    private val singlesTable: LocalOpponentBuildUsageTable by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        load(SINGLES_RESOURCE, "singles")
    }
    private val doublesTable: LocalOpponentBuildUsageTable by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        load(DOUBLES_RESOURCE, "doubles")
    }

    fun forFormat(format: BattleFormat): LocalOpponentBuildUsageTable = when (format) {
        BattleFormat.SINGLE -> singlesTable
        BattleFormat.DOUBLE -> doublesTable
    }

    private fun load(resource: String, formatLabel: String): LocalOpponentBuildUsageTable {
        val stream = LocalOpponentBuildUsage::class.java.getResourceAsStream(resource)
        if (stream == null) {
            logger.warn("Better AI {} opponent build usage snapshot is missing; native build worlds stay unavailable", formatLabel)
            return LocalOpponentBuildUsageTable.EMPTY
        }
        return try {
            stream.use { input ->
                InputStreamReader(input, StandardCharsets.UTF_8).use(LocalOpponentBuildUsageTable::parse)
            }
        } catch (exception: RuntimeException) {
            logger.warn(
                "Better AI {} opponent build usage snapshot is invalid; native build worlds stay unavailable: {}",
                formatLabel,
                exception.javaClass.name,
            )
            LocalOpponentBuildUsageTable.EMPTY
        }
    }
}
