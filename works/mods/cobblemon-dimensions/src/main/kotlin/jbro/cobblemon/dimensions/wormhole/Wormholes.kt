package jbro.cobblemon.dimensions.wormhole

import jbro.cobblemon.dimensions.CobblemonDimensions
import jbro.cobblemon.dimensions.DimensionAccess
import jbro.cobblemon.dimensions.DimensionTravel
import jbro.cobblemon.dimensions.ModDimension
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.level.Level

/** Ultra Wormholes: the entity, opening them over time, and what touching one does. */
object Wormholes {
    val TYPE: EntityType<UltraWormhole> = Registry.register(BuiltInRegistries.ENTITY_TYPE, CobblemonDimensions.id("ultra_wormhole"),
        EntityType.Builder.of(::UltraWormhole, MobCategory.MISC).sized(1f, 1f).clientTrackingRange(16).updateInterval(20)
            .noSave().fireImmune().build("ultra_wormhole"))

    lateinit var settings: WormholeSettings
        private set

    fun register() {
        settings = WormholeSettings.load(FabricLoader.getInstance().configDir.resolve("cobblemon-dimensions.json"))
        ServerTickEvents.END_SERVER_TICK.register(::tick)
    }

    fun touch(player: ServerPlayer, hole: UltraWormhole) {
        if (player.isOnPortalCooldown || player.isSpectator) return
        if (hole.returning) {
            player.setPortalCooldown()
            player.server.execute { DimensionTravel.returnHome(player) }
            return
        }
        if (!DimensionAccess.allowed(player)) {
            DimensionAccess.refuse(player)
            return
        }
        player.setPortalCooldown()
        player.server.execute {
            // The hole hangs in the sky: coming back should land on the ground below it, not inside it again.
            val entry = DimensionTravel.groundBelow(player.serverLevel(), player.x, player.z)
            if (DimensionTravel.enter(player, ModDimension.ULTRA_SPACE, player.blockX, player.blockZ, entry)) {
                player.level().playSound(null, player.blockPosition(), SoundEvents.PORTAL_TRAVEL, SoundSource.PLAYERS, 0.4f, 1.6f)
            }
        }
    }

    /**
     * Opens a hole [above] blocks over a spot near [near], where the sky is open around it. Returns null when no spot
     * near there is clear.
     */
    fun open(level: ServerLevel, near: BlockPos, minDistance: Int, maxDistance: Int, radius: Float, seconds: Int, returning: Boolean): UltraWormhole? {
        val random = level.random
        repeat(12) {
            val angle = random.nextDouble() * Math.PI * 2
            val distance = minDistance + random.nextDouble() * (maxDistance - minDistance)
            val x = near.x + Math.cos(angle) * distance
            val z = near.z + Math.sin(angle) * distance
            val y = (near.y + 15 + random.nextInt(16)).coerceAtMost(level.maxBuildHeight - radius.toInt() - 4).toDouble()
            if (!clear(level, BlockPos.containing(x, y, z), radius)) return@repeat
            val hole = UltraWormhole(TYPE, level)
            hole.radius = radius
            hole.lifetime = seconds * 20
            hole.returning = returning
            hole.setPos(x, y - 0.5, z)
            level.addFreshEntity(hole)
            return hole
        }
        return null
    }

    private fun clear(level: Level, center: BlockPos, radius: Float): Boolean {
        val r = radius.toInt() + 1
        for (dx in -r..r step r) for (dy in -r..r step r) for (dz in -r..r step r) {
            if (!level.getBlockState(center.offset(dx, dy, dz)).isAir) return false
        }
        return true
    }

    private fun tick(server: MinecraftServer) {
        val every = settings.checkSeconds.coerceAtLeast(1) * 20
        if (server.tickCount % every != 0) return
        val checksPerMinute = 60.0 / settings.checkSeconds.coerceAtLeast(1)
        val random = server.overworld().random
        val overworldPlayers = server.overworld().players().filter { !it.isSpectator }

        for (player in server.playerList.players) {
            if (player.isSpectator) continue
            val level = player.serverLevel()
            val inUltraSpace = level.dimension() == ModDimension.ULTRA_SPACE.key
            if (level.dimension() != Level.OVERWORLD && !inUltraSpace) continue
            // One hole near a player at a time.
            if (level.getEntities(TYPE, player.boundingBox.inflate(96.0)) { true }.isNotEmpty()) continue
            if (inUltraSpace) {
                if (random.nextDouble() < 1.0 / (settings.returnEveryMinutes * checksPerMinute)) {
                    open(level, player.blockPosition(), 6, 16, settings.returnRadius, settings.returnSeconds, returning = true)
                        ?.let { notifyNearby(player, it) }
                }
            } else if (random.nextDouble() < 1.0 / (settings.personalEveryMinutes * checksPerMinute)) {
                open(level, player.blockPosition(), 6, 16, settings.personalRadius, settings.personalSeconds, returning = false)
                    ?.let { notifyNearby(player, it) }
            }
        }

        if (overworldPlayers.isNotEmpty() && random.nextDouble() < 1.0 / (settings.greatEveryMinutes * checksPerMinute)) {
            val near = overworldPlayers[random.nextInt(overworldPlayers.size)]
            if (open(server.overworld(), near.blockPosition(), 48, 128, settings.greatRadius, settings.greatSeconds, returning = false) != null) {
                announceGreat(server)
            }
        }
    }

    /** A small hole is only for the player it opened by: a line over the hotbar and a soft sound, no coordinates. */
    fun notifyNearby(player: ServerPlayer, hole: UltraWormhole) {
        val key = if (hole.returning) "message.cobblemon_dimensions.return_wormhole" else "message.cobblemon_dimensions.small_wormhole"
        player.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.LIGHT_PURPLE), true)
        player.playNotifySound(SoundEvents.PORTAL_TRIGGER, SoundSource.AMBIENT, 0.35f, 1.6f)
    }

    /** A great hole is told to the whole server, without saying where. */
    fun announceGreat(server: MinecraftServer) {
        server.playerList.broadcastSystemMessage(Component.translatable("message.cobblemon_dimensions.great_wormhole")
            .withStyle(ChatFormatting.LIGHT_PURPLE), false)
    }
}
