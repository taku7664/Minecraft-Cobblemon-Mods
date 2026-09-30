package jbro.cobblemon.mcc.league.trainer

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.random.Random

/** One stage of a trainer's Pokemon pool: [species] appears at levels [minLevel] to [maxLevel]. */
data class WildTrainerStage(val species: String, val minLevel: Int, val maxLevel: Int) {
    init {
        require(species.matches(Regex("[a-z0-9_]+"))) { "Invalid species: $species" }
        require(minLevel in 1..100 && maxLevel in minLevel..100) { "Invalid levels for $species: $minLevel-$maxLevel" }
    }
}

/**
 * A kind of trainer met in the wild. [npcClass] is the Cobblemon NPC class it spawns as; [bp] is paid for a win;
 * [strong] trainers bring one more Pokemon and fight closer to the level cap.
 */
data class WildTrainerDefinition(
    val npcClass: String,
    val bp: Long,
    val strong: Boolean,
    val pokemon: List<WildTrainerStage>,
) {
    init {
        require(npcClass.matches(Regex("[a-z0-9_.-]+:[a-z0-9/._-]+"))) { "Invalid NPC class: $npcClass" }
        require(bp in 0..1_000_000) { "Invalid BP for $npcClass: $bp" }
        require(pokemon.isNotEmpty()) { "$npcClass has no Pokemon" }
    }
}

object WildTrainerCatalogParser {
    /** Reads every definition in [files] (resource ID to JSON), keyed by NPC class; any bad file rejects them all. */
    fun parse(files: Map<String, String>): Map<String, WildTrainerDefinition> {
        val definitions = files.entries.sortedBy { it.key }.map { (id, json) ->
            try {
                read(JsonParser.parseString(json).asJsonObject)
            } catch (failure: RuntimeException) {
                throw IllegalArgumentException("Wild trainer $id: ${failure.message}", failure)
            }
        }
        val byClass = definitions.associateBy(WildTrainerDefinition::npcClass)
        require(byClass.size == definitions.size) { "Two wild trainer files name the same NPC class" }
        return byClass
    }

    private fun read(root: JsonObject): WildTrainerDefinition {
        require(root.get("schema_version")?.asInt == 1) { "schema_version must be 1" }
        return WildTrainerDefinition(
            npcClass = root.get("npc_class").asString,
            bp = root.get("bp")?.asLong ?: 0,
            strong = root.get("strong")?.asBoolean ?: false,
            pokemon = root.getAsJsonArray("pokemon").map { element ->
                val stage = element.asJsonObject
                WildTrainerStage(stage.get("species").asString, stage.get("min_level").asInt, stage.get("max_level").asInt)
            },
        )
    }
}

/** How a wild trainer's party follows the level cap of the player who challenges them. */
object WildTrainerParty {
    /** How many Pokemon a trainer brings against a player whose cap is [cap]. */
    fun sizes(cap: Int, strong: Boolean): IntRange {
        val base = when {
            cap <= 24 -> 1..2
            cap <= 39 -> 2..3
            cap <= 52 -> 3..4
            cap <= 64 -> 3..5
            else -> 4..6
        }
        return if (strong) minOf(base.first + 1, 6)..minOf(base.last + 1, 6) else base
    }

    /** The levels a trainer's Pokemon take against a cap of [cap]; never above it. */
    fun levels(cap: Int, strong: Boolean): IntRange {
        val top = cap.coerceIn(1, 100)
        val range = if (strong) (top - 4)..top else (top - 7)..(top - 2)
        return range.first.coerceIn(1, top)..range.last.coerceIn(1, top)
    }

    /** The species and level of each Pokemon the trainer brings, different species where the pool allows. */
    fun roll(definition: WildTrainerDefinition, cap: Int, random: Random): List<Pair<String, Int>> {
        val size = sizes(cap, definition.strong).let { random.nextInt(it.first, it.last + 1) }
        val levels = levels(cap, definition.strong)
        val taken = HashSet<String>()
        return List(size) {
            val level = random.nextInt(levels.first, levels.last + 1)
            val fitting = definition.pokemon.filter { level in it.minLevel..it.maxLevel }
                .ifEmpty { listOf(nearest(definition.pokemon, level)) }
            val stage = fitting.filter { it.species !in taken }.ifEmpty { fitting }.random(random)
            taken += stage.species
            stage.species to level
        }
    }

    /** The stage whose levels come closest to [level], for a pool with no stage covering it. */
    private fun nearest(stages: List<WildTrainerStage>, level: Int): WildTrainerStage =
        stages.minBy { if (level < it.minLevel) it.minLevel - level else level - it.maxLevel }
}
