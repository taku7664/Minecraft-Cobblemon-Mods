package jbro.cobblemon.partysidebar

import jbro.cobblemon.partysidebar.network.HeldItemClickPayload
import jbro.cobblemon.partysidebar.server.HeldItemExchange
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.resources.ResourceLocation
import org.slf4j.LoggerFactory

/**
 * The server half: Cobblemon has no packet that lets a client change a held item, so the sidebar's held-item slots
 * send [HeldItemClickPayload] and the server makes the swap. Reordering uses Cobblemon's own party packets.
 */
object CobblemonPartySidebar : ModInitializer {
    const val MOD_ID = "cobblemon_party_sidebar"
    val LOGGER = LoggerFactory.getLogger("Cobblemon Party Sidebar")

    fun id(path: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(MOD_ID, path)

    override fun onInitialize() {
        PayloadTypeRegistry.playC2S().register(HeldItemClickPayload.TYPE, HeldItemClickPayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(HeldItemClickPayload.TYPE) { payload, context ->
            HeldItemExchange.handle(context.player(), payload)
        }
    }
}
