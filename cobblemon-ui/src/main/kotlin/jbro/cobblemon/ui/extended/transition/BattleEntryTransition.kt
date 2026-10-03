package jbro.cobblemon.ui.extended.transition

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.labels.CobblemonPokemonLabels
import jbro.cobblemon.ui.extended.PanelConfig
import jbro.cobblemon.ui.extended.UIUtils
import jbro.cobblemon.ui.extended.ui.shared.BattleCornerCuts
import jbro.cobblemon.ui.extended.ui.shared.BattleEntryPattern
import jbro.cobblemon.ui.extended.ui.shared.BattleSurface
import jbro.cobblemon.ui.extended.ui.shared.BattleSurfaceRenderer
import jbro.cobblemon.ui.extended.ui.shared.BattleUiTheme
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.RenderType
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * The screen transition into a battle. [play] flashes and covers the screen, then holds it covered; the battle's
 * start (or [reveal], or a timeout) clears it to show the battle. Whoever starts a battle calls [play] first and
 * starts the battle once the screen is covered, so the battle opens on a clean reveal instead of under the pattern.
 *
 * It draws above every screen and the HUD, in the battle theme's pattern: rounded cells for Theme 1, slanted bands
 * for Theme 2.
 */
object BattleEntryTransition {
    private class Run(val kind: BattleEntryKind, val accent: Int, val startedAt: Long) {
        var revealAt: Long? = null
    }

    @Volatile
    private var run: Run? = null

    /** Whether a transition is on screen. */
    val active: Boolean get() = run != null

    /** Whether the screen is fully covered and waiting for the battle. */
    val covered: Boolean
        get() = run?.let { it.revealAt == null && BattleEntryTimeline.covered(it.kind, Util.getMillis() - it.startedAt) }
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
        play(kind, if (legendary) UIUtils.getTypeColor(found!!.primaryType) else null, blockInput)
    }

    /**
     * Plays [kind] in [accent], or the theme's colour for it. While [blockInput] holds, an empty screen keeps the
     * player from walking or acting until the battle opens.
     */
    @JvmStatic
    @JvmOverloads
    fun play(kind: BattleEntryKind, accent: Int? = null, blockInput: Boolean = true) {
        if (!PanelConfig.enableBattleEntryTransition) return
        val palette = BattleUiTheme.palette
        val color = accent ?: if (kind == BattleEntryKind.TRAINER) palette.opponent else palette.ally
        run = Run(kind, color, Util.getMillis())
        val client = Minecraft.getInstance()
        if (blockInput && client.screen == null) client.setScreen(BattleEntryScreen())
    }

    /** Clears the screen to show what is behind it; nothing if no transition is holding. */
    @JvmStatic
    fun reveal() {
        val current = run ?: return
        if (current.revealAt != null) return
        // A reveal asked for mid-cover waits for the cover to finish, so the pattern never jumps.
        val elapsed = Util.getMillis() - current.startedAt
        val coverEnd = BattleEntryTimeline.flashEnd(current.kind) + current.kind.coverMillis
        current.revealAt = current.startedAt + maxOf(elapsed, coverEnd)
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
    }

    private fun closeInputScreen() {
        val client = Minecraft.getInstance()
        if (client.screen is BattleEntryScreen) client.setScreen(null)
    }

    private fun render(context: GuiGraphics) {
        val current = run ?: return
        val now = Util.getMillis()
        val elapsed = now - current.startedAt
        val coverEnd = BattleEntryTimeline.flashEnd(current.kind) + current.kind.coverMillis
        if (current.revealAt == null && elapsed > coverEnd + BattleEntryTimeline.HOLD_TIMEOUT_MILLIS) reveal()
        val reveal = current.revealAt?.let { BattleEntryTimeline.reveal(current.kind, now - it) } ?: 0f
        if (reveal >= 1f) {
            run = null
            return
        }
        val cover = BattleEntryTimeline.cover(current.kind, elapsed)
        val width = context.guiWidth()
        val height = context.guiHeight()
        context.pose().pushPose()
        context.pose().translate(0f, 0f, 500f)
        when (BattleUiTheme.palette.entryPattern) {
            BattleEntryPattern.CELLS -> drawCells(context, width, height, current.accent, cover, reveal)
            BattleEntryPattern.STRIPES -> drawStripes(context, width, height, current.accent, cover, reveal)
        }
        val flash = BattleEntryTimeline.flash(current.kind, elapsed)
        if (flash > 0f) context.fill(0, 0, width, height, BattleSurfaceRenderer.withOpacity(0xFFFFFFFF.toInt(), flash * .85f))
        context.pose().popPose()
    }

    /** Rounded cells in a checker of two tones that bloom from the screen's center and close up seamlessly. */
    private fun drawCells(context: GuiGraphics, width: Int, height: Int, accent: Int, cover: Float, reveal: Float) {
        val base = BattleUiTheme.palette.entryBase
        val tones = intArrayOf(BattleSurfaceRenderer.interpolate(accent, base, .58f),
            BattleSurfaceRenderer.interpolate(accent, base, .70f))
        val columns = ceil(width / CELL.toFloat()).toInt() + 1
        val rows = ceil(height / CELL.toFloat()).toInt() + 1
        val left = (width - columns * CELL) / 2f
        val top = (height - rows * CELL) / 2f
        val far = hypot(width / 2f, height / 2f)
        for (row in 0 until rows) for (column in 0 until columns) {
            val centerX = left + column * CELL + CELL / 2f
            val centerY = top + row * CELL + CELL / 2f
            val distance = hypot(centerX - width / 2f, centerY - height / 2f) / far
            val share = BattleEntryTimeline.pieceSize(cover, reveal, distance)
            if (share <= 0f) continue
            // A little larger than the cell when full, so neighbours overlap and no seam shows.
            val size = ((CELL + 2) * share).toInt()
            if (size <= 0) continue
            val radius = (size / 2f * (1f - share)).toInt()
            BattleSurfaceRenderer.draw(context, (centerX - size / 2f).toInt(), (centerY - size / 2f).toInt(), size, size,
                BattleSurface(tones[(row + column) % 2], cornerCuts = BattleCornerCuts(radius, radius, radius, radius)))
        }
    }

    /** Slanted bands, dark and coloured in turn, that widen from their middles outward from the screen's center. */
    private fun drawStripes(context: GuiGraphics, width: Int, height: Int, accent: Int, cover: Float, reveal: Float) {
        val base = BattleUiTheme.palette.entryBase
        val colors = intArrayOf(base, BattleSurfaceRenderer.interpolate(accent, base, .2f))
        val shift = height * SLOPE / 2f
        val reach = ceil((width / 2f + shift) / BAND).toInt() + 1
        val buffer = context.bufferSource().getBuffer(RenderType.gui())
        val matrix = context.pose().last().pose()
        for (band in -reach..reach) {
            val distance = kotlin.math.abs(band) / reach.toFloat()
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
        context.flush()
    }

    private const val CELL = 20
    private const val BAND = 26f
    private const val SLOPE = .55f
}

/** An empty screen held while the transition covers the world, so the player stands still until the battle. */
internal class BattleEntryScreen : Screen(Component.empty()) {
    override fun isPauseScreen() = false
    override fun shouldCloseOnEsc() = false
    override fun renderBackground(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) = Unit
}
