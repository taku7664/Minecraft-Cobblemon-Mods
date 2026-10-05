package jbro.cobblemon.mcc.internal.bp.shop

import io.netty.buffer.Unpooled
import java.util.UUID
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ShopPlayPayloadsTest {
    @Test
    fun `shop state open and multi line purchase payloads round trip`() {
        val state = ShopStatePayload(
            catalogId = "mcc_core",
            catalogRevision = "revision-1",
            balanceBp = 125L,
            limits = BattlePointShopLimits(16, 64, 64),
            entries = listOf(
                ShopEntryView("choice_band", "cobblemon:choice_band", 1, 25L, "held_item"),
                ShopEntryView("rare_candy", "cobblemon:rare_candy", 1, 3L, "consumable"),
                ShopEntryView("life_orb", "cobblemon:life_orb", 1, 25L, "held_item"),
            ),
            result = BattlePointShopPurchaseStatus.APPLIED,
            shopkeeper = listOf(
                BattlePointShopkeeperAppearance.Skin("rctmod:textures/trainers/single/clerk.png", true),
                BattlePointShopkeeperAppearance.Villager("cobblemon:nurse_joy", "minecraft:plains"),
            ),
            categories = listOf("consumable", "held_item"),
        )
        val purchase = ShopPurchasePayload(
            UUID(0, 10),
            "mcc_core",
            "revision-1",
            listOf(BattlePointShopCartLine("choice_band", 2), BattlePointShopCartLine("life_orb", 1)),
        )
        val leaderboard = HomeLeaderboardStatePayload(
            singles = listOf(HomeLeaderboardEntry(1, UUID(0, 1), "Alpha", totalWins = 40, totalLosses = 8, bestWinStreak = 10)),
            doubles = listOf(HomeLeaderboardEntry(1, UUID(0, 2), "Beta", totalWins = 28, totalLosses = 6, bestWinStreak = 8)),
        )
        val leaderboardCatalog = HomeLeaderboardCatalogPayload(
            boards = listOf(
                HomeLeaderboardBoard(
                    contentId = "more_cobblemon_contents:battle_tower",
                    formatId = "single",
                    entries = leaderboard.singles,
                ),
                HomeLeaderboardBoard(
                    contentId = "more_cobblemon_contents:pvp",
                    formatId = "double",
                    entries = listOf(
                        HomeLeaderboardEntry(
                            place = 1,
                            playerId = UUID(0, 3),
                            playerName = "Gamma",
                            totalWins = 18,
                            totalLosses = 4,
                            bestWinStreak = 6,
                        ),
                    ),
                ),
            ),
        )

        assertEquals(ShopOpenPayload, roundTrip(ShopOpenPayload.CODEC, ShopOpenPayload))
        assertEquals(state, roundTrip(ShopStatePayload.CODEC, state))
        assertEquals(purchase, roundTrip(ShopPurchasePayload.CODEC, purchase))
        assertEquals(leaderboard, roundTrip(HomeLeaderboardStatePayload.CODEC, leaderboard))
        assertEquals(leaderboardCatalog, roundTrip(HomeLeaderboardCatalogPayload.CODEC, leaderboardCatalog))
    }

    private fun <T : Any> roundTrip(
        codec: net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf, T>,
        value: T,
    ): T {
        val buffer = RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY)
        codec.encode(buffer, value)
        return codec.decode(buffer)
    }
}
