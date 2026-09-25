package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.pokemon.stats.Stat
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import com.cobblemon.mod.common.pokemon.PokemonStats
import jbro.cobblemon.morebattlecontent.api.ai.BattleExactOwnTeamView
import jbro.cobblemon.morebattlecontent.api.ai.BattleExactPokemonBuildView
import net.minecraft.core.registries.BuiltInRegistries

/** Captures the trainer's own immutable opening sets before battle callbacks can mutate public state. */
internal object Cobblemon173ExactOwnTeamView {
    fun from(pokemon: List<BattlePokemon>): BattleExactOwnTeamView = BattleExactOwnTeamView(
        pokemon.map { battlePokemon ->
            val source = battlePokemon.effectedPokemon
            val heldItem = source.heldItem()
            BattleExactPokemonBuildView(
                battlePokemonId = battlePokemon.uuid,
                abilityId = source.ability.name,
                heldItemId = if (heldItem.isEmpty) null else BuiltInRegistries.ITEM.getKey(heldItem.item).toString(),
                natureId = source.nature.name.toString(),
                gender = source.gender.showdownName,
                evs = statSpread(source.evs) { getOrDefault(it) },
                ivs = statSpread(source.ivs) { getEffectiveBattleIV(it) },
                teraTypeId = source.teraType.name,
                showdownSpeciesId = source.form.showdownId(),
            )
        },
    )

    private fun <T : PokemonStats> statSpread(
        stats: T,
        value: T.(Stat) -> Int,
    ): Map<String, Int> = linkedMapOf(
        "hp" to stats.value(Stats.HP),
        "atk" to stats.value(Stats.ATTACK),
        "def" to stats.value(Stats.DEFENCE),
        "spa" to stats.value(Stats.SPECIAL_ATTACK),
        "spd" to stats.value(Stats.SPECIAL_DEFENCE),
        "spe" to stats.value(Stats.SPEED),
    )
}
