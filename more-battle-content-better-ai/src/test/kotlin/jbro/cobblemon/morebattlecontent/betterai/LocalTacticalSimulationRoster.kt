package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import java.io.InputStreamReader
import java.net.JarURLConnection
import java.nio.file.Files
import java.nio.file.Paths
import java.util.jar.JarFile
import java.util.zip.ZipInputStream
import kotlin.math.floor
import kotlin.random.Random

/**
 * Loads complete immutable Factory presets for virtual battles. The large randomized damage league keeps its
 * original four-direct-damage filter, while focused scenario battles can request the full preset catalog.
 * Preset contents are never recombined or randomized independently.
 */
internal class LocalTacticalSimulationRoster private constructor(
    val entries: List<LocalTacticalSimulationEntry>,
) {
    fun randomTeam(random: Random, size: Int): List<LocalTacticalSimulationEntry> {
        require(size > 0)
        val selected = ArrayList<LocalTacticalSimulationEntry>(size)
        val species = HashSet<String>()
        val heldItems = HashSet<String>()
        for (candidate in entries.shuffled(random)) {
            if (candidate.speciesId in species || candidate.heldItemId in heldItems) continue
            selected += candidate
            species += candidate.speciesId
            heldItems += candidate.heldItemId
            if (selected.size == size) return selected
        }
        error("Simulation roster cannot draw $size unique species and held items")
    }

    companion object {
        private val allCached by lazy { loadUncached() }
        private val damageOnlyCached by lazy {
            LocalTacticalSimulationRoster(
                allCached.entries.filter { entry -> entry.moves.all(::isDamageLeagueMove) },
            )
        }

        fun load(): LocalTacticalSimulationRoster = damageOnlyCached

        fun loadAll(): LocalTacticalSimulationRoster = allCached

        private fun loadUncached(): LocalTacticalSimulationRoster {
            val moveData = loadMoveData()
            cobblemonJar().use { jar ->
                val speciesEntries = jar.entries().asSequence()
                    .filter { it.name.startsWith("data/cobblemon/species/") && it.name.endsWith(".json") }
                    .associateBy { it.name.substringAfterLast('/').removeSuffix(".json") }
                val entries = rentalSetRoots().flatMap { root ->
                    root.getAsJsonArray("rental_sets").mapNotNull { element ->
                        val set = element.asJsonObject
                        val moves = set.getAsJsonArray("moves").map { moveElement ->
                            moveData[moveElement.asString.normalizedId()]
                        }
                        if (moves.any { it == null }) {
                            return@mapNotNull null
                        }
                        val speciesId = set.requiredString("species_id")
                        val speciesName = speciesId.substringAfter(':')
                        val speciesEntry = requireNotNull(speciesEntries[speciesName]) {
                            "Missing Cobblemon species metadata for $speciesId"
                        }
                        val speciesRoot = jar.getInputStream(speciesEntry).reader().use(JsonParser::parseReader).asJsonObject
                        val formId = set.optionalString("form_id")
                        val form = formId?.let { wanted ->
                            speciesRoot.getAsJsonArray("forms")?.map { it.asJsonObject }
                                ?.firstOrNull { it.optionalString("name")?.equals(wanted, ignoreCase = true) == true }
                        }
                        val baseStats = (form?.getAsJsonObject("baseStats") ?: speciesRoot.getAsJsonObject("baseStats"))
                        val evs = set.requiredSpread("evs")
                        val ivs = set.getAsJsonObject("ivs")?.toSpread() ?: LocalTacticalSimulationStatSpread.uniform(31)
                        val natureId = set.requiredString("nature_id")
                        LocalTacticalSimulationEntry(
                            setId = set.requiredString("set_id"),
                            speciesId = speciesId,
                            formId = formId,
                            abilityId = set.requiredString("ability_id"),
                            heldItemId = set.requiredString("held_item_id"),
                            natureId = natureId,
                            evs = evs,
                            ivs = ivs,
                            typeIds = linkedSetOf(
                                (form?.optionalString("primaryType") ?: speciesRoot.requiredString("primaryType")).lowercase(),
                            ).apply {
                                (form?.optionalString("secondaryType") ?: speciesRoot.optionalString("secondaryType"))
                                    ?.lowercase()?.let(::add)
                            },
                            stats = calculatedStats(baseStats, ivs, evs, natureId),
                            moves = moves.filterNotNull(),
                        )
                    }
                }.sortedBy { it.setId }
                require(entries.map { it.setId }.distinct().size == entries.size) { "Duplicate simulation set IDs" }
                return LocalTacticalSimulationRoster(entries)
            }
        }

        private fun isDamageLeagueMove(move: LocalTacticalSimulationMove): Boolean =
            move.category != BattleMoveDamageCategory.STATUS && move.power > 0.0

        private fun calculatedStats(
            base: JsonObject,
            ivs: LocalTacticalSimulationStatSpread,
            evs: LocalTacticalSimulationStatSpread,
            natureId: String,
        ): LocalTacticalSimulationStats {
            val nature = NATURES[natureId.substringAfter(':')] ?: NatureEffect()
            fun hp(baseValue: Int, iv: Int, ev: Int): Int =
                floor((2 * baseValue + iv + floor(ev / 4.0)) * LEVEL / 100.0).toInt() + LEVEL + 10
            fun stat(name: String, baseValue: Int, iv: Int, ev: Int): Int {
                val raw = floor((2 * baseValue + iv + floor(ev / 4.0)) * LEVEL / 100.0).toInt() + 5
                return floor(raw * nature.multiplier(name)).toInt()
            }
            return LocalTacticalSimulationStats(
                maxHp = hp(base.requiredInt("hp"), ivs.hp, evs.hp),
                attack = stat("attack", base.requiredInt("attack"), ivs.attack, evs.attack),
                defence = stat("defense", base.requiredInt("defence"), ivs.defense, evs.defense),
                specialAttack = stat(
                    "special_attack",
                    base.requiredInt("special_attack"),
                    ivs.specialAttack,
                    evs.specialAttack,
                ),
                specialDefence = stat(
                    "special_defense",
                    base.requiredInt("special_defence"),
                    ivs.specialDefense,
                    evs.specialDefense,
                ),
                speed = stat("speed", base.requiredInt("speed"), ivs.speed, evs.speed),
            )
        }

        private fun loadMoveData(): Map<String, LocalTacticalSimulationMove> {
            val showdown = requireNotNull(
                LocalTacticalSimulationRoster::class.java.getResourceAsStream("/data/cobblemon/showdown.zip"),
            ) {
                "Missing Cobblemon Showdown data"
            }
            val source = showdown.use { input ->
                ZipInputStream(input).use { zip ->
                    generateSequence { zip.nextEntry }
                        .firstOrNull { it.name == "data/moves.js" }
                        ?: error("Missing data/moves.js in Cobblemon Showdown data")
                    InputStreamReader(zip).readText()
                }
            }
            return topLevelObjectEntries(source, "const Moves = {").mapNotNull { (id, body) ->
                val category = field(body, "category") ?: return@mapNotNull null
                val type = field(body, "type") ?: return@mapNotNull null
                val power = numericField(body, "basePower") ?: return@mapNotNull null
                val accuracy = when (val raw = rawField(body, "accuracy")) {
                    "true" -> 100.0
                    null -> return@mapNotNull null
                    else -> raw.toDoubleOrNull() ?: return@mapNotNull null
                }
                val damageCategory = when (category.lowercase()) {
                    "physical" -> BattleMoveDamageCategory.PHYSICAL
                    "special" -> BattleMoveDamageCategory.SPECIAL
                    "status" -> BattleMoveDamageCategory.STATUS
                    else -> return@mapNotNull null
                }
                id to LocalTacticalSimulationMove(
                    id = "cobblemon:$id",
                    typeId = type.lowercase(),
                    power = power,
                    category = damageCategory,
                    accuracy = accuracy,
                    priority = numericField(body, "priority")?.toInt() ?: 0,
                )
            }.toMap()
        }

        private fun topLevelObjectEntries(source: String, marker: String): List<Pair<String, String>> {
            var index = source.indexOf(marker)
            require(index >= 0) { "Missing JavaScript object marker: $marker" }
            index += marker.length
            val entries = ArrayList<Pair<String, String>>()
            while (index < source.length) {
                while (index < source.length && (source[index].isWhitespace() || source[index] == ',')) index += 1
                if (index >= source.length || source[index] == '}') break
                val (key, keyEnd) = if (source[index] == '"') {
                    val end = source.indexOf('"', index + 1)
                    source.substring(index + 1, end) to end
                } else {
                    val end = source.indexOf(':', index)
                    source.substring(index, end).trim() to end
                }
                require(key.matches(Regex("[a-z0-9]+"))) { "Invalid Showdown ID '$key' at offset $index" }
                index = source.indexOf('{', keyEnd)
                require(index >= 0) { "Missing object body for $key" }
                val bodyEnd = matchingBrace(source, index)
                entries += key to source.substring(index + 1, bodyEnd)
                index = bodyEnd + 1
            }
            return entries
        }

        private fun matchingBrace(source: String, start: Int): Int {
            var depth = 0
            var quote: Char? = null
            var escaped = false
            var lineComment = false
            var blockComment = false
            var index = start
            while (index < source.length) {
                val char = source[index]
                val next = source.getOrNull(index + 1)
                if (lineComment) {
                    if (char == '\n') lineComment = false
                } else if (blockComment) {
                    if (char == '*' && next == '/') {
                        blockComment = false
                        index += 1
                    }
                } else if (quote != null) {
                    if (escaped) escaped = false
                    else if (char == '\\') escaped = true
                    else if (char == quote) quote = null
                } else {
                    when (char) {
                        '/' -> when (next) {
                            '/' -> {
                                lineComment = true
                                index += 1
                            }
                            '*' -> {
                                blockComment = true
                                index += 1
                            }
                        }
                        '\'', '"', '`' -> quote = char
                        '{' -> depth += 1
                        '}' -> {
                            depth -= 1
                            if (depth == 0) return index
                        }
                    }
                }
                index += 1
            }
            error("Unclosed JavaScript object at offset $start")
        }

        private fun field(body: String, name: String): String? =
            Regex("(?:^|\\n)\\s*$name:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)

        private fun numericField(body: String, name: String): Double? = rawField(body, name)?.toDoubleOrNull()

        private fun rawField(body: String, name: String): String? =
            Regex("(?:^|\\n)\\s*$name:\\s*(true|-?\\d+(?:\\.\\d+)?)").find(body)?.groupValues?.get(1)

        private fun rentalSetRoots(): List<JsonObject> {
            val url = requireNotNull(LocalTacticalSimulationRoster::class.java.getResource(RENTAL_SET_DIRECTORY)) {
                "Missing Battle Factory rental set resources"
            }
            if (url.protocol == "jar") {
                val jar = (url.openConnection() as JarURLConnection).jarFile
                return jar.entries().asSequence()
                    .filter { it.name.startsWith(RENTAL_SET_DIRECTORY.removePrefix("/") + "/") && it.name.endsWith(".json") }
                    .sortedBy { it.name }
                    .map { entry ->
                        jar.getInputStream(entry).reader().use(JsonParser::parseReader).asJsonObject
                    }.toList()
            }
            return Files.list(Paths.get(url.toURI())).use { paths ->
                paths.filter { it.fileName.toString().endsWith(".json") }.sorted().map { path ->
                    Files.newBufferedReader(path).use(JsonParser::parseReader).asJsonObject
                }.toList()
            }
        }

        private fun cobblemonJar(): JarFile {
            val showdownUrl = requireNotNull(
                LocalTacticalSimulationRoster::class.java.getResource("/data/cobblemon/showdown.zip"),
            )
            if (showdownUrl.protocol == "jar") {
                return (showdownUrl.openConnection() as JarURLConnection).jarFile
            }
            val candidate = System.getProperty("java.class.path").split(System.getProperty("path.separator"))
                .asSequence().map(Paths::get).filter { it.toFile().isFile && it.fileName.toString().endsWith(".jar") }
                .firstOrNull { path ->
                    runCatching {
                        JarFile(path.toFile()).use { jar ->
                            jar.entries().asSequence().any { it.name.startsWith("data/cobblemon/species/") }
                        }
                    }.getOrDefault(false)
                }
            return JarFile(requireNotNull(candidate) {
                "Could not locate the Cobblemon runtime JAR; showdown resource protocol=${showdownUrl.protocol}"
            }.toFile())
        }

        private const val LEVEL = 50
        private const val RENTAL_SET_DIRECTORY = "/data/cobblemon_more_battle_content/mbc-battle-factory/rental-sets"
        private val NATURES = mapOf(
            "lonely" to NatureEffect("attack", "defense"),
            "adamant" to NatureEffect("attack", "special_attack"),
            "naughty" to NatureEffect("attack", "special_defense"),
            "brave" to NatureEffect("attack", "speed"),
            "bold" to NatureEffect("defense", "attack"),
            "impish" to NatureEffect("defense", "special_attack"),
            "lax" to NatureEffect("defense", "special_defense"),
            "relaxed" to NatureEffect("defense", "speed"),
            "modest" to NatureEffect("special_attack", "attack"),
            "mild" to NatureEffect("special_attack", "defense"),
            "rash" to NatureEffect("special_attack", "special_defense"),
            "quiet" to NatureEffect("special_attack", "speed"),
            "calm" to NatureEffect("special_defense", "attack"),
            "gentle" to NatureEffect("special_defense", "defense"),
            "careful" to NatureEffect("special_defense", "special_attack"),
            "sassy" to NatureEffect("special_defense", "speed"),
            "timid" to NatureEffect("speed", "attack"),
            "hasty" to NatureEffect("speed", "defense"),
            "jolly" to NatureEffect("speed", "special_attack"),
            "naive" to NatureEffect("speed", "special_defense"),
        )
    }
}

internal data class LocalTacticalSimulationEntry(
    val setId: String,
    val speciesId: String,
    val formId: String?,
    val abilityId: String,
    val heldItemId: String,
    val natureId: String,
    val evs: LocalTacticalSimulationStatSpread,
    val ivs: LocalTacticalSimulationStatSpread,
    val typeIds: Set<String>,
    val stats: LocalTacticalSimulationStats,
    val moves: List<LocalTacticalSimulationMove>,
)

internal data class LocalTacticalSimulationMove(
    val id: String,
    val typeId: String,
    val power: Double,
    val category: BattleMoveDamageCategory,
    val accuracy: Double,
    val priority: Int,
)

internal data class LocalTacticalSimulationStats(
    val maxHp: Int,
    val attack: Int,
    val defence: Int,
    val specialAttack: Int,
    val specialDefence: Int,
    val speed: Int,
)

internal data class LocalTacticalSimulationStatSpread(
    val hp: Int,
    val attack: Int,
    val defense: Int,
    val specialAttack: Int,
    val specialDefense: Int,
    val speed: Int,
) {
    val total: Int get() = hp + attack + defense + specialAttack + specialDefense + speed

    companion object {
        fun uniform(value: Int) = LocalTacticalSimulationStatSpread(value, value, value, value, value, value)
    }
}

private data class NatureEffect(
    val increased: String? = null,
    val decreased: String? = null,
) {
    fun multiplier(stat: String): Double = when (stat) {
        increased -> 1.1
        decreased -> 0.9
        else -> 1.0
    }
}

private fun JsonObject.requiredString(name: String): String = requireNotNull(get(name)) { "Missing $name" }.asString
private fun JsonObject.optionalString(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString
private fun JsonObject.requiredInt(name: String): Int = requireNotNull(get(name)) { "Missing $name" }.asInt
private fun JsonObject.requiredSpread(name: String): LocalTacticalSimulationStatSpread =
    requireNotNull(getAsJsonObject(name)) { "Missing $name" }.toSpread()

private fun JsonObject.toSpread() = LocalTacticalSimulationStatSpread(
    hp = requiredInt("hp"),
    attack = requiredInt("attack"),
    defense = requiredInt("defense"),
    specialAttack = requiredInt("special_attack"),
    specialDefense = requiredInt("special_defense"),
    speed = requiredInt("speed"),
)

private fun String.normalizedId(): String = substringAfter(':').lowercase().filter(Char::isLetterOrDigit)
