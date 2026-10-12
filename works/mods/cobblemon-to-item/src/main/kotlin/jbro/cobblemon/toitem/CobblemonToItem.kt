package jbro.cobblemon.toitem

import com.cobblemon.mod.common.Cobblemon
import com.mojang.brigadier.arguments.IntegerArgumentType
import jbro.cobblemon.toitem.api.PokemonItemData
import jbro.cobblemon.toitem.api.PokemonTransfers
import jbro.cobblemon.toitem.config.ToItemConfig
import jbro.cobblemon.toitem.network.RestoreInventoryPayload
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.commands.Commands
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResultHolder
import org.slf4j.LoggerFactory

object CobblemonToItem : ModInitializer {
    const val MOD_ID = "cobblemon_to_item"
    val LOGGER = LoggerFactory.getLogger(MOD_ID)
    fun id(path: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(MOD_ID, path)

    override fun onInitialize() {
        ToItemConfig.load()
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(Commands.literal(PokemonTransfers.COMMAND)
                .requires { it.hasPermission(ToItemConfig.permissionLevel) }
                .then(Commands.argument("slot", IntegerArgumentType.integer(1, 6)).executes { context ->
                    val player = context.source.playerOrException
                    val slot = IntegerArgumentType.getInteger(context, "slot") - 1
                    val pokemon = Cobblemon.storage.getParty(player).get(slot)
                    if (pokemon == null) {
                        context.source.sendFailure(net.minecraft.network.chat.Component.translatable("cobblemon_to_item.message.empty_slot"))
                        0
                    } else if (PokemonTransfers.itemize(player, slot, pokemon.uuid, dropOverflow = true)) 1 else 0
                }))
        }
        PayloadTypeRegistry.playC2S().register(RestoreInventoryPayload.TYPE, RestoreInventoryPayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(RestoreInventoryPayload.TYPE) { payload, context ->
            PokemonTransfers.restoreInventory(context.player(), payload.menuSlot, payload.pokemonId)
        }
        UseItemCallback.EVENT.register { player, _, hand ->
            val stack = player.getItemInHand(hand)
            if (!PokemonItemData.isPokemonItem(stack)) return@register InteractionResultHolder.pass(stack)
            if (player !is ServerPlayer) return@register InteractionResultHolder.success(stack)
            if (PokemonTransfers.restoreHeld(player, stack)) InteractionResultHolder.success(stack)
            else InteractionResultHolder.fail(stack)
        }
    }
}
