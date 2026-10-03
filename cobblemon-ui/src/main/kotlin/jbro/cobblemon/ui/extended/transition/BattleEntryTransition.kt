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

    /** Whether the screen is covered and has pulsed long enough for the battle to start. */
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
     * Plays [kind] against [species], whose primary type colours a legendary transition, and runs [onCovered] once the
     * screen is covered. False when the transition is turned off, and [onCovered] will not run.
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
        // A reveal asked for early waits for the cover and its pulse to finish, so the transition always plays out.
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
        val sinceReveal = current.revealAt?.let { now - it }
        if (sinceReveal != null && BattleEntryTimeline.revealed(current.kind, sinceReveal)) {
            run = null
            return
        }
        val kind = current.kind
        val width = context.guiWidth()
        val height = context.guiHeight()
        val cover = BattleEntryTimeline.cover(kind, elapsed)
        val reveal = sinceReveal?.let { BattleEntryTimeline.reveal(kind, it) } ?: 0f
        val pose = context.pose()
        pose.pushPose()
        pose.translate(0f, 0f, 500f)

        // Behind the pattern: a legendary's darkened world, tinted at the edges in its colour.
        val dim = BattleEntryTimeline.dim(kind, elapsed) * (1f - reveal)
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
        val pulse = if (sinceReveal == null) BattleEntryTimeline.pulse(kind, elapsed) else 0f
        when (BattleUiTheme.palette.entryPattern) {
            BattleEntryPattern.CELLS -> drawCells(context, width, height, kind, current.accent, cover, reveal, pulse)
            BattleEntryPattern.STRIPES -> drawStripes(context, width, height, current.accent, cover, reveal, pulse)
        }
        pose.popPose()

        val bars = BattleEntryTimeline.bars(kind, elapsed, sinceReveal)
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
        val white = sinceReveal?.let { BattleEntryTimeline.white(kind, it) } ?: 0f
        if (white > 0f) context.fill(0, 0, width, height, BattleSurfaceRenderer.withOpacity(0xFFFFFFFF.toInt(), white))
        pose.popPose()
    }

    /**
     * Square cells in a checker of two tones, one quad each: a wild battle's grow from the center, a trainer's sweep in
     * from the top left, a legendary's are larger.
     */
    private fun drawCells(context: GuiGraphics, width: Int, height: Int, kind: BattleEntryKind, accent: Int,
                          cover: Float, reveal: Float, pulse: Float) {
        val base = BattleUiTheme.palette.entryBase
        // The covered screen breathes: both tones lean toward the accent with the pulse.
        val lift = pulse * if (kind == BattleEntryKind.LEGENDARY) .32f else .18f
        val tones = intArrayOf(BattleSurfaceRenderer.interpolate(accent, base, .58f - lift),
            BattleSurfaceRenderer.interpolate(accent, base, .70f - lift))
        val cell = if (kind == BattleEntryKind.LEGENDARY) LEGENDARY_CELL else CELL
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

    private inline fun quads(context: GuiGraphics, draw: (VertexConsumer, Matrix4f) -> Unit) {
        val buffer = context.bufferSource().getBuffer(RenderType.gui())
        draw(buffer, context.pose().last().pose())
        RenderSystem.disableCull()
        context.flush()
        RenderSystem.enableCull()
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
    private const val LEGENDARY_CELL = 28
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
