package jbro.cobblemon.dimensions.client

import jbro.cobblemon.dimensions.DimensionTravel
import jbro.cobblemon.dimensions.ModDimension
import jbro.cobblemon.dimensions.portal.PortalFrames
import jbro.cobblemon.dimensions.wormhole.UltraWormhole
import jbro.cobblemon.dimensions.wormhole.Wormholes
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Difficulty
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.GameType
import net.minecraft.world.level.LevelSettings
import net.minecraft.world.level.WorldDataConfiguration
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.WorldOptions
import net.minecraft.world.level.levelgen.presets.WorldPresets
import net.minecraft.world.phys.Vec3
import org.slf4j.LoggerFactory

/**
 * Development captures of the dimensions, run with `gradlew :cobblemon-dimensions:runCapture`. It makes a fresh
 * creative world (no experimental-settings prompt that way), shoots the portals, a wormhole and views of each
 * dimension from the air, writes `run/screenshots/cdim-*.png`, and quits. Off unless `cobblemon_dimensions.capture`
 * is set.
 */
object DimensionCaptureHarness {
    private val logger = LoggerFactory.getLogger("cobblemon_dimensions")
    private const val WORLD = "cdim-capture"

    /** One picture: where to stand, where to look, and what to set up first. */
    private data class Shot(val name: String, val dimension: ModDimension?, val x: Int, val z: Int, val height: Int,
                            val yaw: Float, val pitch: Float, val setup: (ServerPlayer) -> Unit = {})

    private val allShots = listOf(
        Shot("portals", null, 0, 0, 9, 0f, 45f) { player -> buildPortals(player) },
        Shot("wormhole", null, 0, 0, 3, 0f, -25f) { player -> openWormhole(player) },
        Shot("ancient-1", ModDimension.ANCIENT, 1200, 900, 60, 30f, 30f),
        Shot("ancient-2", ModDimension.ANCIENT, 2400, -1600, 60, 200f, 30f),
        Shot("ancient-3", ModDimension.ANCIENT, -2800, -900, 60, 120f, 30f),
        Shot("future-1", ModDimension.FUTURE, 1200, 900, 60, 30f, 30f),
        Shot("future-2", ModDimension.FUTURE, -2000, 2600, 60, 120f, 30f),
        Shot("future-3", ModDimension.FUTURE, 3600, -2400, 60, 300f, 30f),
        Shot("ultra-1", ModDimension.ULTRA_SPACE, 1500, 600, 12, 30f, 35f),
        Shot("ultra-2", ModDimension.ULTRA_SPACE, 3000, 3000, 12, 250f, 35f),
        Shot("ultra-3", ModDimension.ULTRA_SPACE, -2600, 1800, 12, 140f, 35f),
    )

    /** `CDIM_CAPTURE_ONLY=portals,wormhole` retakes just those shots. */
    private val shots = System.getenv("CDIM_CAPTURE_ONLY")?.split(',')?.map(String::trim)?.toSet()
        ?.let { only -> allShots.filter { it.name in only } } ?: allShots

    fun installIfRequested() {
        if (System.getProperty("cobblemon_dimensions.capture") != "true") return
        var created = false
        var index = 0
        var waited = 0
        var placed = false
        var shooting = false
        var shotTaken = false
        // Set on the server thread once the player stands where the shot wants them; entering generates chunks first.
        val arrived = AtomicBoolean(false)
        // Where the server put the player; the client holds them there so they cannot drop before flight syncs.
        val standAt = AtomicReference<Vec3>()

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            // The first launch in a fresh run folder asks for accessibility and language settings; take the defaults.
            if (client.screen is AccessibilityOnboardingScreen) {
                client.options.onboardAccessibility = false
                client.options.save()
                client.setScreen(TitleScreen(true))
                return@register
            }
            // Another window taking focus would pause the game, and the server tasks below with it.
            client.options.pauseOnLostFocus = false
            if (client.screen is PauseScreen) client.setScreen(null)
            if (!created) {
                if (client.screen !is TitleScreen) return@register
                created = true
                createWorld(client)
                return@register
            }
            val server = client.singleplayerServer ?: return@register
            if (client.level == null || client.player == null || client.screen != null) return@register
            if (index >= shots.size) {
                logger.info("Dimension captures done")
                client.stop()
                return@register
            }
            val shot = shots[index]
            if (!placed) {
                placed = true
                waited = 0
                client.options.hideGui = true
                arrived.set(false)
                val uuid = client.player!!.uuid
                server.execute {
                    val player = server.playerList.getPlayer(uuid) ?: return@execute
                    place(player, shot)
                    standAt.set(player.position())
                    arrived.set(true)
                }
                return@register
            }
            val expected = shot.dimension?.key ?: net.minecraft.world.level.Level.OVERWORLD
            if (!arrived.get() || client.level!!.dimension() != expected) return@register
            waited++
            // Hold the camera: a hand on the mouse or a resized window must not turn the shot.
            client.mouseHandler.releaseMouse()
            client.player!!.apply {
                yRot = shot.yaw; yRotO = shot.yaw; xRot = shot.pitch; xRotO = shot.pitch
                yHeadRot = shot.yaw; yHeadRotO = shot.yaw
                abilities.flying = true
                standAt.get()?.let { setPos(it.x, it.y, it.z) }
                deltaMovement = Vec3.ZERO
            }
            // Let the chunks generate and draw; Terralith terrain takes a while on a fresh world.
            val rendered = client.levelRenderer.hasRenderedAllSections()
            if (!shooting && waited >= 400 && (rendered || waited >= 1600)) {
                shooting = true
                val file = "cdim-${shot.name}.png"
                Screenshot.grab(client.gameDirectory, file, client.mainRenderTarget) { result ->
                    logger.info("Dimension capture {}: {}", file, result.string)
                    shotTaken = true
                }
            }
            if (shotTaken) {
                index++
                placed = false
                shooting = false
                shotTaken = false
            }
        }
    }

    private fun createWorld(client: Minecraft) {
        client.window.setWindowed(1600, 900)
        client.options.renderDistance().set(12)
        val saves = client.levelSource.baseDir.resolve(WORLD).toFile()
        if (saves.exists()) saves.deleteRecursively()
        val rules = GameRules().apply {
            getRule(GameRules.RULE_DAYLIGHT).set(false, null)
            getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null)
        }
        val settings = LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT)
        client.createWorldOpenFlows().createFreshLevel(WORLD, settings, WorldOptions(20261006L, false, false), { registries ->
            registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions()
        }, TitleScreen())
        logger.info("Creating the capture world")
    }

    private fun place(player: ServerPlayer, shot: Shot) {
        player.server.overworld().dayTime = 6000
        player.abilities.flying = true
        player.onUpdateAbilities()
        val level: ServerLevel = if (shot.dimension == null) {
            player.server.overworld()
        } else {
            DimensionTravel.enter(player, shot.dimension, shot.x, shot.z)
            player.serverLevel()
        }
        val x = if (shot.dimension == null) shot.x else player.blockX
        val z = if (shot.dimension == null) shot.z else player.blockZ
        val ground = level.getChunk(x shr 4, z shr 4).getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x and 15, z and 15) + 1
        player.teleportTo(level, x + 0.5, (ground + shot.height).toDouble(), z + 0.5, shot.yaw, shot.pitch)
        shot.setup(player)
    }

    /** An ancient and a future portal side by side on the ground below the player, lit. */
    private fun buildPortals(player: ServerPlayer) {
        val level = player.serverLevel()
        val y = level.getChunk(0, 0).getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0) + 1
        for ((cx, frame) in listOf(-4 to Blocks.CHISELED_DEEPSLATE, 4 to Blocks.OXIDIZED_CHISELED_COPPER)) {
            for (dx in -3..3) for (dz in -3..3) for (dy in 0..4) {
                level.setBlockAndUpdate(BlockPos(cx + dx, y + dy, 6 + dz), Blocks.AIR.defaultBlockState())
            }
            for (k in -1..1) for ((ox, oz) in listOf(-2 to k, 2 to k, k to -2, k to 2)) {
                level.setBlockAndUpdate(BlockPos(cx + ox, y, 6 + oz), frame.defaultBlockState())
            }
            PortalFrames.tryLight(level, BlockPos(cx - 2, y, 6))
        }
    }

    /** A personal-sized wormhole hanging in front of the camera. */
    private fun openWormhole(player: ServerPlayer) {
        val hole = UltraWormhole(Wormholes.TYPE, player.serverLevel())
        hole.radius = Wormholes.settings.greatRadius
        hole.lifetime = 20 * 600
        hole.setPos(player.x, player.y + 9, player.z + 16)
        player.serverLevel().addFreshEntity(hole)
    }
}
