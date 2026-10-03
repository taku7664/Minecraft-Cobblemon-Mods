package jbro.cobblemon.ui.extended.transition

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.labels.CobblemonPokemonLabels
import jbro.cobblemon.ui.extended.PanelConfig
import jbro.cobblemon.ui.extended.UIUtils
import jbro.cobblemon.ui.extended.ui.shared.BattleUiTheme
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation

/**
 * The screen transition into a battle. [play] runs the kind's [EntryStages]: an intro, the cover in its mood, then the
 * whiteout that turns the covered screen fully white, at which point it reports the screen covered so whoever holds
 * the battle starts it; once the battle opens ([reveal], or a timeout) the fade-in takes the white away to show it.
 * It draws above every screen and the HUD.
 */
object BattleEntryTransition {
    private class Run(val kind: BattleEntryKind, val stages: EntryStages, val accent: Int, val startedAt: Long,
                      val onCovered: Runnable?) {
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
     * Plays [kind] against [species] (the wild Pokémon, or the trainer's lead), whose primary type colours the
     * transition, and runs [onCovered] once the screen is covered. False when the transition is turned off, and
     * [onCovered] will not run.
     */
    @JvmStatic
    @JvmOverloads
    fun play(kind: BattleEntryKind, species: ResourceLocation?, onCovered: Runnable?, blockInput: Boolean = true): Boolean {
        val accent = species?.let { PokemonSpecies.getByIdentifier(it) }?.let { UIUtils.getTypeColor(it.primaryType) }
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
        run = Run(kind, kind.variants.random(), color, Util.getMillis(), onCovered)
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

    /** Draws the current transition's stages in order; see [EntryStages]. */
    private fun render(context: GuiGraphics) {
        check()
        val current = run ?: return
        val elapsed = Util.getMillis() - current.startedAt
        val revealAt = current.revealAt?.let { it - current.startedAt }
        val kind = current.kind
        val stages = current.stages
        if (BattleEntryTimeline.revealed(kind, elapsed, revealAt, stages.fadeIn)) {
            run = null
            return
        }
        val pose = context.pose()
        pose.pushPose()
        pose.translate(0f, 0f, 500f)
        val frame = EntryFrame(context, context.guiWidth(), context.guiHeight(), kind, current.accent, elapsed, revealAt)
        // The intro may copy the screen as it stands, before anything of the transition is drawn over it.
        val screen = stages.intro.prepare(frame)
        if (frame.covering) {
            stages.mood.drawBehind(frame)
            val (shakeX, shakeY) = stages.mood.shake(frame)
            pose.pushPose()
            pose.translate(shakeX, shakeY, 0f)
            stages.intro.draw(frame, screen)
            stages.cover.draw(frame)
            pose.popPose()
            stages.mood.drawFront(frame)
            if (elapsed >= BattleEntryTimeline.riseStart(kind)) stages.whiteout.draw(frame)
        } else {
            stages.fadeIn.draw(frame)
        }
        pose.popPose()
    }
}

/** An empty screen held while the transition covers the world, so the player stands still until the battle. */
internal class BattleEntryScreen : Screen(Component.empty()) {
    override fun isPauseScreen() = false
    override fun shouldCloseOnEsc() = false
    override fun renderBackground(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) = Unit
}
