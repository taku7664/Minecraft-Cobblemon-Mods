package jbro.cobblemon.mcc.pwt.system

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.random.Random

/**
 * A tournament trainer. [pool] holds Showdown-style property lines at the battle level; each battle draws
 * [PwtRules.TEAM_SIZE] of them, so a pool keeps one Pokémon per species and one of each held item.
 */
data class PwtTrainer(
    val id: String,
    val nameKey: String,
    val generation: Int,
    val skin: String,
    val slim: Boolean,
    /** The Pokémon the bracket names this trainer by on hover. */
    val ace: String,
    val pool: List<String>,
) {
    fun drawTeam(random: Random): List<String> = pool.shuffled(random).take(PwtRules.TEAM_SIZE)
}

data class PwtCatalog(val trainers: Map<String, PwtTrainer>)

object PwtCatalogParser {
    private val ID = Regex("[a-z0-9_.-]+:[a-z0-9/._-]+")
    private val TEXTURE = Regex("[a-z0-9_.-]+:textures/[a-z0-9/._-]+\\.png")

    /** [files] maps each trainer ID (`namespace:path`) to its JSON. */
    fun parse(files: Map<String, String>): PwtCatalog {
        val trainers = files.entries.sortedBy { it.key }.associate { (id, json) ->
            require(ID.matches(id)) { "Invalid PWT trainer ID $id" }
            id to runCatching { trainer(id, JsonParser.parseString(json).asJsonObject) }
                .getOrElse { throw IllegalArgumentException("PWT trainer $id: ${it.message}", it) }
        }
        require(trainers.size >= PwtBracket.SIZE - 1) { "PWT needs at least ${PwtBracket.SIZE - 1} trainers, found ${trainers.size}" }
        return PwtCatalog(trainers)
    }

    private fun trainer(id: String, json: JsonObject): PwtTrainer {
        require(json.get("schema_version")?.asInt == 1) { "schema_version must be 1" }
        val pool = json.getAsJsonArray("pokemon").map { it.asString.trim() }
        require(pool.size in PwtRules.TEAM_SIZE..6) { "pokemon must list ${PwtRules.TEAM_SIZE} to 6 entries" }
        val species = pool.map { it.substringBefore(' ') }
        require(species.distinct().size == species.size) { "pokemon repeats a species" }
        val items = pool.mapNotNull { line -> property(line, "held_item") }
        require(items.distinct().size == items.size) { "pokemon repeats a held item" }
        pool.forEach { line -> require(property(line, "level") == PwtRules.BATTLE_LEVEL.toString()) { "every entry must be level=${PwtRules.BATTLE_LEVEL}: $line" } }
        val skin = json.get("skin").asString
        require(TEXTURE.matches(skin)) { "skin must be a texture resource: $skin" }
        val ace = json.get("ace").asString
        require(ace in species) { "ace $ace is not in the pool" }
        return PwtTrainer(
            id = id,
            nameKey = json.get("name_key").asString.also { require(it.isNotBlank()) { "name_key is blank" } },
            generation = json.get("generation").asInt.also { require(it in 1..9) { "generation must be 1-9" } },
            skin = skin,
            slim = json.get("slim")?.asBoolean ?: false,
            ace = ace,
            pool = pool,
        )
    }

    private fun property(line: String, key: String): String? =
        line.split(' ').firstOrNull { it.startsWith("$key=") }?.substringAfter('=')
}
