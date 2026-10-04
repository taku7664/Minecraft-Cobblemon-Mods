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

    /** The screen breaks up into ever coarser blocks over the kind's beats, as Pokémon Emerald's blur. */
    MOSAIC {
        override fun prepare(frame: EntryFrame): Int? {
            val mosaic = BattleEntryTimeline.mosaic(frame.kind, frame.elapsed)
            return if (mosaic <= 0f || !frame.covering) null else EntryDraw.captureScreen(frame.context)
        }

        override fun draw(frame: EntryFrame, screen: Int?) {
            if (screen == null) return
            EntryDraw.mosaic(frame, 1f + 23f * BattleEntryTimeline.mosaic(frame.kind, frame.elapsed))
        }
    },

    /**
     * The screen whirls outward in turning, growing copies of itself, laid over it half-transparent rather than added,
     * so they show on a bright screen too.
     */
    SPIN_ZOOM {
        override fun prepare(frame: EntryFrame): Int? =
            if (BattleEntryTimeline.spin(frame.kind, frame.elapsed) == null) null else EntryDraw.captureScreen(frame.context)

        override fun draw(frame: EntryFrame, screen: Int?) {
            if (screen == null) return
            val progress = BattleEntryTimeline.spin(frame.kind, frame.elapsed) ?: return
            EntryDraw.zoom(frame, screen, progress, spin = 1f, additive = false)
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
    },

    /**
     * The screen warms and brightens while ever larger copies of it pile up from the center, smearing it outward until
     * it burns white, as the main series' legendary encounter. Needs no cover.
     */
    ZOOM_BLUR {
        override fun prepare(frame: EntryFrame): Int? =
            if (BattleEntryTimeline.zoomBlur(frame.kind, frame.elapsed) > 0f) EntryDraw.captureScreen(frame.context) else null

        override fun draw(frame: EntryFrame, screen: Int?) {
            EntryDraw.zoomBlur(frame, screen, BattleEntryTimeline.zoomBlur(frame.kind, frame.elapsed),
                BattleEntryTimeline.warmth(frame.kind, frame.elapsed))
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

    /** The theme's cells filling in a rectangular spiral from the edges to the center. */
    SPIRAL {
        override fun draw(frame: EntryFrame) = EntryDraw.spiral(frame)
    },

    /** Horizontal bands sliding in from the left and the right in turn, top to bottom. */
    SLICES {
        override fun draw(frame: EntryFrame) = EntryDraw.slices(frame)
    },

    /** A clock hand sweeping from twelve o'clock round the screen, leaving slices of two tones behind it. */
    CLOCK_WIPE {
        override fun draw(frame: EntryFrame) = EntryDraw.clockWipe(frame)
    },

    /** A circle closing on the center, an accent rim at its edge. */
    IRIS {
        override fun draw(frame: EntryFrame) = EntryDraw.iris(frame)
    },

    /** No cover: for intros that carry the screen to white on their own. */
    NONE {
        override fun draw(frame: EntryFrame) = Unit
    },

    /** Turning spiral arms that widen until they meet, as the main series' spinning spiral. */
    SPINNING_SPIRAL {
        override fun draw(frame: EntryFrame) = EntryDraw.spinningSpiral(frame)
    },

    /**
     * A Poké Ball's outline over the screen while darkness sweeps clockwise round it, the outline last of all, as the
     * main series' Poké Ball arena transition.
     */
    POKE_ARENA {
        override fun draw(frame: EntryFrame) = EntryDraw.pokeArena(frame)
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

    /** A white circle bursting out from the center past the corners, its edge soft. */
    WHITE_BURST {
        override fun draw(frame: EntryFrame) = EntryDraw.whiteBurst(frame)
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

    /** The white waits for the battle, then a hole opens in it from the center out past the corners. */
    IRIS_OPEN {
        override fun draw(frame: EntryFrame) = EntryDraw.irisOpen(frame)
    },

    /** The white waits for the battle, then parts at the middle, its halves sliding off the top and bottom. */
    SPLIT_OPEN {
        override fun draw(frame: EntryFrame) = EntryDraw.splitOpen(frame)
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
    private const val FINE_SEGMENTS = 3
    /** The share of the shatter by which the farthest shard starts after the nearest. */
    private const val SHARD_SPREAD = .5f
    private const val ZOOM_COPIES = 5
    /** How much larger each zoom copy grows than the one before it. */
    private const val ZOOM_STEP = .055f
    /** How many copies a zoom blur piles up, and how much larger than the screen the largest grows. */
    private const val BLUR_COPIES = 8
    private const val BLUR_REACH = 1.3f
    private const val TAU = (Math.PI * 2).toFloat()
    /** How many times the spinning spiral winds from the center to the corners. */
    private const val SPIRAL_TURNS = 2.2f

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

    private class SpiralOrder(val columns: Int, val rows: Int, val rank: IntArray)

    private var spiralOrder: SpiralOrder? = null

    /** Each cell's place in a clockwise rectangular spiral from the top left corner inward, as a share of all cells. */
    private fun spiralRanks(columns: Int, rows: Int): IntArray {
        spiralOrder?.takeIf { it.columns == columns && it.rows == rows }?.let { return it.rank }
        val rank = IntArray(columns * rows)
        var left = 0
        var top = 0
        var right = columns - 1
        var bottom = rows - 1
        var next = 0
        while (left <= right && top <= bottom) {
            for (x in left..right) rank[top * columns + x] = next++
            for (y in top + 1..bottom) rank[y * columns + right] = next++
            if (top < bottom) for (x in right - 1 downTo left) rank[bottom * columns + x] = next++
            if (left < right) for (y in bottom - 1 downTo top + 1) rank[y * columns + left] = next++
            left++; top++; right--; bottom--
        }
        spiralOrder = SpiralOrder(columns, rows, rank)
        return rank
    }

    /** The theme's two cell tones filling in along a rectangular spiral. */
    fun spiral(frame: EntryFrame) {
        val base = BattleUiTheme.palette.entryBase
        val pulse = BattleEntryTimeline.pulse(frame.kind, frame.elapsed) * .18f
        val tones = intArrayOf(BattleSurfaceRenderer.interpolate(frame.accent, base, .58f - pulse),
            BattleSurfaceRenderer.interpolate(frame.accent, base, .70f - pulse))
        val size = CELL * 2
        val columns = ceil(frame.width / size.toFloat()).toInt()
        val rows = ceil(frame.height / size.toFloat()).toInt()
        val rank = spiralRanks(columns, rows)
        val total = (columns * rows - 1).coerceAtLeast(1).toFloat()
        quads(frame.context) { buffer, matrix ->
            for (row in 0 until rows) for (column in 0 until columns) {
                val share = (frame.cover * (1f + .15f) - rank[row * columns + column] / total).coerceIn(0f, .15f) / .15f
                if (share <= 0f) continue
                val centerX = column * size + size / 2f
                val centerY = row * size + size / 2f
                val half = (size + 1) * BattleEntryTimeline.smooth(share) / 2f
                quad(buffer, matrix, centerX - half, centerY - half, centerX + half, centerY + half, tones[(row + column) % 2])
            }
        }
    }

    /** Horizontal bands, from alternate sides, each a little after the one above it. */
    fun slices(frame: EntryFrame) {
        val base = BattleUiTheme.palette.entryBase
        val tones = intArrayOf(BattleSurfaceRenderer.interpolate(frame.accent, base, .2f),
            BattleSurfaceRenderer.interpolate(frame.accent, base, .75f))
        val bands = 10
        val bandHeight = frame.height / bands.toFloat()
        val w = frame.width.toFloat()
        quads(frame.context) { buffer, matrix ->
            for (band in 0 until bands) {
                val share = BattleEntryTimeline.smooth(BattleEntryTimeline.piece(frame.cover, band / (bands - 1f)))
                if (share <= 0f) continue
                val top = band * bandHeight
                val bottom = top + bandHeight + 1f
                if (band % 2 == 0) quad(buffer, matrix, 0f, top, w * share, bottom, tones[band % 2])
                else quad(buffer, matrix, w * (1f - share), top, w, bottom, tones[band % 2])
            }
        }
    }

    /** A sector growing clockwise from twelve o'clock, in eight slices of two tones. */
    fun clockWipe(frame: EntryFrame) {
        if (frame.cover <= 0f) return
        val base = BattleUiTheme.palette.entryBase
        val tones = intArrayOf(BattleSurfaceRenderer.interpolate(frame.accent, base, .3f),
            BattleSurfaceRenderer.interpolate(frame.accent, base, .7f))
        val sweep = BattleEntryTimeline.smooth(frame.cover) * TAU
        val radius = frame.far * 1.1f
        val segments = 96
        quads(frame.context) { buffer, matrix ->
            for (segment in 0 until segments) {
                val from = segment * TAU / segments
                if (from >= sweep) break
                val to = minOf((segment + 1) * TAU / segments, sweep)
                val color = tones[(segment * 8 / segments) % 2]
                val a0 = from - TAU / 4f
                val a1 = to - TAU / 4f
                buffer.addVertex(matrix, frame.centerX, frame.centerY, 0f).setColor(color)
                buffer.addVertex(matrix, frame.centerX + cos(a0) * radius, frame.centerY + sin(a0) * radius, 0f).setColor(color)
                buffer.addVertex(matrix, frame.centerX + cos(a1) * radius, frame.centerY + sin(a1) * radius, 0f).setColor(color)
                buffer.addVertex(matrix, frame.centerX, frame.centerY, 0f).setColor(color)
            }
        }
    }

    /**
     * Spiral arms, turning slowly, that widen until they meet: a point is covered once its place along the spiral
     * falls below the cover. Drawn on a polar grid of small cells.
     */
    fun spinningSpiral(frame: EntryFrame) {
        if (frame.cover <= 0f) return
        val base = BattleUiTheme.palette.entryBase
        val tones = intArrayOf(BattleSurfaceRenderer.interpolate(frame.accent, base, .55f),
            BattleSurfaceRenderer.interpolate(frame.accent, base, .75f))
        val radius = frame.far * 1.12f
        val angular = 144
        val radial = 40
        val turn = frame.elapsed / 1000f * .35f
        val arms = 2
        quads(frame.context) { buffer, matrix ->
            for (ring in 0 until radial) {
                val r0 = radius * ring / radial
                val r1 = radius * (ring + 1) / radial
                val along = (ring + .5f) / radial * SPIRAL_TURNS
                for (step in 0 until angular) {
                    val share = (step + .5f) / angular
                    val place = ((share * arms + along - turn) % 1f + 1f) % 1f
                    if (place >= frame.cover) continue
                    val a0 = step * TAU / angular - TAU / 4f
                    val a1 = (step + 1) * TAU / angular - TAU / 4f
                    val color = tones[((share * arms + along - turn).toInt() and 1)]
                    buffer.addVertex(matrix, frame.centerX + cos(a0) * r1, frame.centerY + sin(a0) * r1, 0f).setColor(color)
                    buffer.addVertex(matrix, frame.centerX + cos(a0) * r0, frame.centerY + sin(a0) * r0, 0f).setColor(color)
                    buffer.addVertex(matrix, frame.centerX + cos(a1) * r0, frame.centerY + sin(a1) * r0, 0f).setColor(color)
                    buffer.addVertex(matrix, frame.centerX + cos(a1) * r1, frame.centerY + sin(a1) * r1, 0f).setColor(color)
                }
            }
        }
    }

    /**
     * Darkness sweeping clockwise from twelve o'clock round a Poké Ball's outline (its rim, band and button), and a
     * second sweep after it taking the outline too.
     */
    fun pokeArena(frame: EntryFrame) {
        if (frame.cover <= 0f) return
        val dark = BattleSurfaceRenderer.interpolate(frame.accent, BattleUiTheme.palette.entryBase, .85f)
        val line = BattleSurfaceRenderer.interpolate(frame.accent, 0xFFFFFFFF.toInt(), .55f)
        val sweep = (frame.cover * 1.25f).coerceAtMost(1f) * TAU
        val taken = ((frame.cover - .2f) / .8f).coerceIn(0f, 1f) * TAU
        val appear = (frame.cover / .08f).coerceAtMost(1f)
        val stroke = BattleSurfaceRenderer.withOpacity(line, appear)
        val radius = minOf(frame.width, frame.height) * .42f
        val reach = frame.far * 1.1f
        // The clockwise angle from twelve o'clock of a point relative to the center, 0 to a full turn.
        fun clock(x: Float, y: Float): Float {
            val angle = kotlin.math.atan2(y - frame.centerY, x - frame.centerX) + TAU / 4f
            return ((angle % TAU) + TAU) % TAU
        }
        quads(frame.context) { buffer, matrix ->
            val segments = 120
            for (segment in 0 until segments) {
                val from = segment * TAU / segments
                if (from >= sweep) break
                val to = minOf((segment + 1) * TAU / segments, sweep)
                val a0 = from - TAU / 4f
                val a1 = to - TAU / 4f
                buffer.addVertex(matrix, frame.centerX, frame.centerY, 0f).setColor(dark)
                buffer.addVertex(matrix, frame.centerX + cos(a0) * reach, frame.centerY + sin(a0) * reach, 0f).setColor(dark)
                buffer.addVertex(matrix, frame.centerX + cos(a1) * reach, frame.centerY + sin(a1) * reach, 0f).setColor(dark)
                buffer.addVertex(matrix, frame.centerX, frame.centerY, 0f).setColor(dark)
            }
            // The outline, in pieces, each kept until the second sweep passes it.
            fun arc(r: Float, width: Float) {
                for (segment in 0 until segments) {
                    val a0 = segment * TAU / segments
                    val a1 = (segment + 1) * TAU / segments
                    if ((a0 + a1) / 2f < taken) continue
                    val s0 = a0 - TAU / 4f
                    val s1 = a1 - TAU / 4f
                    val outer = r + width / 2f
                    val inner = r - width / 2f
                    buffer.addVertex(matrix, frame.centerX + cos(s0) * outer, frame.centerY + sin(s0) * outer, 0f).setColor(stroke)
                    buffer.addVertex(matrix, frame.centerX + cos(s0) * inner, frame.centerY + sin(s0) * inner, 0f).setColor(stroke)
                    buffer.addVertex(matrix, frame.centerX + cos(s1) * inner, frame.centerY + sin(s1) * inner, 0f).setColor(stroke)
                    buffer.addVertex(matrix, frame.centerX + cos(s1) * outer, frame.centerY + sin(s1) * outer, 0f).setColor(stroke)
                }
            }
            arc(radius, 6f)
            arc(radius * .24f, 5f)
            arc(radius * .1f, radius * .2f)
            // The band across the middle, broken by the button.
            val pieces = 40
            for (piece in 0 until pieces) {
                val x0 = frame.centerX - radius + 2f * radius * piece / pieces
                val x1 = frame.centerX - radius + 2f * radius * (piece + 1) / pieces
                val middle = (x0 + x1) / 2f
                if (abs(middle - frame.centerX) < radius * .26f) continue
                if (clock(middle, frame.centerY) < taken) continue
                quad(buffer, matrix, x0, frame.centerY - 3f, x1, frame.centerY + 3f, stroke)
            }
        }
    }

    /** A dark ring closing on the center with a bright accent rim. */
    fun iris(frame: EntryFrame) {
        if (frame.cover <= 0f) return
        val base = BattleUiTheme.palette.entryBase
        val dark = BattleSurfaceRenderer.interpolate(frame.accent, base, .8f)
        val rim = BattleSurfaceRenderer.interpolate(frame.accent, 0xFFFFFFFF.toInt(), .35f)
        val closing = 1f - (1f - frame.cover) * (1f - frame.cover)
        val inner = frame.far * 1.05f * (1f - closing)
        quads(frame.context) { buffer, matrix ->
            ring(buffer, matrix, frame, inner, frame.far * 1.3f, dark, dark)
            if (inner > 0f) ring(buffer, matrix, frame, (inner - 3f).coerceAtLeast(0f), inner + 1f, rim, rim)
        }
    }

    /** A white disc growing from the center, its edge fading over a few pixels. */
    fun whiteBurst(frame: EntryFrame) {
        val rise = BattleEntryTimeline.rise(frame.kind, frame.elapsed)
        if (rise <= 0f) return
        val radius = frame.far * 1.1f * rise * rise
        val white = 0xFFFFFFFF.toInt()
        val clear = 0x00FFFFFF
        quads(frame.context) { buffer, matrix ->
            ring(buffer, matrix, frame, 0f, radius, white, white)
            ring(buffer, matrix, frame, radius, radius + 14f, white, clear)
        }
    }

    /** The white with a hole opening in it from the center, its inner edge soft. */
    fun irisOpen(frame: EntryFrame) {
        val opening = BattleEntryTimeline.opening(frame.kind, frame.elapsed, frame.revealAt)
        val hole = frame.far * 1.15f * opening
        val white = 0xFFFFFFFF.toInt()
        val clear = 0x00FFFFFF
        quads(frame.context) { buffer, matrix ->
            if (hole > 0f) ring(buffer, matrix, frame, (hole - 14f).coerceAtLeast(0f), hole, clear, white)
            ring(buffer, matrix, frame, hole, frame.far * 1.4f, white, white)
        }
    }

    /** The white in two halves sliding apart off the top and bottom, a glow along their edges. */
    fun splitOpen(frame: EntryFrame) {
        val opening = BattleEntryTimeline.opening(frame.kind, frame.elapsed, frame.revealAt)
        val half = frame.height / 2f
        val offset = half * opening
        val w = frame.width.toFloat()
        val white = 0xFFFFFFFF.toInt()
        val glow = BattleSurfaceRenderer.withOpacity(BattleSurfaceRenderer.interpolate(frame.accent, white, .5f), 1f - opening)
        quads(frame.context) { buffer, matrix ->
            quad(buffer, matrix, 0f, -offset, w, half - offset + .5f, white)
            quad(buffer, matrix, 0f, half + offset - .5f, w, frame.height + offset, white)
            if (opening > 0f) {
                gradient(buffer, matrix, 0f, half - offset, w, half - offset + 6f, glow, glow, 0, 0)
                gradient(buffer, matrix, 0f, half + offset - 6f, w, half + offset, 0, 0, glow, glow)
            }
        }
    }

    /** A ring around the screen's center from [inner] to [outer], each edge in its own colour. */
    private fun ring(buffer: VertexConsumer, matrix: Matrix4f, frame: EntryFrame, inner: Float, outer: Float,
                     innerColor: Int, outerColor: Int) {
        val segments = 72
        for (segment in 0 until segments) {
            val from = segment * TAU / segments
            val to = (segment + 1) * TAU / segments
            buffer.addVertex(matrix, frame.centerX + cos(from) * outer, frame.centerY + sin(from) * outer, 0f).setColor(outerColor)
            buffer.addVertex(matrix, frame.centerX + cos(from) * inner, frame.centerY + sin(from) * inner, 0f).setColor(innerColor)
            buffer.addVertex(matrix, frame.centerX + cos(to) * inner, frame.centerY + sin(to) * inner, 0f).setColor(innerColor)
            buffer.addVertex(matrix, frame.centerX + cos(to) * outer, frame.centerY + sin(to) * outer, 0f).setColor(outerColor)
        }
    }

    /** A target for the mosaic: the screen shrunk into its corner and drawn back up blocky; reused frame to frame. */
    private var mosaicCopy: TextureTarget? = null

    /** The screen drawn back in blocks [block] GUI pixels wide. */
    fun mosaic(frame: EntryFrame, block: Float) {
        val main = Minecraft.getInstance().mainRenderTarget
        var copy = mosaicCopy
        if (copy == null || copy.width != main.width || copy.height != main.height) {
            copy?.destroyBuffers()
            copy = TextureTarget(main.width, main.height, false, Minecraft.ON_OSX)
            copy.setFilterMode(GL11.GL_NEAREST)
            mosaicCopy = copy
        }
        val pixels = block * main.width / frame.width.toFloat()
        val small = maxOf(1, (main.width / pixels).toInt())
        val smallHeight = maxOf(1, (main.height / pixels).toInt())
        frame.context.flush()
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId)
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copy.frameBufferId)
        GlStateManager._glBlitFrameBuffer(0, 0, main.width, main.height, 0, 0, small, smallHeight,
            GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR)
        main.bindWrite(false)
        val u = small / main.width.toFloat()
        val v = smallHeight / main.height.toFloat()
        val matrix = frame.context.pose().last().pose()
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader)
        RenderSystem.setShaderTexture(0, copy.colorTextureId)
        RenderSystem.disableBlend()
        RenderSystem.disableDepthTest()
        RenderSystem.disableCull()
        val buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR)
        val w = frame.width.toFloat()
        val h = frame.height.toFloat()
        buffer.addVertex(matrix, 0f, 0f, 0f).setUv(0f, v).setColor(-1)
        buffer.addVertex(matrix, 0f, h, 0f).setUv(0f, 0f).setColor(-1)
        buffer.addVertex(matrix, w, h, 0f).setUv(u, 0f).setColor(-1)
        buffer.addVertex(matrix, w, 0f, 0f).setUv(u, v).setColor(-1)
        BufferUploader.drawWithShader(buffer.buildOrThrow())
        RenderSystem.enableCull()
        RenderSystem.enableDepthTest()
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
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
        val fine = BattleEntryTimeline.fineCrack(frame.kind, frame.elapsed, frame.revealAt)
        if (shatter <= 0f) {
            frame.context.fill(0, 0, frame.width, frame.height, 0xFFFFFFFF.toInt())
            cracks(frame, impactX, impactY, crack, fine, 1f)
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
        cracks(frame, impactX, impactY, crack, fine, 1f - shatter * 3f)
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
        // Quiet: each shard eases away and melts out rather than flying.
        val alpha = 1f - BattleEntryTimeline.smooth(t)
        if (alpha <= 0f) return
        val norm = hypot(dx, dy).coerceAtLeast(1f)
        val push = frame.far * (.02f + .03f * hash(seed + 1)) * t
        val moveX = dx / norm * push
        val moveY = dy / norm * push
        val spin = (hash(seed + 2) - .5f) * .25f * t
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
    private fun cracks(frame: EntryFrame, impactX: Float, impactY: Float, growth: Float, fine: Float, opacity: Float) {
        if (growth <= 0f || opacity <= 0f) return
        val color = BattleSurfaceRenderer.withOpacity(BattleSurfaceRenderer.interpolate(frame.accent, 0xFF000000.toInt(), .45f),
            .7f * opacity.coerceAtMost(1f))
        val faint = BattleSurfaceRenderer.withOpacity(BattleSurfaceRenderer.interpolate(frame.accent, 0xFF000000.toInt(), .35f),
            .45f * opacity.coerceAtMost(1f))
        quads(frame.context) { buffer, matrix ->
            /** One straight piece of a crack from (x, y) to (nx, ny), [half] wide on each side. */
            fun piece(x: Float, y: Float, nx: Float, ny: Float, half: Float, color: Int) {
                val dx = nx - x
                val dy = ny - y
                val norm = hypot(dx, dy).coerceAtLeast(.001f)
                val px = -dy / norm * half
                val py = dx / norm * half
                buffer.addVertex(matrix, x + px, y + py, 0f).setColor(color)
                buffer.addVertex(matrix, x - px, y - py, 0f).setColor(color)
                buffer.addVertex(matrix, nx - px, ny - py, 0f).setColor(color)
                buffer.addVertex(matrix, nx + px, ny + py, 0f).setColor(color)
            }
            // The fine cracks: from each bend of a first crack, two thin branches of a few short, jagged pieces.
            if (fine > 0f) for (crack in 0 until CRACKS) {
                val seed = crack * 4099 + 7
                var angle = (crack + hash(seed) * .7f) / CRACKS * TAU
                var x = impactX
                var y = impactY
                for (segment in 0 until CRACK_SEGMENTS) {
                    angle += (hash(seed + segment * 13 + 1) - .5f) * .7f
                    val length = frame.far * (.12f + .14f * hash(seed + segment * 13 + 2))
                    x += cos(angle) * length
                    y += sin(angle) * length
                    for (branch in 0 until 2) {
                        val branchSeed = seed * 31 + segment * 7 + branch
                        var branchAngle = angle + (if (branch == 0) 1f else -1f) * (.6f + .8f * hash(branchSeed))
                        var bx = x
                        var by = y
                        val pieces = FINE_SEGMENTS * fine
                        for (step in 0 until FINE_SEGMENTS) {
                            val share = (pieces - step).coerceIn(0f, 1f)
                            if (share <= 0f) break
                            branchAngle += (hash(branchSeed + step * 5 + 1) - .5f) * .9f
                            val stepLength = frame.far * (.025f + .035f * hash(branchSeed + step * 5 + 2)) * share
                            val nx = bx + cos(branchAngle) * stepLength
                            val ny = by + sin(branchAngle) * stepLength
                            piece(bx, by, nx, ny, .3f, faint)
                            bx = nx
                            by = ny
                        }
                    }
                }
            }
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
                    piece(x, y, nx, ny, .8f * (1f - segment / CRACK_SEGMENTS.toFloat()) + .35f, color)
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

    /**
     * One zoom of the screen copy [texture] at [progress] (0 to 1), its copies turning by [spin] (a direction) as they
     * grow. [additive] copies brighten the screen (they need a darkened one to show); others lie over it half-seen.
     */
    fun zoom(frame: EntryFrame, texture: Int, progress: Float, spin: Float = 0f, additive: Boolean = true) {
        // Additive copies burst out and fade; the others spread slowly and evenly, fading in and staying until covered.
        val reach = if (additive) 1f - (1f - progress) * (1f - progress) else progress
        val fade = if (additive) (1f - progress) * (1f - progress) else (progress / .1f).coerceAtMost(1f)
        val tint = BattleSurfaceRenderer.interpolate(0xFFFFFFFF.toInt(), frame.accent, .3f)
        val matrix = frame.context.pose().last().pose()
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader)
        RenderSystem.setShaderTexture(0, texture)
        RenderSystem.enableBlend()
        if (additive) RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE)
        else RenderSystem.defaultBlendFunc()
        RenderSystem.disableDepthTest()
        RenderSystem.disableCull()
        val buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR)
        val strength = if (additive) .24f else .38f
        for (copy in 1..ZOOM_COPIES) {
            val scale = 1f + ZOOM_STEP * copy * (.3f + .7f * reach)
            val alpha = strength * fade * (1f - .5f * (copy - 1) / ZOOM_COPIES)
            val color = BattleSurfaceRenderer.withOpacity(tint, alpha)
            val halfW = frame.width / 2f * scale
            val halfH = frame.height / 2f * scale
            val angle = spin * (if (additive) .09f else .14f) * copy * reach
            val turnCos = cos(angle)
            val turnSin = sin(angle)
            fun corner(dx: Float, dy: Float, u: Float, v: Float) {
                buffer.addVertex(matrix, frame.centerX + dx * turnCos - dy * turnSin, frame.centerY + dx * turnSin + dy * turnCos, 0f)
                    .setUv(u, v).setColor(color)
            }
            // The copy's texture is upside down: framebuffers start at the bottom.
            corner(-halfW, -halfH, 0f, 1f)
            corner(-halfW, halfH, 0f, 0f)
            corner(halfW, halfH, 1f, 0f)
            corner(halfW, -halfH, 1f, 1f)
        }
        BufferUploader.drawWithShader(buffer.buildOrThrow())
        RenderSystem.enableCull()
        RenderSystem.enableDepthTest()
        RenderSystem.defaultBlendFunc()
    }

    /**
     * The zoom blur at [progress] (0 to 1) over a screen [warmth] (0 to 1) of the way to burning white: the screen copy
     * [texture] laid over itself in ever larger copies, up to [BLUR_REACH] times its size, then a warm light over it.
     */
    fun zoomBlur(frame: EntryFrame, texture: Int?, progress: Float, warmth: Float) {
        if (texture != null && progress > 0f) {
            val reach = BLUR_REACH * BattleEntryTimeline.smooth(progress)
            val appear = (progress / .15f).coerceAtMost(1f)
            val matrix = frame.context.pose().last().pose()
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader)
            RenderSystem.setShaderTexture(0, texture)
            RenderSystem.enableBlend()
            RenderSystem.defaultBlendFunc()
            RenderSystem.disableDepthTest()
            RenderSystem.disableCull()
            val buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR)
            for (copy in 1..BLUR_COPIES) {
                val scale = 1f + reach * copy / BLUR_COPIES
                val color = BattleSurfaceRenderer.withOpacity(0xFFFFFFFF.toInt(), .3f * appear)
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
        }
        if (warmth > 0f) {
            // Warm amber at first, paling to white as it brightens.
            val amber = BattleSurfaceRenderer.interpolate(0xFFFFC860.toInt(), frame.accent, .2f)
            val light = BattleSurfaceRenderer.interpolate(amber, 0xFFFFFFFF.toInt(), warmth)
            RenderSystem.enableBlend()
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE)
            frame.context.fill(0, 0, frame.width, frame.height, BattleSurfaceRenderer.withOpacity(light, .55f * warmth))
            frame.context.flush()
            RenderSystem.defaultBlendFunc()
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

    /** A steady pseudo-random share, 0 to 1, for [seed]. */
    private fun hash(seed: Int): Float {
        var x = seed * -0x61c88647
        x = x xor (x ushr 15)
        x *= 0x2c1b3c6d
        x = x xor (x ushr 12)
        return (x ushr 8) / 16777216f
    }
}
