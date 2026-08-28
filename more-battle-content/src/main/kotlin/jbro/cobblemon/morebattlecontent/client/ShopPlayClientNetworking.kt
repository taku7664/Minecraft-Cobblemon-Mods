package jbro.cobblemon.morebattlecontent.client

import jbro.cobblemon.morebattlecontent.internal.bp.shop.ShopOpenPayload
import jbro.cobblemon.morebattlecontent.internal.bp.shop.HomeLeaderboardCatalogPayload
import jbro.cobblemon.morebattlecontent.internal.bp.shop.HomeLeaderboardStatePayload
import jbro.cobblemon.morebattlecontent.internal.bp.shop.ShopPurchasePayload
import jbro.cobblemon.morebattlecontent.internal.bp.shop.ShopStatePayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft

internal object ShopPlayClientNetworking {
    private var leaderboard: HomeLeaderboardStatePayload? = null
    private var leaderboardCatalog: HomeLeaderboardCatalogPayload? = null

    fun register() {
        MbcClientSessionReset.onReset("shop leaderboard") {
            leaderboard = null
            leaderboardCatalog = null
        }
        ClientPlayNetworking.registerGlobalReceiver(ShopStatePayload.TYPE) { payload, context ->
            context.client().execute {
                MbcBattleHubClientState.update(payload.balanceBp)
                val current = context.client().screen as? ShopScreen
                if (current == null) {
                    context.client().setScreen(ShopScreen(payload, leaderboard, leaderboardCatalog))
                } else {
                    current.applyState(payload)
                }
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(HomeLeaderboardStatePayload.TYPE) { payload, context ->
            context.client().execute {
                leaderboard = payload
                (context.client().screen as? ShopScreen)?.applyLeaderboard(payload)
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(HomeLeaderboardCatalogPayload.TYPE) { payload, context ->
            context.client().execute {
                leaderboardCatalog = payload
                (context.client().screen as? ShopScreen)?.applyLeaderboardCatalog(payload)
            }
        }
    }

    fun canOpen(): Boolean = ClientPlayNetworking.canSend(ShopOpenPayload.TYPE)

    fun open() {
        leaderboard = null
        leaderboardCatalog = null
        Minecraft.getInstance().setScreen(ShopScreen(null, null, null))
        ClientPlayNetworking.send(ShopOpenPayload)
    }

    fun purchase(payload: ShopPurchasePayload) = ClientPlayNetworking.send(payload)
}
