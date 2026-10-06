package jbro.cobblemon.policy.legend

import com.cobblemon.mod.common.api.Priority
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.pokemon.Pokemon
import jbro.cobblemon.policy.JbroPolicy
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

/**
 * Ultra Beasts and the paradoxes that are not Legends are free to catch like any wild Pokemon, but the server still
 * hears when one appears near a player. Legends have their own lines in [LegendPolicy].
 */
object RareSpawnNotice {
    private const val ANNOUNCED_KEY = "jbro_policy_rare_announced"
    private val KINDS = listOf("ultra_beast", "paradox")

    fun register() {
        CobblemonEvents.POKEMON_ENTITY_SPAWN.subscribe(Priority.LOWEST) { event ->
            if (event.isCanceled) return@subscribe
            val player = event.spawnablePosition.cause.entity as? ServerPlayer ?: return@subscribe
            announce(event.entity.pokemon, player)
        }
        CobblemonEvents.POKE_SNACK_SPAWN_POKEMON_POST.subscribe { event ->
            val placer = event.pokeSnackBlockEntity.placedBy ?: return@subscribe
            val player = event.pokeSnackBlockEntity.level?.server?.playerList?.getPlayer(placer) ?: return@subscribe
            announce(event.pokemonEntity.pokemon, player)
        }
    }

    private fun announce(pokemon: Pokemon, player: ServerPlayer) {
        if (LegendPolicy.legendOf(pokemon) != null) return
        val kind = KINDS.firstOrNull { pokemon.hasLabels(it) } ?: return
        // A snack spawn may pass through both events; say it once.
        if (pokemon.persistentData.getBoolean(ANNOUNCED_KEY)) return
        pokemon.persistentData.putBoolean(ANNOUNCED_KEY, true)
        player.server.playerList.broadcastSystemMessage(Component.translatable(
            "message.${JbroPolicy.MOD_ID}.rare_spawn.$kind", player.displayName, pokemon.species.translatedName,
        ).withStyle(ChatFormatting.AQUA), false)
    }
}
