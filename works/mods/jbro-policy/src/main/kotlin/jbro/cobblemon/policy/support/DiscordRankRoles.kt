package jbro.cobblemon.policy.support

import java.util.UUID
import jbro.cobblemon.mcc.league.api.LeagueRanks
import jbro.cobblemon.policy.JbroPolicy
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer

/**
 * Keeps each linked member's League rank role on Discord the same as their rank in game: on `/verify`, on every rank
 * change (promotions and operator edits), on joining the game, and for everyone once the bot comes online, which
 * catches whatever changed while it was away. A member holds at most one rank role; a player with no rank holds none.
 *
 * Needs League Challenge and `rankRoleIds` in `config/jbro-policy-discord.json`; does nothing otherwise.
 */
internal object DiscordRankRoles {
    private var roles: Map<String, String> = emptyMap()

    @Volatile var enabled = false
        private set

    fun register(settings: DiscordSettings) {
        if (settings.rankRoleIds.isEmpty()) return
        if (!FabricLoader.getInstance().isModLoaded("more_cobblemon_contents_league_challenge")) {
            JbroPolicy.LOGGER.warn("rankRoleIds is set but League Challenge is not installed; rank roles stay off")
            return
        }
        roles = settings.rankRoleIds
        enabled = true
        LeagueRanks.onChange { _, player, rank -> push(player, rank.name) }
        ServerPlayConnectionEvents.JOIN.register { handler, _, server -> refresh(server, handler.player.uuid) }
    }

    /** Brings [player]'s linked member up to date. Call on the server thread. */
    fun refresh(server: MinecraftServer, player: UUID) {
        if (!enabled) return
        push(player, LeagueRanks.of(server, player)?.name)
    }

    /** Every linked member, after the bot (re)connects. Call on the server thread. */
    fun refreshAll(server: MinecraftServer) {
        if (!enabled) return
        DiscordLinks.all().forEach { (player, _) -> push(player, LeagueRanks.of(server, player)?.name) }
    }

    /** Takes every rank role off [discordId], whose Minecraft account is now linked to someone else. */
    fun clear(discordId: String) {
        if (enabled) DiscordBot.later { rest -> apply(rest, discordId, null) }
    }

    private fun push(player: UUID, rank: String?) {
        val discordId = DiscordLinks.discordOf(player) ?: return
        DiscordBot.later { rest -> apply(rest, discordId, rank) }
    }

    /** In each server the bot is in, gives the role for [rank] and takes the other rank roles away. */
    private fun apply(rest: DiscordRest, discordId: String, rank: String?) {
        val wanted = rank?.let { roles[it] }
        for (guild in DiscordBot.guilds) {
            val member = rest.request("GET", "/guilds/$guild/members/$discordId")
            if (member.status == 404) continue
            if (!member.ok) {
                JbroPolicy.LOGGER.warn("Could not read Discord member {} ({}): {}", discordId, member.status, member.body.take(200))
                continue
            }
            val held = com.google.gson.JsonParser.parseString(member.body).asJsonObject.getAsJsonArray("roles").map { it.asString }.toSet()
            for (role in roles.values.toSet()) {
                val change = when {
                    role == wanted && role !in held -> "PUT"
                    role != wanted && role in held -> "DELETE"
                    else -> continue
                }
                val response = rest.request(change, "/guilds/$guild/members/$discordId/roles/$role")
                if (!response.ok) JbroPolicy.LOGGER.warn("Could not {} rank role {} for {} ({}): {}",
                    if (change == "PUT") "give" else "remove", role, discordId, response.status, response.body.take(200))
            }
        }
    }
}
