package jbro.cobblemon.ui.extended.transition

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.labels.CobblemonPokemonLabels
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexConsumer
import jbro.cobblemon.ui.extended.PanelConfig
import jbro.cobblemon.ui.extended.UIUtils
import jbro.cobblemon.ui.extended.ui.shared.BattleEntryPattern
import jbro.cobblemon.ui.extended.ui.shared.BattleSurfaceRenderer
import jbro.cobblemon.ui.extended.ui.shared.BattleUiTheme
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.RenderType
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import org.joml.Matrix4f
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The screen transition into a battle. [play] flashes and covers the screen, then holds it covered and reports it
 * covered, so whoever holds the battle can start it then; the battle's start (or [reveal], or a timeout) turns the
 * screen white and slowly fades it into the battle.
 *
 * It draws above every screen and the HUD with plain quads only, in the battle theme's pattern: square cells for
 * Theme 1, slanted bands for Theme 2. A wild battle's cells grow from the center, a trainer's sweep in from a corner,
 * and a legendary darkens the world, flashes three times with a shockwave each, slams black bars in and shakes as
 * cells in its type's colour close.
 */
object BattleEntryTransition {
    private class Run(val kind: BattleEntryKind, val accent: Int, val startedAt: Long, val onCovered: Runnable?) {
        var revealAt: Long? = null
        var reported = false
    }

    @Volatile
    private var run: Run? = null

    /** Whether a transition is on screen. */
    val active: Boolean get() = run != null

    /** Whether the screen is fully white and the battle may start. */
    val covered: Boolean
        get() = run?.let { it.revealAt == null && BattleEntryTimeline.ready(it.kind, Util.getMillis() - it.startedAt) }
            ?: false

    /** The legendary, mythical and Ultra Beast species get the legendary transition. */
    private val LEGENDARY_LABELS = setOf(CobblemonPokemonLabels.LEGENDARY, CobblemonPokemonLabels.MYTHICAL,
        CobblemonPokemonLabels.ULTRA_BEAST)

    /**
     * Plays the transition for a battle against [species] (a wild Pokémon), or a trainer when [trainer] is set:
     * the legendary transition in the species' type colour for legendaries, the plain one otherwise.
     */
    @JvmStatic
    @JvmOverloads
    fun play(species: ResourceLocation?, trainer: Boolean = false, blockInput: Boolean = true) {
        val found = species?.let { PokemonSpecies.getByIdentifier(it) }
        val legendary = !trainer && found != null && found.labels.any { it in LEGENDARY_LABELS }
        val kind = when {
            trainer -> BattleEntryKind.TRAINER
            legendary -> BattleEntryKind.LEGENDARY
            else -> BattleEntryKind.WILD
        }
        play(kind, species, null, blockInput)
    }

    /**
     * Plays [kind] against [species], whose primary type colours a legendary transition (the others keep the theme's
     * colours), and runs [onCovered] once the screen is covered. False when the transition is turned off, and
     * [onCovered] will not run.
     */
    @JvmStatic
    @JvmOverloads
    fun play(kind: BattleEntryKind, species: ResourceLocation?, onCovered: Runnable?, blockInput: Boolean = true): Boolean {
        val accent = if (kind == BattleEntryKind.LEGENDARY) {
            species?.let { PokemonSpecies.getByIdentifier(it) }?.let { UIUtils.getTypeColor(it.primaryType) }
        } else null
        return play(kind, accent, blockInput, onCovered)
    }

    /**
     * Plays [kind] in [accent], or the theme's colour for it. While [blockInput] holds, an empty screen keeps the
     * player from walking or acting until the battle opens.
     */
    @JvmStatic
    @JvmOverloads
    fun play(kind: BattleEntryKind, accent: Int? = null, blockInput: Boolean = true, onCovered: Runnable? = null): Boolean {
        if (!PanelConfig.enableBattleEntryTransition) return false
        val palette = BattleUiTheme.palette
        val color = accent ?: if (kind == BattleEntryKind.TRAINER) palette.opponent else palette.ally
        // A transition this one replaces still owes its holder an answer.
        run?.takeIf { !it.reported }?.let { report(it) }
        run = Run(kind, color, Util.getMillis(), onCovered)
        val client = Minecraft.getInstance()
        if (blockInput && client.screen == null) client.setScreen(BattleEntryScreen())
        return true
    }

    /** Fades the screen into what is behind it; nothing if no transition is holding. */
    @JvmStatic
    fun reveal() {
        val current = run ?: return
        if (current.revealAt != null) return
        // A reveal asked for early waits for the screen to turn white, so the transition always plays out.
        val elapsed = Util.getMillis() - current.startedAt
        current.revealAt = current.startedAt + maxOf(elapsed, BattleEntryTimeline.readyAt(current.kind))
        jbro.cobblemon.ui.extended.CobblemonUi.LOGGER.info("Battle entry {} reveals after {} ms (ready at {} ms)",
            current.kind.id, elapsed, BattleEntryTimeline.readyAt(current.kind))
        closeInputScreen()
    }

    /** The battle has started: show it. */
    @JvmStatic
    fun onBattleInitialize() = reveal()

    /** Ends at once, for a battle that will not come. */
    @JvmStatic
    fun cancel() {
        run = null
        closeInputScreen()
    }

    fun install() {
        HudRenderCallback.EVENT.register { context, _ ->
            if (Minecraft.getInstance().screen == null) render(context)
        }
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            ScreenEvents.afterRender(screen).register { _, context, _, _, _ -> render(context) }
        }
        // Checked on ticks as well as frames, so a minimised game still answers its holder.
        ClientTickEvents.END_CLIENT_TICK.register { _ -> check() }
    }

    private fun check() {
        val current = run ?: return
        val elapsed = Util.getMillis() - current.startedAt
        if (!current.reported && BattleEntryTimeline.ready(current.kind, elapsed)) report(current)
        if (current.revealAt == null && elapsed > BattleEntryTimeline.readyAt(current.kind) + BattleEntryTimeline.HOLD_TIMEOUT_MILLIS) {
            reveal()
        }
    }

    private fun report(current: Run) {
        current.reported = true
        try {
            current.onCovered?.run()
        } catch (failure: RuntimeException) {
            jbro.cobblemon.ui.extended.CobblemonUi.LOGGER.warn("Battle entry cover callback failed", failure)
        }
    }

    private fun closeInputScreen() {
        val client = Minecraft.getInstance()
        if (client.screen is BattleEntryScreen) client.setScreen(null)
    }

    private fun render(context: GuiGraphics) {
        check()
        val current = run ?: return
        val now = Util.getMillis()
        val elapsed = now - current.startedAt
        val revealAt = current.revealAt?.let { it - current.startedAt }
        val kind = current.kind
        if (BattleEntryTimeline.revealed(kind, elapsed, revealAt)) {
            run = null
            return
        }
        val width = context.guiWidth()
        val height = context.guiHeight()
        val cover = BattleEntryTimeline.cover(kind, elapsed)
        val reveal = if (BattleEntryTimeline.patternGone(kind, elapsed)) 1f else 0f
        val pose = context.pose()
        pose.pushPose()
        pose.translate(0f, 0f, 500f)

        // Behind the pattern: a legendary's darkened world, tinted at the edges in its colour.
        val dim = BattleEntryTimeline.dim(kind, elapsed)
        if (dim > 0f) {
            context.fill(0, 0, width, height, BattleSurfaceRenderer.withOpacity(0xFF000000.toInt(), dim * .55f))
            vignette(context, width, height, current.accent, dim)
        }

        // The shockwaves and the pattern shake with a legendary; the bars, flashes and white stay put.
        val shake = BattleEntryTimeline.shake(kind, elapsed) * (1f - cover * .6f)
        pose.pushPose()
        if (shake > 0f) {
            val amplitude = SHAKE_PIXELS * shake
            pose.translate(sin(elapsed * .09f) * amplitude, cos(elapsed * .123f) * amplitude * .7f, 0f)
        }
        BattleEntryTimeline.rings(kind, elapsed).forEach { ring(context, width, height, current.accent, it) }
        val pulse = if (reveal < 1f) BattleEntryTimeline.pulse(kind, elapsed) else 0f
        when {
            kind == BattleEntryKind.LEGENDARY -> if (reveal < 1f) {
                drawLegendaryCover(context, width, height, current.accent, elapsed, cover, pulse)
            }
            BattleUiTheme.palette.entryPattern == BattleEntryPattern.CELLS ->
                drawCells(context, width, height, kind, current.accent, cover, reveal, pulse)
            else -> drawStripes(context, width, height, current.accent, cover, reveal, pulse)
        }
        pose.popPose()

        val bars = BattleEntryTimeline.bars(kind, elapsed)
        if (bars > 0f) {
            val bar = (height * BAR_SHARE * bars).toInt()
            context.fill(0, 0, width, bar, 0xFF000000.toInt())
            context.fill(0, height - bar, width, height, 0xFF000000.toInt())
        }
        val flash = BattleEntryTimeline.flash(kind, elapsed)
        if (flash > 0f) {
            val strength = if (kind == BattleEntryKind.LEGENDARY) .95f else .85f
            context.fill(0, 0, width, height, BattleSurfaceRenderer.withOpacity(0xFFFFFFFF.toInt(), flash * strength))
        }
        if (kind == BattleEntryKind.LEGENDARY) {
            drawLegendaryReveal(context, width, height, current.accent, elapsed, revealAt)
        } else {
            val white = BattleEntryTimeline.white(kind, elapsed, revealAt)
            if (white > 0f) context.fill(0, 0, width, height, BattleSurfaceRenderer.withOpacity(0xFFFFFFFF.toInt(), white))
        }
        pose.popPose()
    }

    /**
     * Square cells in a checker of two tones, one quad each: a wild battle's grow from the center, a trainer's sweep in
     * from the top left.
     */
    private fun drawCells(context: GuiGraphics, width: Int, height: Int, kind: BattleEntryKind, accent: Int,
                          cover: Float, reveal: Float, pulse: Float) {
        val base = BattleUiTheme.palette.entryBase
        // The covered screen breathes: both tones lean toward the accent with the pulse.
        val lift = pulse * .18f
        val tones = intArrayOf(BattleSurfaceRenderer.interpolate(accent, base, .58f - lift),
            BattleSurfaceRenderer.interpolate(accent, base, .70f - lift))
        val cell = CELL
        val columns = ceil(width / cell.toFloat()).toInt() + 1
        val rows = ceil(height / cell.toFloat()).toInt() + 1
        val left = (width - columns * cell) / 2f
        val top = (height - rows * cell) / 2f
        val far = hypot(width / 2f, height / 2f)
        quads(context) { buffer, matrix ->
            for (row in 0 until rows) for (column in 0 until columns) {
                val centerX = left + column * cell + cell / 2f
                val centerY = top + row * cell + cell / 2f
                val distance = if (kind == BattleEntryKind.TRAINER) (centerX + centerY) / (width + height)
                else hypot(centerX - width / 2f, centerY - height / 2f) / far
                val share = BattleEntryTimeline.pieceSize(cover, reveal, distance)
                if (share <= 0f) continue
                // A little larger than the cell when full, so neighbours overlap and no seam shows.
                val half = (cell + 1) * share / 2f
                quad(buffer, matrix, centerX - half, centerY - half, centerX + half, centerY + half, tones[(row + column) % 2])
            }
        }
    }

    /** Slanted bands, dark and coloured in turn, that widen from their middles outward from the screen's center. */
    private fun drawStripes(context: GuiGraphics, width: Int, height: Int, accent: Int, cover: Float, reveal: Float,
                            pulse: Float) {
        val base = BattleUiTheme.palette.entryBase
        // The covered screen breathes: the dark bands warm and the coloured ones brighten with the pulse.
        val colors = intArrayOf(BattleSurfaceRenderer.interpolate(base, accent, pulse * .18f),
            BattleSurfaceRenderer.interpolate(accent, base, .2f - pulse * .15f))
        val shift = height * SLOPE / 2f
        val reach = ceil((width / 2f + shift) / BAND).toInt() + 1
        quads(context) { buffer, matrix ->
            for (band in -reach..reach) {
                val distance = abs(band) / reach.toFloat()
                val share = BattleEntryTimeline.pieceSize(cover, reveal, distance)
                if (share <= 0f) continue
                val half = (BAND / 2f + .6f) * share
                val middle = width / 2f + band * BAND
                val color = colors[Math.floorMod(band, 2)]
                // The top edge leans right of the bottom one.
                buffer.addVertex(matrix, middle + shift - half, 0f, 0f).setColor(color)
                buffer.addVertex(matrix, middle - shift - half, height.toFloat(), 0f).setColor(color)
                buffer.addVertex(matrix, middle - shift + half, height.toFloat(), 0f).setColor(color)
                buffer.addVertex(matrix, middle + shift + half, 0f, 0f).setColor(color)
            }
        }
    }

    /**
     * A legendary's cover: a bright slash crosses the screen on a diagonal, two near-black plates tinted in [accent]
     * slide in from either side and close on it, its seam glowing, and focus lines in [accent] stab in from the edges
     * toward the center. Once covered the lines reach deeper on each heartbeat of [pulse]. The white of the reveal
     * covers all of it, so it needs no clearing of its own.
     */
    private fun drawLegendaryCover(context: GuiGraphics, width: Int, height: Int, accent: Int, elapsed: Long,
                                   cover: Float, pulse: Float) {
        if (cover <= 0f) return
        val w = width.toFloat()
        val h = height.toFloat()
        val centerX = w / 2f
        val centerY = h / 2f
        val far = hypot(centerX, centerY)
        // The slash runs from the top right to the bottom left through the center.
        val dirX = SLASH_X
        val dirY = SLASH_Y
        val dirLength = hypot(dirX, dirY)
        val ux = dirX / dirLength
        val uy = dirY / dirLength
        // The normal points to the top left plate's side.
        val nx = -uy
        val ny = ux
        val reach = far * 1.6f
        val plate = BattleSurfaceRenderer.interpolate(accent, 0xFF000000.toInt(), .88f)
        val closed = BattleEntryTimeline.plates(cover)
        val gap = (1f - closed) * far * 1.25f
        val slash = BattleEntryTimeline.slash(cover)
        val glow = BattleSurfaceRenderer.interpolate(accent, 0xFFFFFFFF.toInt(), .55f)
        quads(context) { buffer, matrix ->
            // Each plate: a band reaching from the slash out past the screen on its side, held [gap] away from it.
            for (side in intArrayOf(1, -1)) {
                val offset = gap + 1f
                val nearX = centerX + nx * side * (offset - 1.5f)
                val nearY = centerY + ny * side * (offset - 1.5f)
                val farX = centerX + nx * side * (offset + reach)
                val farY = centerY + ny * side * (offset + reach)
                buffer.addVertex(matrix, nearX - ux * reach, nearY - uy * reach, 0f).setColor(plate)
                buffer.addVertex(matrix, farX - ux * reach, farY - uy * reach, 0f).setColor(plate)
                buffer.addVertex(matrix, farX + ux * reach, farY + uy * reach, 0f).setColor(plate)
                buffer.addVertex(matrix, nearX + ux * reach, nearY + uy * reach, 0f).setColor(plate)
            }
            // The slash: a blade of light drawn across from its top end, widest while the plates are open, then the
            // seam they close on.
            if (slash > 0f) {
                val thickness = 1.5f + 5f * (1f - closed) + 2f * pulse
                val alpha = (.65f + .35f * (1f - closed)).coerceAtMost(1f)
                val start = -reach
                val end = -reach + 2f * reach * slash
                val color = BattleSurfaceRenderer.withOpacity(glow, alpha)
                val core = BattleSurfaceRenderer.withOpacity(0xFFFFFFFF.toInt(), alpha)
                strip(buffer, matrix, centerX, centerY, ux, uy, nx, ny, start, end, thickness * 2.2f, color)
                strip(buffer, matrix, centerX, centerY, ux, uy, nx, ny, start, end, thickness * .7f, core)
            }
            // Focus lines from the edges toward the center, each its own length, width and flicker.
            val lineColor = BattleSurfaceRenderer.interpolate(accent, 0xFFFFFFFF.toInt(), .3f)
            val flicker = (elapsed / 70L).toInt()
            for (line in 0 until FOCUS_LINES) {
                val seed = line * 7919 + 13
                val angle = (line + hash(seed) * .8f) / FOCUS_LINES * TAU
                val depth = (.38f + .32f * hash(seed + 1)) * (.35f + .65f * cover) + .18f * pulse
                val inner = far * (1.05f - depth)
                val outer = far * 1.25f
                val half = 2.5f + 5f * hash(seed + 2)
                val alpha = cover * (.45f + .45f * hash(seed + flicker * 31))
                val color = BattleSurfaceRenderer.withOpacity(lineColor, alpha.coerceIn(0f, 1f))
                val cx = cos(angle)
                val cy = sin(angle)
                val tipX = centerX + cx * inner
                val tipY = centerY + cy * inner
                val baseX = centerX + cx * outer
                val baseY = centerY + cy * outer
                // A wedge: a quad whose two inner corners meet at the tip.
                buffer.addVertex(matrix, tipX, tipY, 0f).setColor(color)
                buffer.addVertex(matrix, baseX - cy * half, baseY + cx * half, 0f).setColor(color)
                buffer.addVertex(matrix, baseX + cy * half, baseY - cx * half, 0f).setColor(color)
                buffer.addVertex(matrix, tipX, tipY, 0f).setColor(color)
            }
        }
    }

    /**
     * A legendary's reveal: the slash swells until its light covers the whole screen, then the white cracks from just
     * above the center and shatters, its shards flung outward and falling as they fade, nearest the center first.
     */
    private fun drawLegendaryReveal(context: GuiGraphics, width: Int, height: Int, accent: Int, elapsed: Long,
                                    revealAt: Long?) {
        val kind = BattleEntryKind.LEGENDARY
        if (elapsed < BattleEntryTimeline.riseStart(kind)) return
        val w = width.toFloat()
        val h = height.toFloat()
        val centerX = w / 2f
        val centerY = h / 2f
        val far = hypot(centerX, centerY)
        val glow = BattleSurfaceRenderer.interpolate(accent, 0xFFFFFFFF.toInt(), .6f)
        if (!BattleEntryTimeline.patternGone(kind, elapsed)) {
            val length = hypot(SLASH_X, SLASH_Y)
            val ux = SLASH_X / length
            val uy = SLASH_Y / length
            val beam = BattleEntryTimeline.beam(kind, elapsed)
            val thickness = 7f + far * 2.3f * beam
            quads(context) { buffer, matrix ->
                strip(buffer, matrix, centerX, centerY, ux, uy, -uy, ux, -far * 1.6f, far * 1.6f, thickness * 1.3f + 10f,
                    BattleSurfaceRenderer.withOpacity(glow, .55f + .45f * beam))
                strip(buffer, matrix, centerX, centerY, ux, uy, -uy, ux, -far * 1.6f, far * 1.6f, thickness, 0xFFFFFFFF.toInt())
            }
            return
        }
        val impactX = centerX
        val impactY = h * .46f
        val shatter = BattleEntryTimeline.shatter(kind, elapsed, revealAt)
        val crack = BattleEntryTimeline.crack(kind, elapsed, revealAt)
        if (shatter <= 0f) {
            context.fill(0, 0, width, height, 0xFFFFFFFF.toInt())
            drawCracks(context, impactX, impactY, far, accent, crack, 1f)
            return
        }
        // The white tiled in jittered triangles that together cover the screen exactly until they start to move.
        val cellW = w / SHARD_COLUMNS
        val cellH = h / SHARD_ROWS
        fun corner(column: Int, row: Int): Pair<Float, Float> {
            val inside = column in 1 until SHARD_COLUMNS && row in 1 until SHARD_ROWS
            val seed = column * 92821 + row * 68917
            val jitterX = if (inside) (hash(seed) - .5f) * cellW * .7f else 0f
            val jitterY = if (inside) (hash(seed + 5) - .5f) * cellH * .7f else 0f
            return column * cellW + jitterX to row * cellH + jitterY
        }
        quads(context) { buffer, matrix ->
            for (row in 0 until SHARD_ROWS) for (column in 0 until SHARD_COLUMNS) {
                val a = corner(column, row)
                val b = corner(column + 1, row)
                val c = corner(column + 1, row + 1)
                val d = corner(column, row + 1)
                val seed = column * 7333 + row * 1291
                val shards = if (hash(seed) < .5f) listOf(listOf(a, b, c), listOf(a, c, d)) else listOf(listOf(a, b, d), listOf(b, c, d))
                shards.forEachIndexed { index, shard -> drawShard(buffer, matrix, shard, seed * 3 + index, impactX, impactY,
                    far, accent, shatter) }
            }
        }
        // The cracks stay on the shards for a moment as they part.
        drawCracks(context, impactX, impactY, far, accent, crack, 1f - shatter * 4f)
    }

    /**
     * One shard of a legendary's white: from the center outward each shard in turn drifts a little away from the
     * impact at an even pace, turning slightly, and fades as it goes.
     */
    private fun drawShard(buffer: VertexConsumer, matrix: Matrix4f, points: List<Pair<Float, Float>>, seed: Int,
                          impactX: Float, impactY: Float, far: Float, accent: Int, shatter: Float) {
        val cx = points.sumOf { it.first.toDouble() }.toFloat() / 3f
        val cy = points.sumOf { it.second.toDouble() }.toFloat() / 3f
        val dx = cx - impactX
        val dy = cy - impactY
        val distance = (hypot(dx, dy) / far).coerceAtMost(1f)
        // Shards near the impact part first; the parting spreads outward across the screen.
        val delay = distance * SHARD_SPREAD
        val t = ((shatter - delay) / (1f - SHARD_SPREAD)).coerceIn(0f, 1f)
        val alpha = 1f - t
        if (alpha <= 0f) return
        val norm = hypot(dx, dy).coerceAtLeast(1f)
        val push = far * (.04f + .05f * hash(seed + 1)) * t
        val moveX = dx / norm * push
        val moveY = dy / norm * push
        val spin = (hash(seed + 2) - .5f) * .5f * t
        val scale = 1f - .08f * t
        val spinCos = cos(spin)
        val spinSin = sin(spin)
        val tint = BattleSurfaceRenderer.interpolate(0xFFFFFFFF.toInt(), accent, .06f + .14f * hash(seed + 3))
        val color = BattleSurfaceRenderer.withOpacity(tint, alpha)
        val placed = points.map { (x, y) ->
            val rx = (x - cx) * scale
            val ry = (y - cy) * scale
            (cx + moveX + rx * spinCos - ry * spinSin) to (cy + moveY + rx * spinSin + ry * spinCos)
        }
        buffer.addVertex(matrix, placed[0].first, placed[0].second, 0f).setColor(color)
        buffer.addVertex(matrix, placed[1].first, placed[1].second, 0f).setColor(color)
        buffer.addVertex(matrix, placed[2].first, placed[2].second, 0f).setColor(color)
        buffer.addVertex(matrix, placed[2].first, placed[2].second, 0f).setColor(color)
    }

    /** Cracks running out from the impact across the white, each a jagged line of a few segments. */
    private fun drawCracks(context: GuiGraphics, impactX: Float, impactY: Float, far: Float, accent: Int, growth: Float,
                           opacity: Float) {
        if (growth <= 0f || opacity <= 0f) return
        val color = BattleSurfaceRenderer.withOpacity(BattleSurfaceRenderer.interpolate(accent, 0xFF000000.toInt(), .45f),
            .7f * opacity.coerceAtMost(1f))
        quads(context) { buffer, matrix ->
            for (crack in 0 until CRACKS) {
                val seed = crack * 4099 + 7
                var angle = (crack + hash(seed) * .7f) / CRACKS * TAU
                var x = impactX
                var y = impactY
                val segments = CRACK_SEGMENTS * growth
                for (segment in 0 until CRACK_SEGMENTS) {
                    val share = (segments - segment).coerceIn(0f, 1f)
                    if (share <= 0f) break
                    angle += (hash(seed + segment * 13 + 1) - .5f) * .7f
                    val length = far * (.12f + .14f * hash(seed + segment * 13 + 2)) * share
                    val nx = x + cos(angle) * length
                    val ny = y + sin(angle) * length
                    val dx = nx - x
                    val dy = ny - y
                    val norm = hypot(dx, dy).coerceAtLeast(.001f)
                    val half = .8f * (1f - segment / CRACK_SEGMENTS.toFloat()) + .35f
                    val px = -dy / norm * half
                    val py = dx / norm * half
                    buffer.addVertex(matrix, x + px, y + py, 0f).setColor(color)
                    buffer.addVertex(matrix, x - px, y - py, 0f).setColor(color)
                    buffer.addVertex(matrix, nx - px, ny - py, 0f).setColor(color)
                    buffer.addVertex(matrix, nx + px, ny + py, 0f).setColor(color)
                    x = nx
                    y = ny
                }
            }
        }
    }

    /** A band along the slash's direction, from [start] to [end] along it, [thickness] wide. */
    private fun strip(buffer: VertexConsumer, matrix: Matrix4f, centerX: Float, centerY: Float, ux: Float, uy: Float,
                      nx: Float, ny: Float, start: Float, end: Float, thickness: Float, color: Int) {
        val half = thickness / 2f
        buffer.addVertex(matrix, centerX + ux * start + nx * half, centerY + uy * start + ny * half, 0f).setColor(color)
        buffer.addVertex(matrix, centerX + ux * start - nx * half, centerY + uy * start - ny * half, 0f).setColor(color)
        buffer.addVertex(matrix, centerX + ux * end - nx * half, centerY + uy * end - ny * half, 0f).setColor(color)
        buffer.addVertex(matrix, centerX + ux * end + nx * half, centerY + uy * end + ny * half, 0f).setColor(color)
    }

    /** A steady pseudo-random share, 0 to 1, for [seed]. */
    private fun hash(seed: Int): Float {
        var x = seed * -0x61c88647
        x = x xor (x ushr 15)
        x *= 0x2c1b3c6d
        x = x xor (x ushr 12)
        return (x ushr 8) / 16777216f
    }

    /** A shockwave: a ring from the center past the corners, thinning and fading as it goes. */
    private fun ring(context: GuiGraphics, width: Int, height: Int, accent: Int, progress: Float) {
        val far = hypot(width / 2f, height / 2f) * 1.1f
        val eased = 1f - (1f - progress) * (1f - progress)
        val outer = far * eased
        val inner = (outer - (10f - 6f * progress)).coerceAtLeast(0f)
        val color = BattleSurfaceRenderer.withOpacity(BattleSurfaceRenderer.interpolate(accent, 0xFFFFFFFF.toInt(), .45f),
            (1f - progress) * .9f)
        val centerX = width / 2f
        val centerY = height / 2f
        quads(context) { buffer, matrix ->
            for (segment in 0 until RING_SEGMENTS) {
                val from = segment * TAU / RING_SEGMENTS
                val to = (segment + 1) * TAU / RING_SEGMENTS
                buffer.addVertex(matrix, centerX + cos(from) * outer, centerY + sin(from) * outer, 0f).setColor(color)
                buffer.addVertex(matrix, centerX + cos(from) * inner, centerY + sin(from) * inner, 0f).setColor(color)
                buffer.addVertex(matrix, centerX + cos(to) * inner, centerY + sin(to) * inner, 0f).setColor(color)
                buffer.addVertex(matrix, centerX + cos(to) * outer, centerY + sin(to) * outer, 0f).setColor(color)
            }
        }
    }

    /** The screen's edges in [accent], fading toward the middle. */
    private fun vignette(context: GuiGraphics, width: Int, height: Int, accent: Int, strength: Float) {
        val edge = BattleSurfaceRenderer.withOpacity(accent, strength * .55f)
        val clear = BattleSurfaceRenderer.withOpacity(accent, 0f)
        val depthX = width * .22f
        val depthY = height * .26f
        val w = width.toFloat()
        val h = height.toFloat()
        quads(context) { buffer, matrix ->
            gradient(buffer, matrix, 0f, 0f, w, depthY, edge, edge, clear, clear)
            gradient(buffer, matrix, 0f, h - depthY, w, h, clear, clear, edge, edge)
            gradient(buffer, matrix, 0f, 0f, depthX, h, edge, clear, clear, edge)
            gradient(buffer, matrix, w - depthX, 0f, w, h, clear, edge, edge, clear)
        }
    }

    /** Draws quads in one batch, each turned to face the screen so the GUI's back-face culling keeps it. */
    private inline fun quads(context: GuiGraphics, draw: (VertexConsumer, Matrix4f) -> Unit) {
        val buffer = FrontFacingQuads(context.bufferSource().getBuffer(RenderType.gui()))
        draw(buffer, context.pose().last().pose())
        buffer.finish()
        context.flush()
    }

    private fun quad(buffer: VertexConsumer, matrix: Matrix4f, x1: Float, y1: Float, x2: Float, y2: Float, color: Int) =
        gradient(buffer, matrix, x1, y1, x2, y2, color, color, color, color)

    /** A quad with a colour per corner: top left, top right, bottom right, bottom left. */
    private fun gradient(buffer: VertexConsumer, matrix: Matrix4f, x1: Float, y1: Float, x2: Float, y2: Float,
                         topLeft: Int, topRight: Int, bottomRight: Int, bottomLeft: Int) {
        buffer.addVertex(matrix, x1, y1, 0f).setColor(topLeft)
        buffer.addVertex(matrix, x1, y2, 0f).setColor(bottomLeft)
        buffer.addVertex(matrix, x2, y2, 0f).setColor(bottomRight)
        buffer.addVertex(matrix, x2, y1, 0f).setColor(topRight)
    }

    private const val CELL = 20
    private const val FOCUS_LINES = 56
    private const val SLASH_X = -.55f
    private const val SLASH_Y = 1f
    private const val SHARD_COLUMNS = 11
    private const val SHARD_ROWS = 7
    private const val CRACKS = 11
    private const val CRACK_SEGMENTS = 5
    /** The share of the shatter by which the farthest shard starts after the nearest. */
    private const val SHARD_SPREAD = .5f
    private const val BAND = 26f
    private const val SLOPE = .55f
    private const val SHAKE_PIXELS = 5f
    private const val BAR_SHARE = .11f
    private const val RING_SEGMENTS = 48
    private const val TAU = (Math.PI * 2).toFloat()
}

/** An empty screen held while the transition covers the world, so the player stands still until the battle. */
internal class BattleEntryScreen : Screen(Component.empty()) {
    override fun isPauseScreen() = false
    override fun shouldCloseOnEsc() = false
    override fun renderBackground(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) = Unit
}
