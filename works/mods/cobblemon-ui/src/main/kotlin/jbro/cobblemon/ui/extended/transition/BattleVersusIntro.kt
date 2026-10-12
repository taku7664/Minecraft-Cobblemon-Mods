package jbro.cobblemon.ui.extended.transition

import com.mojang.brigadier.context.CommandContext
import jbro.cobblemon.ui.extended.ui.shared.BattleSurfaceRenderer
import jbro.cobblemon.ui.extended.ui.shared.BattleUiTheme
import jbro.cobblemon.uikit.UiModelFraming
import jbro.cobblemon.uikit.UiRect
import jbro.cobblemon.uikit.client.CobblemonUiRenderContent
import jbro.cobblemon.uikit.client.CobblemonUiRenderSlot
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.ChatFormatting
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** How big a battle the versus intro announces; each tier runs longer and adds to the one before. */
enum class VersusTier(val id: String, val millis: Long) {
    GYM("gym", 2_600),
    ELITE_FOUR("elite_four", 3_000),
    CHAMPION("champion", 3_200),
    /** Over a PvP battle that is already open, so kept short. */
    PVP("pvp", 2_400);

    companion object {
        fun fromId(id: String): VersusTier? = entries.firstOrNull { it.id == id }
    }
}

/** One side of the versus intro: who stands there, their title above their name, and how they are drawn. */
data class VersusSide(val name: Component, val title: Component, val portrait: CobblemonUiRenderContent)

/**
 * The versus screen before a League battle, after the handheld games' gym leader screens: the screen splits along a
 * slant, the challenger's half slides in from the left and the opponent's from the right with speed lines running
 * through them, both trainers step in under name plates, and "VS" slams down between them with a flash and a shake.
 * The Elite Four adds crossing slashes; a Champion's half is gold with sparks rising through it. It ends washed out to
 * white and runs [onDone] (the battle may start); the white holds until the battle opens ([reveal]) and then fades into
 * it. It draws above every screen and the HUD.
 */
object BattleVersusIntro {
    private class Run(val tier: VersusTier, val player: VersusSide, val opponent: VersusSide, val accent: Int, val hard: Boolean,
                      val startedAt: Long, val onDone: Runnable?) {
        var done = false
        var impactPlayed = false
    }

    @Volatile
    private var run: Run? = null
    /**
     * Once the intro has ended the screen stays white until the battle opens ([reveal]) or [WHITE_HOLD] passes, and then
     * fades into the battle: when the white began, and when it started to fade.
     */
    private var whiteSince = 0L
    private var fadeFrom = 0L

    /** For the capture harness: draws the intro as it stands this many milliseconds in, without running the clock. */
    @JvmStatic
    var frozenAt: Long? = null

    val active: Boolean get() = run != null

    /**
     * Plays the intro for [tier] between [player] and [opponent] in [accent] (the opponent's colour, a Champion's
     * is always gold), and runs [onDone] when it ends. False when battle entry transitions are turned off.
     */
    @JvmStatic
    fun play(tier: VersusTier, player: VersusSide, opponent: VersusSide, accent: Int?, hard: Boolean, onDone: Runnable?): Boolean {
        if (!jbro.cobblemon.ui.extended.PanelConfig.enableBattleEntryTransition) return false
        run?.takeIf { !it.done }?.let { finish(it) }
        val color = if (tier == VersusTier.CHAMPION) GOLD else (accent ?: BattleUiTheme.palette.opponent) or 0xFF000000.toInt()
        run = Run(tier, player, opponent, color, hard, Util.getMillis(), onDone)
        val client = Minecraft.getInstance()
        if (client.screen == null) client.setScreen(BattleEntryScreen())
        return true
    }

    @JvmStatic
    fun cancel() {
        run = null
        whiteSince = 0
        fadeFrom = 0
        closeInputScreen()
    }

    /** The battle has opened: the white the intro ended in fades away to show it. */
    @JvmStatic
    fun reveal() {
        if (whiteSince != 0L && fadeFrom == 0L) {
            fadeFrom = Util.getMillis()
            closeInputScreen()
        }
    }

    private fun closeInputScreen() {
        val client = Minecraft.getInstance()
        if (client.screen is BattleEntryScreen && !BattleEntryTransition.active) client.setScreen(null)
    }

    fun install() {
        HudRenderCallback.EVENT.register { context, _ -> if (Minecraft.getInstance().screen == null) render(context) }
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            ScreenEvents.afterRender(screen).register { _, context, _, _, _ -> render(context) }
        }
        ClientTickEvents.END_CLIENT_TICK.register { _ -> check() }
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            val root = literal("battleentry").then(literal("versus")
                .then(literal("gym").executes { preview(it, VersusTier.GYM) })
                .then(literal("elite_four").executes { preview(it, VersusTier.ELITE_FOUR) })
                .then(literal("champion").executes { preview(it, VersusTier.CHAMPION) }))
            dispatcher.register(root)
        }
    }

    /** `/battleentry versus <tier>`: the player against themselves, with no battle behind it. */
    private fun preview(context: CommandContext<FabricClientCommandSource>, tier: VersusTier): Int {
        val client = context.source.client
        val profile = client.player?.gameProfile ?: return 0
        val me = VersusSide(Component.literal(profile.name), Component.translatable("cobblemon_ui.versus.challenger"),
            CobblemonUiRenderContent.PlayerProfile(profile, UiModelFraming.FULL_BODY))
        val opponent = VersusSide(Component.literal(profile.name), Component.translatable("cobblemon_ui.versus.${tier.id}"),
            CobblemonUiRenderContent.PlayerProfile(profile, UiModelFraming.FULL_BODY))
        client.execute {
            play(tier, me, opponent, null, false) { cancel() }
        }
        return 1
    }

    private fun elapsed(current: Run): Long = frozenAt ?: (Util.getMillis() - current.startedAt)

    private fun check() {
        if (whiteSince != 0L && fadeFrom == 0L && Util.getMillis() - whiteSince > WHITE_HOLD) reveal()
        val current = run ?: return
        if (frozenAt == null && !current.done && elapsed(current) >= current.tier.millis) finish(current)
    }

    private fun finish(current: Run) {
        current.done = true
        if (run === current) run = null
        whiteSince = Util.getMillis()
        fadeFrom = 0L
        try {
            current.onDone?.run()
        } catch (failure: RuntimeException) {
            jbro.cobblemon.ui.extended.CobblemonUi.LOGGER.warn("Versus intro callback failed", failure)
        }
    }

    // ---- drawing ----

    private fun render(context: GuiGraphics) {
        val current = run
        if (current == null) {
            if (whiteSince == 0L) return
            check()
            val white = if (fadeFrom == 0L) 1f else 1 - (Util.getMillis() - fadeFrom) / FADE_IN.toFloat()
            if (white <= 0f) {
                whiteSince = 0L
                fadeFrom = 0L
                return
            }
            context.pose().pushPose()
            context.pose().translate(0f, 0f, 600f)
            context.fill(0, 0, context.guiWidth(), context.guiHeight(), alpha(0xFFFFFFFF.toInt(), 1 - (1 - white.coerceIn(0f, 1f)).pow(2)))
            context.pose().popPose()
            return
        }
        check()
        if (run !== current) return
        val t = elapsed(current)
        val w = context.guiWidth()
        val h = context.guiHeight()
        val pose = context.pose()
        pose.pushPose()
        pose.translate(0f, 0f, 600f)
        try {
            drawMain(context, current, t, w, h)
        } finally {
            pose.popPose()
        }
    }

    private fun drawMain(context: GuiGraphics, current: Run, t: Long, w: Int, h: Int) {
        val total = current.tier.millis
        val bar = (h * .1f).toInt().coerceAtLeast(12)
        // Shake after the VS lands, and the white flash it makes.
        val impactAt = IMPACT
        if (t >= impactAt && !current.impactPlayed) {
            current.impactPlayed = true
            sound(SoundEvents.GENERIC_EXPLODE.value(), 1.5f, .35f)
            sound(SoundEvents.PLAYER_ATTACK_CRIT, .8f, .7f)
        }
        val shake = if (t >= impactAt) decay((t - impactAt) / 380f) * 4f else 0f
        val sx = shakeX(t, shake)
        val sy = shakeY(t, shake)

        // Behind everything: the world dimmed.
        context.fill(0, 0, w, h, alpha(0xFF05070D.toInt(), .78f * ease(t / 180f)))
        val pose = context.pose()
        pose.pushPose()
        pose.translate(sx, sy, 0f)

        val slide = easeOutBack(((t - 60) / 340f).coerceIn(0f, 1f))
        val top = bar
        val bottom = h - bar
        val seamTop = w * .57f
        val seamBottom = w * .43f
        fun seam(y: Int) = seamTop + (seamBottom - seamTop) * (y - top) / (bottom - top).toFloat()
        val playerShift = -(1 - slide) * w * .75f
        val opponentShift = (1 - slide) * w * .75f
        val palette = BattleUiTheme.palette
        val playerTop = mix(palette.ally, 0xFF0B1A2E.toInt(), .55f)
        val playerBottom = mix(palette.ally, 0xFF050B16.toInt(), .82f)
        val opponentTop = mix(current.accent, 0xFF1A0A1E.toInt(), .5f)
        val opponentBottom = mix(current.accent, 0xFF0A0510.toInt(), .82f)
        context.drawManaged {
            for (y in top until bottom) {
                val f = (y - top) / (bottom - top).toFloat()
                val split = seam(y)
                val left = (split - SEAM_GAP + playerShift).toInt()
                if (left > 0) context.fill(0, y, min(left, w), y + 1, mix(playerTop, playerBottom, f))
                val right = (split + SEAM_GAP + opponentShift).toInt()
                if (right < w) context.fill(max(right, 0), y, w, y + 1, mix(opponentTop, opponentBottom, f))
            }
        }
        drawSpeedLines(context, t, w, top, bottom, ::seam, playerShift, opponentShift, palette.ally, current.accent)
        if (current.tier == VersusTier.CHAMPION) drawSparks(context, t, w, top, bottom, ::seam, opponentShift)

        // The trainers, a beat after their halves.
        val step = easeOut(((t - 200) / 320f).coerceIn(0f, 1f))
        val plateHeight = (h * .16f).toInt().coerceIn(26, 44)
        // Each trainer stands a little smaller than their half, the challenger high and the opponent low, on the diagonal.
        val modelWidth = (w * .26f).toInt()
        val modelHeight = ((bottom - top - plateHeight) * .86f).toInt()
        val playerModel = UiRect((w * .24f - modelWidth / 2 - (1 - step) * w * .25f).toInt(), top + 4, modelWidth, modelHeight)
        val opponentModel = UiRect((w * .76f - modelWidth / 2 + (1 - step) * w * .25f).toInt(), bottom - modelHeight - 2,
            modelWidth, modelHeight)
        if (step > 0) {
            CobblemonUiRenderSlot.drawContent(context, playerModel, current.player.portrait, 0f)
            CobblemonUiRenderSlot.drawContent(context, opponentModel, current.opponent.portrait, 0f)
        }

        // Name plates: the challenger's along the bottom left, the opponent's along the top right.
        val plateIn = easeOutBack(((t - 380) / 280f).coerceIn(0f, 1f))
        if (plateIn > 0) {
            val plateWidth = (w * .42f).toInt()
            drawPlate(context, current.player, (-(1 - plateIn) * plateWidth).toInt(), bottom - plateHeight, plateWidth, plateHeight,
                palette.ally, left = true, hard = false)
            drawPlate(context, current.opponent, (w - plateWidth + (1 - plateIn) * plateWidth).toInt(), top, plateWidth, plateHeight,
                current.accent, left = false, hard = current.hard)
        }

        // The seam lights up as the VS lands.
        if (t >= impactAt) {
            val glow = .35f + .65f * decay((t - impactAt) / 500f)
            context.drawManaged {
                for (y in top until bottom) {
                    val x = (seam(y)).toInt()
                    context.fill(x - 1, y, x + 2, y + 1, alpha(0xFFFFFFFF.toInt(), glow))
                }
            }
            if (current.tier != VersusTier.GYM) drawSlashes(context, t - impactAt, w, h, current.accent)
            val ring = ((t - impactAt) / 420f).coerceIn(0f, 1f)
            if (ring < 1) drawRing(context, w / 2f, h / 2f, h * (.1f + .7f * easeOut(ring)), alpha(0xFFFFFFFF.toInt(), 1 - ring))
        }

        // VS: dropped in large and slammed down to size.
        val vsIn = ((t - (impactAt - 200)) / 200f).coerceIn(0f, 1f)
        if (vsIn > 0) {
            // Slammed down once, then held still.
            val scale = h / 40f * (1.8f - .8f * easeIn(vsIn))
            // The screen shakes under it; the letters themselves stay put.
            // Always opaque: the outline is the text drawn eight times, and see-through copies stack into stripes.
            drawBig(context, Component.literal("VS").withStyle(ChatFormatting.BOLD), w / 2f - sx, h / 2f - sy, scale,
                0xFFFFFFFF.toInt(), mix(current.accent, 0xFF000000.toInt(), .45f), 1f)
        }
        pose.popPose()

        // Letterbox bars, outside the shake.
        val barIn = ease(t / 220f)
        context.fill(0, 0, w, (bar * barIn).toInt(), 0xFF000000.toInt())
        context.fill(0, h - (bar * barIn).toInt(), w, h, 0xFF000000.toInt())

        val flash = if (t >= impactAt) 1 - ((t - impactAt) / 160f).coerceIn(0f, 1f) else 0f
        if (flash > 0) context.fill(0, 0, w, h, alpha(0xFFFFFFFF.toInt(), .85f * flash))
        // The hand-over: everything washes out to white, which holds until the battle opens.
        val out = ((t - (total - EXIT)) / EXIT.toFloat()).coerceIn(0f, 1f)
        if (out > 0) context.fill(0, 0, w, h, alpha(0xFFFFFFFF.toInt(), easeIn(out)))
    }

    private fun drawPlate(context: GuiGraphics, side: VersusSide, x: Int, y: Int, width: Int, height: Int, accent: Int, left: Boolean, hard: Boolean) {
        val font = Minecraft.getInstance().font
        val slant = height / 2
        context.drawManaged {
            for (row in 0 until height) {
                // A parallelogram leaning the same way as the seam: its inner end runs from upper right to lower left.
                val offset = if (left) row * slant / height else (height - row) * slant / height
                val from = if (left) x else x + offset
                val to = if (left) x + width - offset else x + width
                context.fill(from, y + row, to, y + row + 1, alpha(0xFF070A12.toInt(), .86f))
            }
        }
        val stripeY = if (left) y else y + height - 2
        context.fill(x, stripeY, x + width, stripeY + 2, accent)
        val title = if (hard) Component.translatable("cobblemon_ui.versus.hard", side.title) else side.title
        val pad = 10
        val nameScale = (height / 22f).coerceIn(1f, 2f)
        if (left) {
            context.drawString(font, title, x + pad, y + 5, mix(accent, 0xFFFFFFFF.toInt(), .35f), true)
            drawScaled(context, side.name, x + pad, y + 5 + font.lineHeight + 2, nameScale)
        } else {
            val titleX = x + width - pad - font.width(title)
            context.drawString(font, title, titleX, y + 5, mix(accent, 0xFFFFFFFF.toInt(), .35f), true)
            drawScaled(context, side.name, x + width - pad - (font.width(side.name) * nameScale).toInt(), y + 5 + font.lineHeight + 2, nameScale)
        }
    }

    /** Streaks running through each half the way it came in. */
    private fun drawSpeedLines(context: GuiGraphics, t: Long, w: Int, top: Int, bottom: Int, seam: (Int) -> Float,
                               playerShift: Float, opponentShift: Float, ally: Int, accent: Int) {
        context.drawManaged {
            for (i in 0 until LINES) {
                val seed = hash(i)
                val y = top + ((bottom - top) * seed).toInt()
                val length = 30 + (hash(i + 97) * 110).toInt()
                val speed = .45f + hash(i + 13) * .5f
                val thickness = if (hash(i + 7) > .7f) 2 else 1
                val travel = w + length
                val playerSide = i % 2 == 0
                val raw = ((hash(i + 31) * travel + t * speed) % travel)
                val split = seam(y)
                if (playerSide) {
                    val x0 = (raw - length + playerShift).toInt()
                    val x1 = min((x0 + length).toFloat(), split - SEAM_GAP + playerShift).toInt()
                    if (x1 > x0) context.fill(max(x0, 0), y, x1, y + thickness, alpha(mix(ally, 0xFFFFFFFF.toInt(), .6f), .32f))
                } else {
                    val x1 = (w - raw + length + opponentShift).toInt()
                    val x0 = max((x1 - length).toFloat(), split + SEAM_GAP + opponentShift).toInt()
                    if (x1 > x0) context.fill(x0, y, min(x1, w), y + thickness, alpha(mix(accent, 0xFFFFFFFF.toInt(), .6f), .32f))
                }
            }
        }
    }

    /** A Champion's half fills with gold sparks drifting upward. */
    private fun drawSparks(context: GuiGraphics, t: Long, w: Int, top: Int, bottom: Int, seam: (Int) -> Float, shift: Float) {
        for (i in 0 until 26) {
            val rise = (hash(i + 400) * (bottom - top) + t * (.03f + hash(i + 500) * .05f)) % (bottom - top)
            val y = (bottom - rise).toInt()
            val minX = seam(y) + SEAM_GAP + shift
            val x = (minX + hash(i + 600) * (w - minX)).toInt()
            val twinkle = .5f + .5f * sin(t / 120f + i)
            val size = if (hash(i + 700) > .8f) 2 else 1
            context.fill(x, y, x + size, y + size, alpha(GOLD_LIGHT, .9f * twinkle))
        }
    }

    /** The Elite Four's and Champion's two slashes crossing the screen as the VS lands. */
    private fun drawSlashes(context: GuiGraphics, t: Long, w: Int, h: Int, accent: Int) {
        val life = (t / 360f).coerceIn(0f, 1f)
        if (life >= 1) return
        val reach = easeOut((t / 140f).coerceIn(0f, 1f))
        val fade = 1 - life
        context.drawManaged {
            for (slash in 0..1) {
                val steps = (w * reach).toInt()
                for (i in 0 until steps step 2) {
                    val x = if (slash == 0) i else w - i
                    val y = (h * (if (slash == 0) .22f else .78f) + (i - w / 2f) * (if (slash == 0) .32f else -.32f)).toInt()
                    context.fill(x, y, x + 2, y + 2, alpha(mix(accent, 0xFFFFFFFF.toInt(), .7f), .8f * fade))
                }
            }
        }
    }

    private fun drawRing(context: GuiGraphics, cx: Float, cy: Float, radius: Float, color: Int) {
        val points = (radius * 1.2f).toInt().coerceIn(24, 220)
        context.drawManaged {
            for (i in 0 until points) {
                val a = i * 2 * PI / points
                val x = (cx + radius * kotlin.math.cos(a)).toInt()
                val y = (cy + radius * kotlin.math.sin(a)).toInt()
                context.fill(x - 1, y - 1, x + 1, y + 1, color)
            }
        }
    }

    /** [text] centred on ([cx], [cy]) at [scale], with a heavy outline in [outline]. */
    private fun drawBig(context: GuiGraphics, text: Component, cx: Float, cy: Float, scale: Float, color: Int, outline: Int, opacity: Float) {
        val font = Minecraft.getInstance().font
        val pose = context.pose()
        pose.pushPose()
        pose.translate(cx, cy, 0f)
        pose.scale(scale, scale, 1f)
        val x = -font.width(text) / 2
        val y = -font.lineHeight / 2
        val edge = alpha(outline, opacity)
        for (dx in -1..1) for (dy in -1..1) if (dx != 0 || dy != 0) context.drawString(font, text, x + dx, y + dy, edge, false)
        context.drawString(font, text, x, y, alpha(color, opacity), false)
        pose.popPose()
    }

    private fun drawScaled(context: GuiGraphics, text: Component, x: Int, y: Int, scale: Float) {
        val font = Minecraft.getInstance().font
        val pose = context.pose()
        pose.pushPose()
        pose.translate(x.toFloat(), y.toFloat(), 0f)
        pose.scale(scale, scale, 1f)
        context.drawString(font, text, 0, 0, 0xFFFFFFFF.toInt(), true)
        pose.popPose()
    }

    private fun sound(event: net.minecraft.sounds.SoundEvent, pitch: Float, volume: Float) {
        if (frozenAt != null) return
        Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(event, pitch, volume))
    }

    // ---- easing and colour ----

    private fun ease(f: Float) = f.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
    private fun easeOut(f: Float) = 1 - (1 - f.coerceIn(0f, 1f)).pow(3)
    private fun easeIn(f: Float) = f.coerceIn(0f, 1f).pow(3)
    private fun easeOutBack(f: Float): Float {
        val x = f.coerceIn(0f, 1f)
        val c = 1.70158f
        return 1 + (c + 1) * (x - 1).pow(3) + c * (x - 1).pow(2)
    }
    private fun decay(f: Float) = (1 - f.coerceIn(0f, 1f)).pow(2)
    private fun shakeX(t: Long, amount: Float) = if (amount <= 0) 0f else amount * sin(t * .09f) * sin(t * .031f + 1)
    private fun shakeY(t: Long, amount: Float) = if (amount <= 0) 0f else amount * sin(t * .077f + 2) * sin(t * .043f)
    private fun hash(i: Int): Float {
        var x = i * 374761393 + 668265263
        x = (x xor (x ushr 13)) * 1274126177
        return ((x xor (x ushr 16)) and 0xFFFFFF) / 16777216f
    }
    private fun alpha(color: Int, opacity: Float): Int = BattleSurfaceRenderer.withOpacity(color, opacity.coerceIn(0f, 1f))
    private fun mix(a: Int, b: Int, f: Float): Int = BattleSurfaceRenderer.interpolate(a or 0xFF000000.toInt(), b, f)

    private const val IMPACT = 820L
    private const val EXIT = 260L
    /** How long the white waits for the battle before it fades anyway, and how long it takes to fade. */
    private const val WHITE_HOLD = 4_000L
    private const val FADE_IN = 350L
    private const val LINES = 34
    private const val SEAM_GAP = 3f
    private val GOLD = 0xFFE8B530.toInt()
    private val GOLD_LIGHT = 0xFFFFE9A8.toInt()
}
