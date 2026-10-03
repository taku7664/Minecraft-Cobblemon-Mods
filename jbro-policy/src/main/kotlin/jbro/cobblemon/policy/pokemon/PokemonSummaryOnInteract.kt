package jbro.cobblemon.policy.pokemon

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.util.party
import jbro.cobblemon.policy.JbroPolicy
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.UUIDUtil
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import java.util.UUID

/**
 * Right-clicking your own sent-out Pokemon opens the same summary as pressing M with it selected.
 *
 * Cobblemon decides on the server whether a held item (potions, candies, evolution items, dyes and so on) does
 * something to the Pokemon, so the server only asks the client to open the summary once that click went unused.
 * Sneaking keeps Cobblemon's interaction wheel, and items from other mods keep their own right-click use
 * (Poke Balls, the Pokedex and the like), so only an empty hand or a vanilla item opens the summary.
 */
object PokemonSummaryOnInteract {
    fun register() {
        PayloadTypeRegistry.playS2C().register(OpenSummaryPayload.TYPE, OpenSummaryPayload.CODEC)
    }

    @JvmStatic
    fun onUnhandledInteract(entity: PokemonEntity, player: Player, hand: InteractionHand) {
        if (player !is ServerPlayer || hand != InteractionHand.MAIN_HAND || player.isShiftKeyDown || player.isSpectator) return
        if (entity.isBattleClone()) return
        val held = player.getItemInHand(hand)
        if (!held.isEmpty && BuiltInRegistries.ITEM.getKey(held.item).namespace != "minecraft") return
        val pokemon = entity.pokemon
        if (pokemon.getOwnerPlayer() != player || pokemon !in player.party()) return
        ServerPlayNetworking.send(player, OpenSummaryPayload(pokemon.uuid))
    }
}

/** Opens the summary on the party slot holding [pokemonId]. */
data class OpenSummaryPayload(val pokemonId: UUID) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<OpenSummaryPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<OpenSummaryPayload>(JbroPolicy.id("open_summary"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, OpenSummaryPayload> = StreamCodec.of(
            { buffer, payload -> UUIDUtil.STREAM_CODEC.encode(buffer, payload.pokemonId) },
            { buffer -> OpenSummaryPayload(UUIDUtil.STREAM_CODEC.decode(buffer)) },
        )
    }
}
