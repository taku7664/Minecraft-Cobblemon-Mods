package jbro.cobblemon.ui.extended.transition

import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import jbro.cobblemon.ui.extended.ui.shared.BattleEntryPattern
import jbro.cobblemon.ui.extended.ui.shared.BattleSurfaceRenderer
import jbro.cobblemon.ui.extended.ui.shared.BattleUiTheme
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.RenderType
import org.joml.Matrix4f
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The stages one entry transition is made of, each swappable for another of its kind: an [intro] before the cover, a
 * [mood] around the whole cover, the [cover] itself, the [whiteout] that turns the covered screen fully white, and the
 * [fadeIn] that takes the white screen away to show the battle. Every whiteout ends on a fully white screen and every
 * fade-in starts from one, so any whiteout follows on into any fade-in.
 */
data class EntryStages(
    val intro: EntryIntro,
    val mood: EntryMood,
    val cover: EntryCover,
    val whiteout: EntryWhiteout,
    val fadeIn: EntryFadeIn,
)

/** One frame of a transition, as every stage draws it. Times are in milliseconds since the transition started. */
class EntryFrame internal constructor(
    val context: GuiGraphics,
    val width: Int,
    val height: Int,
    val kind: BattleEntryKind,
    val accent: Int,
    val elapsed: Long,
    /** When the battle opened, or null while it has not. */
    val revealAt: Long?,
) {
    val centerX = width / 2f
    val centerY = height / 2f
    /** The distance from the screen's center to a corner. */
    val far = hypot(centerX, centerY)
    val cover = BattleEntryTimeline.cover(kind, elapsed)
    /** Whether the cover is still up: until the screen is fully white. */
    val covering = !BattleEntryTimeline.patternGone(kind, elapsed)
}

/** What plays before the cover. */
enum class EntryIntro {
    NONE,

    /** White flashes, one at each of the kind's beats. */
    FLASHES {
        override fun draw(frame: EntryFrame, screen: Int?) {
            val flash = BattleEntryTimeline.flash(frame.kind, frame.elapsed)
            if (flash > 0f) frame.context.fill(0, 0, frame.width, frame.height,
                BattleSurfaceRenderer.withOpacity(0xFFFFFFFF.toInt(), flash * .85f))
        }
    },

    /**
     * At each of the kind's beats the screen rushes outward in copies of itself, each a little larger than the last,
     * laid over it additively in a light wash of the accent so it brightens; they fade as the zoom ends.
     */
    SCREEN_ZOOM {
        override fun prepare(frame: EntryFrame): Int? =
            if (BattleEntryTimeline.zooms(frame.kind, frame.elapsed).isEmpty()) null else EntryDraw.captureScreen(frame.context)

        override fun draw(frame: EntryFrame, screen: Int?) {
            if (screen == null) return
            BattleEntryTimeline.zooms(frame.kind, frame.elapsed).forEach { EntryDraw.zoom(frame, screen, it) }
        }
    };

    /** Anything the intro needs from the screen before the transition draws over it this frame. */
    internal open fun prepare(frame: EntryFrame): Int? = null

    internal open fun draw(frame: EntryFrame, screen: Int?) = Unit
}

/** What surrounds the cover, from the start until the screen is white. */
enum class EntryMood {
    CALM,

    /** The world darkens with the accent at its edges, black bars slam in on the last beat, and the cover shakes. */
    OMINOUS {
        override fun drawBehind(frame: EntryFrame) {
            val dim = BattleEntryTimeline.dim(frame.kind, frame.elapsed)
            if (dim <= 0f) return
            frame.context.fill(0, 0, frame.width, frame.height, BattleSurfaceRenderer.withOpacity(0xFF000000.toInt(), dim * .55f))
            EntryDraw.vignette(frame, dim)
        }

        override fun drawFront(frame: EntryFrame) {
            val bars = BattleEntryTimeline.bars(frame.kind, frame.elapsed)
            if (bars <= 0f) return
            val bar = (frame.height * BAR_SHARE * bars).toInt()
            frame.context.fill(0, 0, frame.width, bar, 0xFF000000.toInt())
            frame.context.fill(0, frame.height - bar, frame.width, frame.height, 0xFF000000.toInt())
        }

        override fun shake(frame: EntryFrame): Pair<Float, Float> {
            val shake = BattleEntryTimeline.shake(frame.kind, frame.elapsed) * (1f - frame.cover * .6f)
            if (shake <= 0f) return 0f to 0f
            val amplitude = SHAKE_PIXELS * shake
            return sin(frame.elapsed * .09f) * amplitude to cos(frame.elapsed * .123f) * amplitude * .7f
        }
    };

    internal open fun drawBehind(frame: EntryFrame) = Unit
    internal open fun drawFront(frame: EntryFrame) = Unit
    /** How far the intro and cover are pushed this frame. */
    internal open fun shake(frame: EntryFrame): Pair<Float, Float> = 0f to 0f
}

/** How the screen is covered, drawn until it is fully white. */
enum class EntryCover {
    /** The theme's pattern (cells or bands) growing from the center. */
    THEME_BLOOM {
        override fun draw(frame: EntryFrame) = EntryDraw.themePattern(frame, sweep = false)
    },

    /** The theme's pattern sweeping in from the top left. */
    THEME_SWEEP {
        override fun draw(frame: EntryFrame) = EntryDraw.themePattern(frame, sweep = true)
    },

    /**
     * Two near-black plates tinted in the accent slide in from either side of a diagonal and close on it, under focus
     * lines in the accent that stab in from the edges toward the center, flickering.
     */
    PLATES {
        override fun draw(frame: EntryFrame) {
            EntryDraw.plates(frame)
            EntryDraw.focusLines(frame, flood = 0f)
        }
    };

    internal abstract fun draw(frame: EntryFrame)
}

/** How the covered screen turns fully white, between the kind's rise start and its ready time. */
enum class EntryWhiteout {
    /** Plain white rising over the cover. */
    WHITE {
        override fun draw(frame: EntryFrame) {
            val rise = BattleEntryTimeline.rise(frame.kind, frame.elapsed)
            if (rise > 0f) frame.context.fill(0, 0, frame.width, frame.height,
                BattleSurfaceRenderer.withOpacity(0xFFFFFFFF.toInt(), rise))
        }
    },

    /** Focus lines thicken, whiten and drive in from the edges to the center until their light fills the screen. */
    FOCUS_FLOOD {
        override fun draw(frame: EntryFrame) {
            val flood = BattleEntryTimeline.flood(frame.kind, frame.elapsed)
            if (flood <= 0f) return
            EntryDraw.focusLines(frame, flood)
            // The last of the flood closes the gaps between the lines.
            val fill = BattleEntryTimeline.smooth(((flood - .7f) / .3f).coerceIn(0f, 1f))
            if (fill > 0f) frame.context.fill(0, 0, frame.width, frame.height,
                BattleSurfaceRenderer.withOpacity(0xFFFFFFFF.toInt(), fill))
        }
    };

    internal abstract fun draw(frame: EntryFrame)
}

/** How the fully white screen gives way to the battle, from the kind's ready time on. */
enum class EntryFadeIn {
    /** The white waits for the battle, settles a moment and fades. */
    WHITE_FADE {
        override fun draw(frame: EntryFrame) {
            val white = BattleEntryTimeline.white(frame.kind, frame.elapsed, frame.revealAt)
            if (white > 0f) frame.context.fill(0, 0, frame.width, frame.height,
                BattleSurfaceRenderer.withOpacity(0xFFFFFFFF.toInt(), white))
        }
    },

    /**
     * The white holds, cracks from just above the center, stays still, then comes apart in shards that drift out from
     * the center at an even pace and fade, nearest first.
     */
    SHATTER {
        override fun draw(frame: EntryFrame) = EntryDraw.shatter(frame)
    };

    internal abstract fun draw(frame: EntryFrame)
}

private const val BAR_SHARE = .11f
private const val SHAKE_PIXELS = 5f

/** The drawing the stages share: batched quads, the screen copy, and each stage's shapes. */
internal object EntryDraw {
    private const val CELL = 20
    private const val BAND = 26f
    private const val SLOPE = .55f
    private const val FOCUS_LINES = 56
    /** How wide each focus line grows at the screen's edge, as a share of the center-to-corner distance. */
    private const val FLOOD_WIDTH = .11f
    /** The diagonal the plates meet on, from the top right to the bottom left. */
    private const val SEAM_X = -.55f
    private const val SEAM_Y = 1f
    private const val SHARD_COLUMNS = 11
    private const val SHARD_ROWS = 7
    private const val CRACKS = 11
    private const val CRACK_SEGMENTS = 5
    /** The share of the shatter by which the farthest shard starts after the nearest. */
    private const val SHARD_SPREAD = .5f
    private const val ZOOM_COPIES = 5
    /** How much larger each zoom copy grows than the one before it. */
    private const val ZOOM_STEP = .055f
    private const val TAU = (Math.PI * 2).toFloat()

    /** The theme's pattern: square cells in two tones for Theme 1, slanted bands for Theme 2. */
    fun themePattern(frame: EntryFrame, sweep: Boolean) {
        val pulse = BattleEntryTimeline.pulse(frame.kind, frame.elapsed)
        when (BattleUiTheme.palette.entryPattern) {
            BattleEntryPattern.CELLS -> cells(frame, sweep, pulse)
            BattleEntryPattern.STRIPES -> stripes(frame, pulse)
        }
    }

    /** Square cells in a checker of two tones, one quad each, from the center or [sweep]ing from the top left. */
    private fun cells(frame: EntryFrame, sweep: Boolean, pulse: Float) {
        val base = BattleUiTheme.palette.entryBase
        // The covered screen breathes: both tones lean toward the accent with the pulse.
        val lift = pulse * .18f
        val tones = intArrayOf(BattleSurfaceRenderer.interpolate(frame.accent, base, .58f - lift),
            BattleSurfaceRenderer.interpolate(frame.accent, base, .70f - lift))
        val columns = ceil(frame.width / CELL.toFloat()).toInt() + 1
        val rows = ceil(frame.height / CELL.toFloat()).toInt() + 1
        val left = (frame.width - columns * CELL) / 2f
        val top = (frame.height - rows * CELL) / 2f
        quads(frame.context) { buffer, matrix ->
            for (row in 0 until rows) for (column in 0 until columns) {
                val centerX = left + column * CELL + CELL / 2f
                val centerY = top + row * CELL + CELL / 2f
                val distance = if (sweep) (centerX + centerY) / (frame.width + frame.height)
                else hypot(centerX - frame.centerX, centerY - frame.centerY) / frame.far
                val share = BattleEntryTimeline.pieceSize(frame.cover, 0f, distance)
                if (share <= 0f) continue
                // A little larger than the cell when full, so neighbours overlap and no seam shows.
                val half = (CELL + 1) * share / 2f
                quad(buffer, matrix, centerX - half, centerY - half, centerX + half, centerY + half, tones[(row + column) % 2])
            }
        }
    }

    /** Slanted bands, dark and coloured in turn, that widen from their middles outward from the screen's center. */
    private fun stripes(frame: EntryFrame, pulse: Float) {
        val base = BattleUiTheme.palette.entryBase
        // The covered screen breathes: the dark bands warm and the coloured ones brighten with the pulse.
        val colors = intArrayOf(BattleSurfaceRenderer.interpolate(base, frame.accent, pulse * .18f),
            BattleSurfaceRenderer.interpolate(frame.accent, base, .2f - pulse * .15f))
        val shift = frame.height * SLOPE / 2f
        val reach = ceil((frame.width / 2f + shift) / BAND).toInt() + 1
        quads(frame.context) { buffer, matrix ->
            for (band in -reach..reach) {
                val share = BattleEntryTimeline.pieceSize(frame.cover, 0f, abs(band) / reach.toFloat())
                if (share <= 0f) continue
                val half = (BAND / 2f + .6f) * share
                val middle = frame.centerX + band * BAND
                val color = colors[Math.floorMod(band, 2)]
                // The top edge leans right of the bottom one.
                buffer.addVertex(matrix, middle + shift - half, 0f, 0f).setColor(color)
                buffer.addVertex(matrix, middle - shift - half, frame.height.toFloat(), 0f).setColor(color)
                buffer.addVertex(matrix, middle - shift + half, frame.height.toFloat(), 0f).setColor(color)
                buffer.addVertex(matrix, middle + shift + half, 0f, 0f).setColor(color)
            }
        }
    }

    /** Two near-black plates tinted in the accent closing on the diagonal seam. */
    fun plates(frame: EntryFrame) {
        if (frame.cover <= 0f) return
        val length = hypot(SEAM_X, SEAM_Y)
        val ux = SEAM_X / length
        val uy = SEAM_Y / length
        // The normal points to the top left plate's side.
        val nx = -uy
        val ny = ux
        val reach = frame.far * 1.6f
        val plate = BattleSurfaceRenderer.interpolate(frame.accent, 0xFF000000.toInt(), .88f)
        val gap = (1f - BattleEntryTimeline.plates(frame.cover)) * frame.far * 1.25f
        quads(frame.context) { buffer, matrix ->
            // Each plate: a band reaching from the seam out past the screen on its side, held [gap] away from it.
            for (side in intArrayOf(1, -1)) {
                val nearX = frame.centerX + nx * side * (gap - .5f)
                val nearY = frame.centerY + ny * side * (gap - .5f)
                val farX = frame.centerX + nx * side * (gap + reach)
                val farY = frame.centerY + ny * side * (gap + reach)
                buffer.addVertex(matrix, nearX - ux * reach, nearY - uy * reach, 0f).setColor(plate)
                buffer.addVertex(matrix, farX - ux * reach, farY - uy * reach, 0f).setColor(plate)
                buffer.addVertex(matrix, farX + ux * reach, farY + uy * reach, 0f).setColor(plate)
                buffer.addVertex(matrix, nearX + ux * reach, nearY + uy * reach, 0f).setColor(plate)
            }
        }
    }

    /**
     * Focus lines from the edges toward the center, each its own length, width and flicker, reaching in as the cover
     * closes; as they [flood] (0 to 1) they thicken, whiten and drive to the center. The same lines at a flood of 0 are
     * the cover's, so a flood grows out of them seamlessly.
     */
    fun focusLines(frame: EntryFrame, flood: Float) {
        val cover = maxOf(frame.cover, if (flood > 0f) 1f else 0f)
        if (cover <= 0f) return
        val lineColor = BattleSurfaceRenderer.interpolate(frame.accent, 0xFFFFFFFF.toInt(), .3f)
        val flicker = (frame.elapsed / 70L).toInt()
        val wide = frame.far * FLOOD_WIDTH
        quads(frame.context) { buffer, matrix ->
            for (line in 0 until FOCUS_LINES) {
                val seed = line * 7919 + 13
                val angle = (line + hash(seed) * .8f) / FOCUS_LINES * TAU
                val resting = (.38f + .32f * hash(seed + 1)) * (.35f + .65f * cover)
                val depth = resting + (1.05f - resting) * flood
                val inner = frame.far * (1.05f - depth)
                val outer = frame.far * 1.25f
                val thin = 2.5f + 5f * hash(seed + 2)
                val half = thin + (wide - thin) * flood
                val restingAlpha = cover * (.45f + .45f * hash(seed + flicker * 31))
                val alpha = restingAlpha + (1f - restingAlpha) * flood
                val color = BattleSurfaceRenderer.withOpacity(
                    BattleSurfaceRenderer.interpolate(lineColor, 0xFFFFFFFF.toInt(), flood), alpha.coerceIn(0f, 1f))
                val cx = cos(angle)
                val cy = sin(angle)
                val tipX = frame.centerX + cx * inner
                val tipY = frame.centerY + cy * inner
                val baseX = frame.centerX + cx * outer
                val baseY = frame.centerY + cy * outer
                // A wedge: a quad whose two inner corners meet at the tip.
                buffer.addVertex(matrix, tipX, tipY, 0f).setColor(color)
                buffer.addVertex(matrix, baseX - cy * half, baseY + cx * half, 0f).setColor(color)
                buffer.addVertex(matrix, baseX + cy * half, baseY - cx * half, 0f).setColor(color)
                buffer.addVertex(matrix, tipX, tipY, 0f).setColor(color)
            }
        }
    }

    /** The white holding, cracking, staying still, then coming apart in shards that drift out and fade. */
    fun shatter(frame: EntryFrame) {
        val impactX = frame.centerX
        val impactY = frame.height * .46f
        val shatter = BattleEntryTimeline.shatter(frame.kind, frame.elapsed, frame.revealAt)
        val crack = BattleEntryTimeline.crack(frame.kind, frame.elapsed, frame.revealAt)
        if (shatter <= 0f) {
            frame.context.fill(0, 0, frame.width, frame.height, 0xFFFFFFFF.toInt())
            cracks(frame, impactX, impactY, crack, 1f)
            return
        }
        // The white tiled in jittered triangles that together cover the screen exactly until they start to move.
        val cellW = frame.width.toFloat() / SHARD_COLUMNS
        val cellH = frame.height.toFloat() / SHARD_ROWS
        fun corner(column: Int, row: Int): Pair<Float, Float> {
            val inside = column in 1 until SHARD_COLUMNS && row in 1 until SHARD_ROWS
            val seed = column * 92821 + row * 68917
            val jitterX = if (inside) (hash(seed) - .5f) * cellW * .7f else 0f
            val jitterY = if (inside) (hash(seed + 5) - .5f) * cellH * .7f else 0f
            return column * cellW + jitterX to row * cellH + jitterY
        }
        quads(frame.context) { buffer, matrix ->
            for (row in 0 until SHARD_ROWS) for (column in 0 until SHARD_COLUMNS) {
                val a = corner(column, row)
                val b = corner(column + 1, row)
                val c = corner(column + 1, row + 1)
                val d = corner(column, row + 1)
                val seed = column * 7333 + row * 1291
                val shards = if (hash(seed) < .5f) listOf(listOf(a, b, c), listOf(a, c, d)) else listOf(listOf(a, b, d), listOf(b, c, d))
                shards.forEachIndexed { index, shard -> shard(buffer, matrix, frame, shard, seed * 3 + index, impactX, impactY, shatter) }
            }
        }
        // The cracks stay on the shards for a moment as they part.
        cracks(frame, impactX, impactY, crack, 1f - shatter * 4f)
    }

    /** One shard: from the center outward each in turn drifts a little away at an even pace, turning slightly, fading. */
    private fun shard(buffer: VertexConsumer, matrix: Matrix4f, frame: EntryFrame, points: List<Pair<Float, Float>>, seed: Int,
                      impactX: Float, impactY: Float, shatter: Float) {
        val cx = points.sumOf { it.first.toDouble() }.toFloat() / 3f
        val cy = points.sumOf { it.second.toDouble() }.toFloat() / 3f
        val dx = cx - impactX
        val dy = cy - impactY
        val distance = (hypot(dx, dy) / frame.far).coerceAtMost(1f)
        // Shards near the impact part first; the parting spreads outward across the screen.
        val t = ((shatter - distance * SHARD_SPREAD) / (1f - SHARD_SPREAD)).coerceIn(0f, 1f)
        val alpha = 1f - t
        if (alpha <= 0f) return
        val norm = hypot(dx, dy).coerceAtLeast(1f)
        val push = frame.far * (.04f + .05f * hash(seed + 1)) * t
        val moveX = dx / norm * push
        val moveY = dy / norm * push
        val spin = (hash(seed + 2) - .5f) * .5f * t
        val scale = 1f - .08f * t
        val spinCos = cos(spin)
        val spinSin = sin(spin)
        val tint = BattleSurfaceRenderer.interpolate(0xFFFFFFFF.toInt(), frame.accent, .06f + .14f * hash(seed + 3))
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
    private fun cracks(frame: EntryFrame, impactX: Float, impactY: Float, growth: Float, opacity: Float) {
        if (growth <= 0f || opacity <= 0f) return
        val color = BattleSurfaceRenderer.withOpacity(BattleSurfaceRenderer.interpolate(frame.accent, 0xFF000000.toInt(), .45f),
            .7f * opacity.coerceAtMost(1f))
        quads(frame.context) { buffer, matrix ->
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
                    val length = frame.far * (.12f + .14f * hash(seed + segment * 13 + 2)) * share
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

    /** The screen's edges in the accent, fading toward the middle. */
    fun vignette(frame: EntryFrame, strength: Float) {
        val edge = BattleSurfaceRenderer.withOpacity(frame.accent, strength * .55f)
        val clear = BattleSurfaceRenderer.withOpacity(frame.accent, 0f)
        val w = frame.width.toFloat()
        val h = frame.height.toFloat()
        val depthX = w * .22f
        val depthY = h * .26f
        quads(frame.context) { buffer, matrix ->
            gradient(buffer, matrix, 0f, 0f, w, depthY, edge, edge, clear, clear)
            gradient(buffer, matrix, 0f, h - depthY, w, h, clear, clear, edge, edge)
            gradient(buffer, matrix, 0f, 0f, depthX, h, edge, clear, clear, edge)
            gradient(buffer, matrix, w - depthX, 0f, w, h, clear, edge, edge, clear)
        }
    }

    /** A copy of the screen as the game has drawn it so far this frame, for the zooms; reused frame to frame. */
    private var screenCopy: TextureTarget? = null

    /** Copies the game's frame as it stands into [screenCopy] and returns its texture, or null if it cannot. */
    fun captureScreen(context: GuiGraphics): Int? {
        return try {
            context.flush()
            val main = Minecraft.getInstance().mainRenderTarget
            var copy = screenCopy
            if (copy == null || copy.width != main.width || copy.height != main.height) {
                copy?.destroyBuffers()
                copy = TextureTarget(main.width, main.height, false, Minecraft.ON_OSX)
                copy.setFilterMode(GL11.GL_LINEAR)
                screenCopy = copy
            }
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId)
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copy.frameBufferId)
            GlStateManager._glBlitFrameBuffer(0, 0, main.width, main.height, 0, 0, main.width, main.height,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST)
            main.bindWrite(false)
            copy.colorTextureId
        } catch (failure: RuntimeException) {
            jbro.cobblemon.ui.extended.CobblemonUi.LOGGER.warn("Battle entry could not copy the screen", failure)
            Minecraft.getInstance().mainRenderTarget.bindWrite(false)
            null
        }
    }

    /** One zoom of the screen copy [texture] at [progress] (0 to 1). */
    fun zoom(frame: EntryFrame, texture: Int, progress: Float) {
        val reach = 1f - (1f - progress) * (1f - progress)
        val fade = (1f - progress) * (1f - progress)
        val tint = BattleSurfaceRenderer.interpolate(0xFFFFFFFF.toInt(), frame.accent, .3f)
        val matrix = frame.context.pose().last().pose()
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader)
        RenderSystem.setShaderTexture(0, texture)
        RenderSystem.enableBlend()
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE)
        RenderSystem.disableDepthTest()
        RenderSystem.disableCull()
        val buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR)
        for (copy in 1..ZOOM_COPIES) {
            val scale = 1f + ZOOM_STEP * copy * (.3f + .7f * reach)
            val alpha = .24f * fade * (1f - .5f * (copy - 1) / ZOOM_COPIES)
            val color = BattleSurfaceRenderer.withOpacity(tint, alpha)
            val halfW = frame.width / 2f * scale
            val halfH = frame.height / 2f * scale
            // The copy's texture is upside down: framebuffers start at the bottom.
            buffer.addVertex(matrix, frame.centerX - halfW, frame.centerY - halfH, 0f).setUv(0f, 1f).setColor(color)
            buffer.addVertex(matrix, frame.centerX - halfW, frame.centerY + halfH, 0f).setUv(0f, 0f).setColor(color)
            buffer.addVertex(matrix, frame.centerX + halfW, frame.centerY + halfH, 0f).setUv(1f, 0f).setColor(color)
            buffer.addVertex(matrix, frame.centerX + halfW, frame.centerY - halfH, 0f).setUv(1f, 1f).setColor(color)
        }
        BufferUploader.drawWithShader(buffer.buildOrThrow())
        RenderSystem.enableCull()
        RenderSystem.enableDepthTest()
        RenderSystem.defaultBlendFunc()
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

    /** A steady pseudo-random share, 0 to 1, for [seed]. */
    private fun hash(seed: Int): Float {
        var x = seed * -0x61c88647
        x = x xor (x ushr 15)
        x *= 0x2c1b3c6d
        x = x xor (x ushr 12)
        return (x ushr 8) / 16777216f
    }
}
