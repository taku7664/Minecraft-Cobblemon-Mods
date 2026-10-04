package jbro.cobblemon.mcc.client

import jbro.cobblemon.mcc.internal.bp.shop.ShopPurchasePayload
import jbro.cobblemon.mcc.internal.bp.shop.ShopStatePayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

internal object ShopPlayClientNetworking {
    fun register() {
        MccClientSessionReset.onReset("shop client state", MccShopClient::clear)
        ClientPlayNetworking.registerGlobalReceiver(ShopStatePayload.TYPE) { payload, context ->
            context.client().execute { MccShopClient.accept(payload) }
        }
    }

    fun purchase(payload: ShopPurchasePayload) = ClientPlayNetworking.send(payload)
}
