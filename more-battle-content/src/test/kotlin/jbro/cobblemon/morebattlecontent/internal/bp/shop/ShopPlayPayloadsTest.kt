package jbro.cobblemon.morebattlecontent.internal.bp.shop

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
            catalogId = "mbc_core",
            catalogRevision = "revision-1",
            balanceBp = 125L,
            limits = BattlePointShopLimits(16, 64, 64),
            entries = listOf(
                ShopEntryView("choice_band", "cobblemon:choice_band", 1, 25L),
                ShopEntryView("life_orb", "cobblemon:life_orb", 1, 25L),
            ),
            result = BattlePointShopPurchaseStatus.APPLIED,
        )
        val purchase = ShopPurchasePayload(
            UUID(0, 10),
            "mbc_core",
            "revision-1",
            listOf(BattlePointShopCartLine("choice_band", 2), BattlePointShopCartLine("life_orb", 1)),
        )
        val leaderboard = HomeLeaderboardStatePayload(
            singles = listOf(HomeLeaderboardEntry(1, UUID(0, 1), "Alpha", 10, 2, 40)),
            doubles = listOf(HomeLeaderboardEntry(1, UUID(0, 2), "Beta", 8, 3, 28)),
        )
        val leaderboardCatalog = HomeLeaderboardCatalogPayload(
            boards = listOf(
                HomeLeaderboardBoard(
                    contentId = "battle_tower",
                    formatId = "single",
                    entries = leaderboard.singles,
                ),
                HomeLeaderboardBoard(
                    contentId = "pvp",
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
