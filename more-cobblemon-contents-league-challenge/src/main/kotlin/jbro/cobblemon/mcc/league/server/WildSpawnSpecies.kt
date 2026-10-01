package jbro.cobblemon.mcc.league.server

import com.cobblemon.mod.common.api.pokemon.evolution.PreEvolution
import com.cobblemon.mod.common.api.spawning.CobblemonSpawnPools
import com.cobblemon.mod.common.api.spawning.detail.PokemonSpawnDetail
import com.cobblemon.mod.common.api.spawning.detail.SpawnPool
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.Species
import com.cobblemon.mod.common.pokemon.requirements.LevelRequirement
import jbro.cobblemon.mcc.league.system.WildSpawnStage

/**
 * Fits a wild spawn's species to the level the League gave it. A stage's lowest level is the lowest level Cobblemon's
 * world spawn pool brings it at, which covers stone, trade and friendship evolutions as well (Gengar from 36,
 * Raichu from 27); a species the pool never spawns falls back to the level its evolution needs.
 */
internal object WildSpawnSpecies {
    private const val LONGEST_LINE = 4

    /** The pool the levels were read from, and the levels; dropped when that pool reloads. */
    @Volatile
    private var lowest: Pair<SpawnPool, Map<String, Int>>? = null
    private var watched: SpawnPool? = null

    /** Steps [pokemon] back to the stage that fits [level]; true when its species changed. */
    fun fit(pokemon: Pokemon, level: Int): Boolean {
        val lowestSpawn = lowestSpawnLevels()
        val line = generateSequence(pokemon.species to preEvolution(pokemon.species, pokemon.form.preEvolution)) { (_, pre) ->
            pre?.let { it.species to preEvolution(it.species, it.form.preEvolution) }
        }.takeWhile { it.second != null }.take(LONGEST_LINE).toList()
        val minimums = line.map { (species, pre) -> lowestSpawn[species.resourceIdentifier.path] ?: evolutionLevel(pre!!, species) }
        val steps = WildSpawnStage.stepsBack(level, minimums)
        if (steps == 0) return false
        pokemon.species = line[steps - 1].second!!.species
        return true
    }

    private fun preEvolution(species: Species, formPre: PreEvolution?): PreEvolution? = formPre ?: species.preEvolution

    /** The level [pre] needs to evolve into [species], or null when it evolves another way. */
    private fun evolutionLevel(pre: PreEvolution, species: Species): Int? = pre.form.evolutions
        .filter { it.result.species?.substringAfter(':') == species.resourceIdentifier.path }
        .flatMap { it.requirements }.filterIsInstance<LevelRequirement>().minOfOrNull { it.minLevel }

    /** Each species' lowest level in the world spawn pool, worked out again whenever the pool reloads. */
    private fun lowestSpawnLevels(): Map<String, Int> {
        val pool = CobblemonSpawnPools.WORLD_SPAWN_POOL
        lowest?.let { (from, levels) -> if (from === pool) return levels }
        val levels = HashMap<String, Int>()
        for (detail in pool.details) {
            if (detail !is PokemonSpawnDetail) continue
            val species = detail.pokemon.species?.substringAfter(':')?.lowercase() ?: continue
            val low = detail.levelRange?.first ?: continue
            levels.merge(species, low, ::minOf)
        }
        if (watched !== pool) {
            watched = pool
            pool.observable.subscribe { lowest = null }
        }
        lowest = pool to levels
        return levels
    }
}
