package jbro.cobblemon.morebattlecontent.internal.compat.fabric

import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.Cobblemon173PvpLoungeGateway
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpArenaProtectionPolicy
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level

/**
 * Keeps the generated PvP arenas intact. Competitors are teleported in without a game mode change,
 * so a creative or survival player would otherwise be free to mine the arena around a live battle.
 */
internal object PvpLoungeProtection {
    fun registerServer() {
        PlayerBlockBreakEvents.BEFORE.register { level, player, _, _, _ -> !protects(level, player) }
        AttackBlockCallback.EVENT.register { player, level, _, _, _ -> refuseWhenProtected(level, player) }
        UseBlockCallback.EVENT.register { player, level, _, _ -> refuseWhenProtected(level, player) }
    }

    private fun refuseWhenProtected(level: Level, player: Player): InteractionResult =
        if (protects(level, player)) InteractionResult.FAIL else InteractionResult.PASS

    private fun protects(level: Level, player: Player): Boolean = PvpArenaProtectionPolicy.protects(
        inLoungeDimension = level.dimension() == Cobblemon173PvpLoungeGateway.LEVEL_KEY,
        hasBypassPermission = player.hasPermissions(PvpArenaProtectionPolicy.BYPASS_PERMISSION_LEVEL),
    )
}
