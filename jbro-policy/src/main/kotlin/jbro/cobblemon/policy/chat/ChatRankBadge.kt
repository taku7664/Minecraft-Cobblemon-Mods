package jbro.cobblemon.policy.chat

import jbro.cobblemon.mcc.league.api.LeagueRanks
import jbro.cobblemon.mcc.league.ui.LeagueRank
import jbro.cobblemon.policy.JbroPolicy
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer

/** Puts the sender's League rank ball in front of their name in player chat. */
object ChatRankBadge {
    private val FONT = ResourceLocation.fromNamespaceAndPath(JbroPolicy.MOD_ID, "rank_icons")
    private const val RANK_KEY_PREFIX = "screen.more_cobblemon_contents_league_challenge.home.rank."
    private val leagueLoaded by lazy { FabricLoader.getInstance().isModLoaded("more_cobblemon_contents_league_challenge") }

    @JvmStatic
    fun decorate(player: ServerPlayer, name: Component): Component {
        val rank = rankOf(player) ?: return name
        return Component.empty().append(icon(rank)).append(" ").append(name)
    }

    private fun rankOf(player: ServerPlayer): LeagueRank? {
        if (!leagueLoaded) return null
        // Chat must never fail because League storage did.
        return try { LeagueRanks.of(player.server, player.uuid) } catch (failure: RuntimeException) {
            JbroPolicy.LOGGER.debug("No League rank for {}: {}", player.uuid, failure.message)
            null
        }
    }

    private fun icon(rank: LeagueRank): MutableComponent = Component.literal(RankIcon.of(rank).glyph).withStyle(
        Style.EMPTY.withFont(FONT).withColor(ChatFormatting.WHITE)
            .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT,
                Component.translatable(RANK_KEY_PREFIX + rank.name.lowercase()))))
}
