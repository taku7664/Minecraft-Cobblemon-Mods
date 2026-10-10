package jbro.cobblemon.policy.plaza

import com.cobblemon.mod.common.battles.BattleRegistry
import jbro.cobblemon.policy.JbroPolicy
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.ChatFormatting
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks

/** A safe hub dimension players travel to with `/plaza enter` and leave with `/plaza exit`. */
object Plaza {
    val DIMENSION: ResourceKey<Level> = ResourceKey.create(Registries.DIMENSION, JbroPolicy.id("plaza"))
    private const val KEY = "message.${JbroPolicy.MOD_ID}.plaza."

    fun isPlaza(level: Level): Boolean = level.dimension() == DIMENSION

    fun register() {
        PlazaProtection.register()
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(Commands.literal("plaza")
                .executes { it.source.sendFailure(message("usage")); 0 }
                .then(Commands.literal("enter").executes { enter(it.source.playerOrException) })
                .then(Commands.literal("exit").executes { exit(it.source.playerOrException) }))
        }
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            val level = server.getLevel(DIMENSION)
            if (level == null) JbroPolicy.LOGGER.error("Plaza dimension {} was not loaded", DIMENSION.location())
            else {
                ensurePlatform(level)
                PlazaBiomeMigration.start(level)
            }
        }
        ServerTickEvents.END_SERVER_TICK.register { server ->
            PlazaBiomeMigration.tick()
            if (server.tickCount % 10 != 0) return@register
            val level = server.getLevel(DIMENSION) ?: return@register
            // Nothing in the plaza takes damage, so a fall into the void would never end.
            for (player in level.players().toList()) {
                if (player.y < level.minBuildHeight - 8) goHome(player, "fell")
            }
        }
        ServerLivingEntityEvents.ALLOW_DEATH.register { entity, _, _ ->
            if (entity !is ServerPlayer || !isPlaza(entity.level())) return@register true
            entity.health = entity.maxHealth
            goHome(entity, "fatal")
            false
        }
        // However a player left the hubs, that trip is over and its return point must not steer a later exit.
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register { player, _, _ -> clearIfStale(player) }
        ServerPlayerEvents.AFTER_RESPAWN.register { _, player, _ -> clearIfStale(player) }
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ -> clearIfStale(handler.player) }
    }

    private fun clearIfStale(player: ServerPlayer) {
        if (HubReturnRule.isStale(player.level().dimension().location().toString(), JbroPolicy.config.plazaOtherHubDimensions)) {
            PlazaReturnPoints.get(player.server).remove(player.uuid)
        }
    }

    private fun enter(player: ServerPlayer): Int {
        if (isPlaza(player.level())) return fail(player, "already_here")
        if (inBattle(player)) return fail(player, "battle")
        val level = player.server.getLevel(DIMENSION) ?: return fail(player, "unavailable")
        val points = PlazaReturnPoints.get(player.server)
        val source = player.level().dimension()
        if (HubReturnRule.shouldSaveOnEntry(source.location().toString(), points[player.uuid] != null, JbroPolicy.config.plazaOtherHubDimensions)) {
            points[player.uuid] = ReturnPoint(source, player.x, SafeLanding.groundY(player.serverLevel(), player), player.z, player.yRot, player.xRot)
        }
        val spawn = JbroPolicy.config.plaza
        player.teleportTo(level, spawn.x, spawn.y, spawn.z, spawn.yaw, spawn.pitch)
        player.sendSystemMessage(message("arrived").withStyle(ChatFormatting.GREEN))
        return 1
    }

    private fun exit(player: ServerPlayer): Int {
        if (!isPlaza(player.level())) return fail(player, "exit_outside")
        if (inBattle(player)) return fail(player, "battle")
        goHome(player, "returned")
        return 1
    }

    /**
     * Back to the saved return point, to a free spot next to it when it is blocked, and otherwise to the player's
     * spawn. The plaza is never a dead end: the last resort teleports even when no spot around the spawn looks safe.
     */
    private fun goHome(player: ServerPlayer, reason: String) {
        val server = player.server
        val points = PlazaReturnPoints.get(server)
        val point = points[player.uuid]
        val level = point?.let { server.getLevel(it.dimension) }
        val landing = if (point != null && level != null) SafeLanding.find(level, player, point.x, point.y, point.z) else null
        if (point != null && level != null && landing != null) {
            player.teleportTo(level, landing.first, landing.second, landing.third, point.yaw, point.pitch)
            land(player)
            points.remove(player.uuid)
            player.sendSystemMessage(message(reason).withStyle(ChatFormatting.GREEN))
            return
        }
        val respawn = player.respawnPosition?.let { pos -> server.getLevel(player.respawnDimension)?.let { it to pos } }
        val (target, pos) = respawn ?: (server.overworld() to server.overworld().sharedSpawnPos)
        val (x, y, z) = SafeLanding.find(target, player, pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5)
            ?: Triple(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5)
        player.teleportTo(target, x, y, z, player.yRot, player.xRot)
        land(player)
        points.remove(player.uuid)
        val why = when {
            point == null -> "returned_to_spawn"
            level == null -> "return_dimension_missing"
            else -> "return_blocked"
        }
        player.sendSystemMessage(message(why).withStyle(ChatFormatting.YELLOW))
    }

    /**
     * Ends the fall the player was in. A fall into the plaza's void is a long one that the plaza's damage rule kept
     * harmless; carried home, it would be paid in full on the first block they touch.
     */
    private fun land(player: ServerPlayer) {
        player.resetFallDistance()
        player.deltaMovement = player.deltaMovement.multiply(1.0, 0.0, 1.0)
        player.setOnGround(true)
    }

    /** A 9x9 stone brick floor under the landing spot, so a fresh plaza is not a drop into the void. */
    private fun ensurePlatform(level: ServerLevel) {
        val spawn = JbroPolicy.config.plaza
        val feet = BlockPos.containing(spawn.x, spawn.y, spawn.z)
        if (!level.getBlockState(feet.below()).isAir) return
        for (dx in -4..4) for (dz in -4..4) level.setBlockAndUpdate(feet.offset(dx, -1, dz), Blocks.STONE_BRICKS.defaultBlockState())
        level.setBlockAndUpdate(feet, Blocks.AIR.defaultBlockState())
        level.setBlockAndUpdate(feet.above(), Blocks.AIR.defaultBlockState())
        JbroPolicy.LOGGER.info("Created the plaza landing platform at {}", feet)
    }

    private fun inBattle(player: ServerPlayer) = BattleRegistry.getBattleByParticipatingPlayer(player) != null

    private fun fail(player: ServerPlayer, key: String): Int {
        player.sendSystemMessage(message(key).withStyle(ChatFormatting.RED))
        return 0
    }

    private fun message(key: String) = Component.translatable(KEY + key)
}
