package jbro.cobblemon.policy.pokemon

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.util.party
import jbro.cobblemon.policy.JbroPolicy
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.UUIDUtil
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Right-clicking your own sent-out Pokemon opens the same summary as pressing M with it selected.
 *
 * Cobblemon decides on the server whether a held item (potions, candies, evolution items, dyes and so on) does
 * something to the Pokemon, so the server only asks the client to open the summary once that click went unused.
 * Sneaking keeps Cobblemon's interaction wheel. An item with a right-click use of its own (Poke Balls, food, bows,
 * the Pokedex and the like) keeps it, whatever mod it comes from; any other item opens the summary like an empty hand.
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
        if (!held.isEmpty && hasOwnUse(held, player)) return
        val pokemon = entity.pokemon
        if (pokemon.getOwnerPlayer() != player || pokemon !in player.party()) return
        ServerPlayNetworking.send(player, OpenSummaryPayload(pokemon.uuid))
    }

    /** Whether [stack] does something when used in the air: eaten, drawn or thrown, or an item that overrides `use`. */
    private fun hasOwnUse(stack: ItemStack, player: Player): Boolean =
        stack.getUseDuration(player) > 0 || overridesUse.computeIfAbsent(stack.item.javaClass) { type ->
            val use = USE ?: return@computeIfAbsent true
            try {
                type.getMethod(use.name, *use.parameterTypes).declaringClass != Item::class.java
            } catch (failure: ReflectiveOperationException) {
                true
            }
        }

    private val overridesUse = ConcurrentHashMap<Class<*>, Boolean>()

    /** `Item.use(Level, Player, InteractionHand)`, found by its parameters so it matches under any mapping. */
    private val USE: Method? = Item::class.java.declaredMethods.firstOrNull { method ->
        method.parameterTypes.contentEquals(arrayOf(Level::class.java, Player::class.java, InteractionHand::class.java))
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
