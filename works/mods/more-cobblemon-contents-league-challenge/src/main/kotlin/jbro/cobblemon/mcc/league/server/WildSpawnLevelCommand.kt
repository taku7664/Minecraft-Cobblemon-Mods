package jbro.cobblemon.mcc.league.server

import jbro.cobblemon.mcc.league.MoreCobblemonContentsLeagueChallenge as Mod
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.Commands.literal
import net.minecraft.network.chat.Component

/** `/구역스폰레벨`: any player reads the wild spawn band of the region they stand in, in chat. */
object WildSpawnLevelCommand {
    const val NAME = "구역스폰레벨"
    private const val KEY = "command.${Mod.MOD_ID}.region_spawn_level"

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(literal(NAME).executes { context ->
                val player = context.source.playerOrException
                val chunk = player.chunkPosition()
                val reading = try {
                    LeagueWildSpawns.read(player, player.serverLevel(), chunk.x, chunk.z)
                } catch (failure: RuntimeException) {
                    Mod.LOGGER.warn("Could not read the League wild spawn level for {}: {}", player.uuid, failure.message)
                    null
                }
                if (reading == null) {
                    context.source.sendFailure(Component.translatableWithFallback("$KEY.unavailable",
                        "지금은 구역 스폰 레벨을 읽을 수 없습니다."))
                    return@executes 0
                }
                context.source.sendSystemMessage(Component.translatableWithFallback(KEY,
                    "이 구역 스폰 레벨: %s~%s (구역 기준 %s, 내 레벨캡 %s)",
                    reading.range.first, reading.range.last, reading.regionLevel, reading.cap))
                1
            })
        }
    }
}
