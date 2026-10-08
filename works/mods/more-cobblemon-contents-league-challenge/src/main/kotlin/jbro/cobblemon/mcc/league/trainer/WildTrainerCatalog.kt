package jbro.cobblemon.mcc.league.trainer

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.math.roundToInt
import kotlin.random.Random

/** One stage of a trainer's Pokemon pool: [species] appears at levels [minLevel] to [maxLevel]. */
data class WildTrainerStage(val species: String, val minLevel: Int, val maxLevel: Int) {
    init {
        require(species.matches(Regex("[a-z0-9_]+"))) { "Invalid species: $species" }
        require(minLevel in 1..100 && maxLevel in minLevel..100) { "Invalid levels for $species: $minLevel-$maxLevel" }
    }
}

/** How seasoned a wild trainer is: an ace brings more Pokemon, closer to the cap and better raised. */
enum class WildTrainerTier(val id: String) {
    NORMAL("normal"),
    ACE("ace");

    companion object {
        fun of(id: String): WildTrainerTier = entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Unknown tier: $id")
    }
}

/** What a wild NPC does when a player talks to it. Trainers battle; the others help (see `docs/WILD_NPC_ROLES.md`). */
enum class WildNpcRole(val id: String) {
    BATTLE("battle"),
    HEAL("heal"),
    TRADE("trade"),
    QUIZ("quiz"),
    GIFT("gift");

    companion object {
        fun of(id: String): WildNpcRole = entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Unknown role: $id")
    }
}

/**
 * A kind of NPC met in the wild. [npcClass] is the Cobblemon NPC class it spawns as; [role] is what it does. A trainer
 * ([WildNpcRole.BATTLE]) brings [pokemon] and pays [bp] for a win; the other roles need neither.
 */
data class WildTrainerDefinition(
    val npcClass: String,
    val bp: Long,
    val tier: WildTrainerTier,
    val pokemon: List<WildTrainerStage>,
    val role: WildNpcRole = WildNpcRole.BATTLE,
) {
    init {
        require(npcClass.matches(Regex("[a-z0-9_.-]+:[a-z0-9/._-]+"))) { "Invalid NPC class: $npcClass" }
        require(bp in 0..1_000_000) { "Invalid BP for $npcClass: $bp" }
        require(role != WildNpcRole.BATTLE || pokemon.isNotEmpty()) { "$npcClass has no Pokemon" }
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
        // Version 3 adds `role`; a version 2 file is a trainer.
        val version = root.get("schema_version")?.asInt
        require(version == 2 || version == 3) { "schema_version must be 2 or 3" }
        return WildTrainerDefinition(
            npcClass = root.get("npc_class").asString,
            bp = root.get("bp")?.asLong ?: 0,
            tier = WildTrainerTier.of(root.get("tier")?.asString ?: WildTrainerTier.NORMAL.id),
            pokemon = root.getAsJsonArray("pokemon")?.map { element ->
                val stage = element.asJsonObject
                WildTrainerStage(stage.get("species").asString, stage.get("min_level").asInt, stage.get("max_level").asInt)
            }.orEmpty(),
            role = if (version == 3) WildNpcRole.of(root.get("role")?.asString ?: WildNpcRole.BATTLE.id) else WildNpcRole.BATTLE,
        )
    }
}

/**
 * How a wild trainer's party follows the level cap of the player who challenges them. Any trainer may bring one to
 * six Pokemon; the more they bring, the further below the cap they stand, so a full party is not a harder fight
 * than a lone Pokemon at the cap. The last Pokemon is the trainer's ace and stands a little above the rest.
 */
object WildTrainerParty {
    const val MAX_SIZE = 6

    /** Relative chances of bringing 1 to 6 Pokemon in each phase; later phases lean towards fuller parties. */
    private val sizeWeights = mapOf(
        WildTrainerTier.NORMAL to mapOf(
            WildTrainerPhase.EARLY to intArrayOf(26, 26, 20, 14, 9, 5),
            WildTrainerPhase.MID to intArrayOf(14, 20, 22, 19, 14, 11),
            WildTrainerPhase.LATE to intArrayOf(10, 15, 19, 21, 18, 17),
            WildTrainerPhase.END to intArrayOf(8, 12, 17, 21, 21, 21),
        ),
        WildTrainerTier.ACE to mapOf(
            WildTrainerPhase.EARLY to intArrayOf(12, 22, 24, 20, 13, 9),
            WildTrainerPhase.MID to intArrayOf(7, 13, 19, 23, 21, 17),
            WildTrainerPhase.LATE to intArrayOf(4, 9, 15, 22, 25, 25),
            WildTrainerPhase.END to intArrayOf(3, 6, 12, 20, 27, 32),
        ),
    )

    fun size(cap: Int, tier: WildTrainerTier, random: Random): Int {
        val weights = sizeWeights.getValue(tier).getValue(WildTrainerPhase.of(cap))
        var roll = random.nextInt(weights.sum())
        weights.forEachIndexed { index, weight ->
            if (roll < weight) return index + 1
            roll -= weight
        }
        return MAX_SIZE
    }

    /**
     * How far below [cap] the party's average stands when it brings [size] Pokemon. Early caps shrink the gap so a
     * full party at cap 15 is still near level 11 rather than level 5.
     */
    fun averageGap(cap: Int, tier: WildTrainerTier, size: Int): Double {
        val (base, perPokemon) = when (tier) {
            WildTrainerTier.NORMAL -> 2.0 to 1.6
            WildTrainerTier.ACE -> 0.5 to 1.1
        }
        val scale = (cap / 60.0).coerceIn(0.35, 1.0)
        return (base + perPokemon * (size.coerceIn(1, MAX_SIZE) - 1)) * scale
    }

    /** The levels of a party of [size], the ace last; never above [cap]. */
    fun levels(cap: Int, tier: WildTrainerTier, size: Int, random: Random): List<Int> {
        val top = cap.coerceIn(1, 100)
        val average = top - averageGap(top, tier, size)
        return List(size) { index ->
            val ace = size > 1 && index == size - 1
            val level = average + random.nextInt(-2, 2) + if (ace) 2 else 0
            level.roundToInt().coerceIn(1, top)
        }
    }

    /** The species and level of each Pokemon the trainer brings, different species where the pool allows. */
    fun roll(definition: WildTrainerDefinition, cap: Int, random: Random): List<Pair<String, Int>> {
        val size = size(cap, definition.tier, random)
        val taken = HashSet<String>()
        return levels(cap, definition.tier, size, random).map { level ->
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
