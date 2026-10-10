package jbro.cobblemon.policy.wild

import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.properties.HiddenAbilityProperty
import java.util.concurrent.ThreadLocalRandom
import jbro.cobblemon.policy.config.IvRange
import jbro.cobblemon.policy.config.PolicyConfig
import kotlin.random.Random

/**
 * Wild Pokemon roll their IVs from the server's bands instead of uniformly, and any new Pokemon — wild, revived
 * from a fossil, or made with /givepokemon or /spawnpokemon — may get its hidden ability.
 */
object WildPokemonPolicy {
    private val PERMANENT_STATS = listOf(Stats.HP, Stats.ATTACK, Stats.DEFENCE, Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED)
    private var hiddenAbilityOneIn = 0
    private var config: PolicyConfig? = null

    fun register(config: PolicyConfig) {
        hiddenAbilityOneIn = config.wildHiddenAbilityOneIn
        this.config = config
        CobblemonEvents.POKEMON_ENTITY_SPAWN.subscribe { event ->
            val pokemon = event.entity.pokemon
            if (config.wildIvEnabled) rollIvs(pokemon, config.wildIvRanges)
            if (pokemon.isAlpha) {
                val ivs = PERMANENT_STATS.map { pokemon.ivs[it] ?: 0 }
                val guaranteed = WildRolls.alphaIvs(ivs, Random.Default)
                PERMANENT_STATS.forEachIndexed { index, stat ->
                    if (guaranteed[index] != ivs[index]) pokemon.setIV(stat, guaranteed[index])
                }
            }
            applyHiddenAbilityChance(pokemon)
        }
        CobblemonEvents.FOSSIL_REVIVED.subscribe { event -> applyHiddenAbilityChance(event.pokemon) }
    }

    /**
     * The wild rolls for a Pokemon made in code that should come out like a wild one: IVs from the server's bands and
     * the hidden ability chance. Other mods call it by reflection (League's wild trade NPC), so its name and
     * signature stay put.
     */
    @JvmStatic
    fun applyWildRolls(pokemon: Pokemon): Pokemon {
        config?.let { if (it.wildIvEnabled) rollIvs(pokemon, it.wildIvRanges) }
        return applyHiddenAbilityChance(pokemon)
    }

    /** Also called by the /givepokemon and /spawnpokemon mixins. */
    @JvmStatic
    fun applyHiddenAbilityChance(pokemon: Pokemon): Pokemon {
        if (hiddenAbilityOneIn > 0 && WildRolls.hiddenAbility(hiddenAbilityOneIn, ThreadLocalRandom.current().nextInt(hiddenAbilityOneIn))) HiddenAbilityProperty(true).apply(pokemon)
        return pokemon
    }

    private fun rollIvs(pokemon: Pokemon, ranges: List<IvRange>) {
        val random = ThreadLocalRandom.current()
        for (stat in PERMANENT_STATS) {
            val range = WildRolls.ivRange(ranges, random.nextDouble(ranges.sumOf { it.chance }))
            pokemon.setIV(stat, random.nextInt(range.min, range.max + 1))
        }
    }
}
