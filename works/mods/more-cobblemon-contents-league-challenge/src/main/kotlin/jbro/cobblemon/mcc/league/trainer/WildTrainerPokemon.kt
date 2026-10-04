package jbro.cobblemon.mcc.league.trainer

import com.cobblemon.mod.common.api.moves.MoveTemplate
import com.cobblemon.mod.common.api.moves.Moves
import com.cobblemon.mod.common.api.moves.categories.DamageCategories
import com.cobblemon.mod.common.api.pokemon.Natures
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.pokemon.Pokemon
import kotlin.random.Random
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack

/** Turns a rolled species and level into a Cobblemon Pokemon raised as [WildTrainerBuild] decides. */
internal object WildTrainerPokemon {
    private val stats = mapOf(
        Stats.HP to WildTrainerStat.HP, Stats.ATTACK to WildTrainerStat.ATTACK, Stats.DEFENCE to WildTrainerStat.DEFENCE,
        Stats.SPECIAL_ATTACK to WildTrainerStat.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE to WildTrainerStat.SPECIAL_DEFENCE,
        Stats.SPEED to WildTrainerStat.SPEED,
    )
    private val cobblemonStats = stats.entries.associate { (stat, ours) -> ours to stat }

    /** The party for one fight; [heldItems] collects what the party holds so no item repeats. */
    fun create(species: String, level: Int, tier: WildTrainerTier, cap: Int, heldItems: MutableSet<String>, random: Random): Pokemon? {
        if (PokemonSpecies.getByName(species) == null) return null
        val pokemon = PokemonProperties.parse("$species level=$level").create()
        val quality = WildTrainerQuality.of(tier, cap)
        val form = pokemon.form
        val mon = WildTrainerMon(
            types = pokemon.types.map { it.showdownId.lowercase() },
            baseStats = form.baseStats.mapNotNull { (stat, value) -> stats[stat]?.let { it to value } }.toMap(),
            canEvolve = form.evolutions.isNotEmpty(),
        )
        val known = form.moves.getLevelUpMovesUpTo(level).map(::option)
        val taught = (form.moves.tmMoves + form.moves.tutorMoves).map(::option)
        val names = WildTrainerBuild.moves(mon, known, taught, quality, random)
        val templates = names.mapNotNull(Moves::getByName)
        if (templates.isNotEmpty()) {
            pokemon.moveSet.doWithoutEmitting {
                pokemon.moveSet.clear()
                templates.forEachIndexed { slot, template -> pokemon.moveSet.setMove(slot, template.create()) }
            }
        }
        WildTrainerBuild.nature(mon, quality, random)
            ?.let { Natures.getNature(ResourceLocation.fromNamespaceAndPath("cobblemon", it)) }
            ?.let { pokemon.nature = it }
        WildTrainerBuild.ivs(quality, random).forEach { (stat, value) -> pokemon.setIV(cobblemonStats.getValue(stat), value) }
        WildTrainerBuild.evs(mon, quality).forEach { (stat, value) -> pokemon.setEV(cobblemonStats.getValue(stat), value) }
        val attackType = templates.map(::option).filter { it.category != WildTrainerMoveCategory.STATUS }
            .maxByOrNull { WildTrainerBuild.attackScore(mon, it) }?.type
        WildTrainerBuild.item(mon, attackType, tier, cap, heldItems, random)?.let { path ->
            val item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.fromNamespaceAndPath("cobblemon", path)).orElse(null) ?: return@let
            pokemon.swapHeldItem(ItemStack(item), false)
            heldItems += path
        }
        pokemon.heal()
        return pokemon
    }

    private fun option(template: MoveTemplate) = WildTrainerMove(
        name = template.name,
        type = template.elementalType.showdownId.lowercase(),
        category = when (template.damageCategory) {
            DamageCategories.PHYSICAL -> WildTrainerMoveCategory.PHYSICAL
            DamageCategories.SPECIAL -> WildTrainerMoveCategory.SPECIAL
            else -> WildTrainerMoveCategory.STATUS
        },
        power = template.power,
        accuracy = template.accuracy,
    )
}
