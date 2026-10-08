package jbro.cobblemon.mcc.league.trainer

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.spawning.CobblemonSpawnPools
import com.cobblemon.mod.common.api.spawning.detail.PokemonSpawnDetail
import com.cobblemon.mod.common.api.spawning.detail.SpawnPool
import com.cobblemon.mod.common.pokemon.Species

/** How rare a species is in the wild, from Cobblemon's world spawn buckets. */
enum class WildRarity(val bucket: String) {
    COMMON("common"),
    UNCOMMON("uncommon"),
    RARE("rare"),
    ULTRA_RARE("ultra-rare");

    /** One step rarer; the rarest stays where it is. */
    fun next(): WildRarity = entries.getOrElse(ordinal + 1) { this }

    companion object {
        fun of(bucket: String): WildRarity? = entries.firstOrNull { it.bucket == bucket }
    }
}

/**
 * Each species' rarity: the most common bucket the world spawn pool brings it in. A species the pool never spawns
 * has none, and neither has a legendary, mythical, paradox or Ultra Beast species, so trades never touch those.
 */
internal object WildSpeciesRarity {
    /** Species with any of these labels are never wanted nor given. */
    val EXCLUDED_LABELS = setOf("legendary", "mythical", "paradox", "ultra_beast")

    /** The pool the table was read from, and the table; dropped when that pool reloads. */
    @Volatile
    private var table: Pair<SpawnPool, Map<String, WildRarity>>? = null
    private var watched: SpawnPool? = null

    fun of(species: Species): WildRarity? =
        if (excluded(species)) null else rarities()[species.resourceIdentifier.path]

    /** The species of [rarity] a trade may give. */
    fun species(rarity: WildRarity): List<Species> = rarities().entries.filter { it.value == rarity }
        .mapNotNull { PokemonSpecies.getByName(it.key) }.filter { it.implemented && !excluded(it) }

    fun excluded(species: Species): Boolean = species.labels.any { it in EXCLUDED_LABELS }

    private fun rarities(): Map<String, WildRarity> {
        val pool = CobblemonSpawnPools.WORLD_SPAWN_POOL
        table?.let { (from, rarities) -> if (from === pool) return rarities }
        val rarities = HashMap<String, WildRarity>()
        for (detail in pool.details) {
            if (detail !is PokemonSpawnDetail) continue
            val species = detail.pokemon.species?.substringAfter(':')?.lowercase() ?: continue
            val rarity = WildRarity.of(detail.bucket) ?: continue
            rarities.merge(species, rarity) { a, b -> minOf(a, b) }
        }
        if (watched !== pool) {
            watched = pool
            pool.observable.subscribe { table = null }
        }
        table = pool to rarities
        return rarities
    }
}
