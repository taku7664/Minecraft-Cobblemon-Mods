package jbro.cobblemon.partysidebar

import jbro.cobblemon.partysidebar.network.HeldItemClickPayload
import jbro.cobblemon.partysidebar.network.ItemizePayload
import jbro.cobblemon.partysidebar.network.PresetActionPayload
import jbro.cobblemon.partysidebar.network.PresetStatePayload
import jbro.cobblemon.partysidebar.network.RestorePayload
import jbro.cobblemon.partysidebar.preset.PresetServerConfig
import jbro.cobblemon.partysidebar.preset.PresetService
import jbro.cobblemon.partysidebar.preset.StorageCommand
import jbro.cobblemon.partysidebar.preset.StorageMenu
import jbro.cobblemon.partysidebar.server.HeldItemExchange
import jbro.cobblemon.partysidebar.server.PokemonItemize
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.resources.ResourceLocation
import org.slf4j.LoggerFactory

/**
 * The server half: Cobblemon has no packet that lets a client change a held item, so the sidebar's held-item slots
 * send [HeldItemClickPayload] and the server makes the swap. [ItemizePayload] turns a Pokemon into a Cobblemon To Item
 * item and [RestorePayload] places or exchanges one in a party slot. Cobblemon To Item owns both operations.
 * [PresetActionPayload] drives party presets and the item storage ([PresetService]); [PresetStatePayload] reports them.
 */
object CobblemonPartySidebar : ModInitializer {
    const val MOD_ID = "cobblemon_party_sidebar"
    val LOGGER = LoggerFactory.getLogger("Cobblemon Party Sidebar")

    fun id(path: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(MOD_ID, path)

    override fun onInitialize() {
        PresetServerConfig.load()
        StorageMenu.register()
        PayloadTypeRegistry.playC2S().register(HeldItemClickPayload.TYPE, HeldItemClickPayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(HeldItemClickPayload.TYPE) { payload, context ->
            HeldItemExchange.handle(context.player(), payload)
        }
        PayloadTypeRegistry.playC2S().register(ItemizePayload.TYPE, ItemizePayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(ItemizePayload.TYPE) { payload, context ->
            PokemonItemize.itemize(context.player(), payload)
        }
        PayloadTypeRegistry.playC2S().register(RestorePayload.TYPE, RestorePayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(RestorePayload.TYPE) { payload, context ->
            PokemonItemize.restore(context.player(), payload)
        }
        PayloadTypeRegistry.playC2S().register(PresetActionPayload.TYPE, PresetActionPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(PresetStatePayload.TYPE, PresetStatePayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(PresetActionPayload.TYPE) { payload, context ->
            PresetService.handle(context.player(), payload)
        }
        ServerTickEvents.END_SERVER_TICK.register(PresetService::tick)
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> StorageCommand.register(dispatcher) }
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ -> PresetService.sync(handler.player, force = true) }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> PresetService.forget(handler.player) }
    }
}
