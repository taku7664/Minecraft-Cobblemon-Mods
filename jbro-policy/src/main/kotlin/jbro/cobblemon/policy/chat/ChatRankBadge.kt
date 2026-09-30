package jbro.cobblemon.policy.chat

import jbro.cobblemon.mcc.league.api.LeagueRanks
import jbro.cobblemon.mcc.league.ui.LeagueRank
import jbro.cobblemon.policy.JbroPolicy
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.ChatType
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer

/** Player chat reads `[ball rank] name: message`, with the sender's League rank in front of their name. */
object ChatRankBadge {
    private val FONT = ResourceLocation.fromNamespaceAndPath(JbroPolicy.MOD_ID, "rank_icons")
    /** `name: message` in place of vanilla's `<name> message`; defined in the mod's data. */
    private val CHAT: ResourceKey<ChatType> = ResourceKey.create(Registries.CHAT_TYPE, JbroPolicy.id("chat"))
    private const val RANK_KEY_PREFIX = "screen.more_cobblemon_contents_league_challenge.home.rank."
    private val leagueLoaded by lazy { FabricLoader.getInstance().isModLoaded("more_cobblemon_contents_league_challenge") }

    /** Called by the chat mixin with the chat bound to [player]. */
    @JvmStatic
    fun decorate(player: ServerPlayer, bound: ChatType.Bound): ChatType.Bound {
        val chatType = player.server.registryAccess().registryOrThrow(Registries.CHAT_TYPE).getHolder(CHAT).orElse(null)
            ?: return bound
        return ChatType.Bound(chatType, sender(player, bound.name()), bound.targetName())
    }

    private fun sender(player: ServerPlayer, name: Component): Component {
        val rank = rankOf(player) ?: return name
        return Component.empty()
            .append(Component.literal("[").withStyle(ChatFormatting.GRAY))
            .append(icon(rank)).append(" ")
            .append(Component.translatable(RANK_KEY_PREFIX + rank.name.lowercase()).withStyle(color(rank)))
            .append(Component.literal("] ").withStyle(ChatFormatting.GRAY))
            .append(name)
    }

    private fun rankOf(player: ServerPlayer): LeagueRank? {
        if (!leagueLoaded) return null
        // Chat must never fail because League storage did.
        return try { LeagueRanks.of(player.server, player.uuid) } catch (failure: RuntimeException) {
            JbroPolicy.LOGGER.debug("No League rank for {}: {}", player.uuid, failure.message)
            null
        }
    }

    /** Each ball's own colour; Champions get gold. */
    private fun color(rank: LeagueRank): ChatFormatting = when (rank) {
        LeagueRank.POKE_BALL -> ChatFormatting.RED
        LeagueRank.GREAT_BALL -> ChatFormatting.BLUE
        LeagueRank.ULTRA_BALL -> ChatFormatting.YELLOW
        LeagueRank.MASTER_BALL -> ChatFormatting.LIGHT_PURPLE
        LeagueRank.CHAMPION -> ChatFormatting.GOLD
    }

    private fun icon(rank: LeagueRank): MutableComponent =
        Component.literal(RankIcon.of(rank).glyph).withStyle(Style.EMPTY.withFont(FONT).withColor(ChatFormatting.WHITE))
}
