package jbro.cobblemon.battleui.extended.ui.shared

import com.cobblemon.mod.common.api.moves.Moves
import com.cobblemon.mod.common.api.types.ElementalTypes
import jbro.cobblemon.battleui.extended.BattleDialogue
import jbro.cobblemon.battleui.extended.CobblemonExtendedBattleUI
import jbro.cobblemon.battleui.extended.ui.champions.ChampionsBattleInfoOverlay
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.screen.TitleScreen
import net.minecraft.client.gui.screen.world.CreateWorldScreen
import net.minecraft.client.gui.screen.world.WorldCreator
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.TranslatableTextContent
import com.cobblemon.mod.common.client.gui.snapshots.SnapshotWarningScreen
import net.minecraft.client.util.ScreenshotRecorder
import net.minecraft.text.Text
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in development fixture. No hooks or automatic screens in deployed clients. */
internal object BattleThemeCapture {
    fun install() {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment ||
            System.getenv("COBBLEMON_BATTLE_UI_CAPTURE") != "1") return
        var started = false
        var created = false
        var opened = false
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!started && client.currentScreen is TitleScreen && client.overlay == null) {
                started = true
                org.lwjgl.glfw.GLFW.glfwSetWindowSize(client.window.handle, 1600, 900)
                client.options.guiScale.value = 2
                client.onResolutionChanged()
                val language = System.getenv("COBBLEMON_BATTLE_UI_CAPTURE_LANGUAGE") ?: "en_us"
                require(language == "en_us" || language == "ko_kr")
                client.options.language = language
                client.languageManager.language = language
                client.reloadResources().thenRun { client.execute { CreateWorldScreen.create(client, TitleScreen()) } }
            }
            val creation = client.currentScreen as? CreateWorldScreen
            if (started && !created && creation != null) {
                created = true
                creation.worldCreator.worldName = "Battle UI capture fixture"
                creation.worldCreator.gameMode = WorldCreator.Mode.CREATIVE
                creation.worldCreator.seed = "2718"
                creation.children().filterIsInstance<ButtonWidget>().single {
                    (it.message.content as? TranslatableTextContent)?.key == "selectWorld.create"
                }.onPress()
            }
            val warning = client.currentScreen as? SnapshotWarningScreen
            if (started && warning != null) {
                // Only our freshly generated, disposable development world is in scope.
                warning.consumer(SnapshotWarningScreen.Acknowledgement.YES, false)
            }
            if (created && !opened && client.world != null && client.player != null && client.currentScreen == null && client.overlay == null) {
                opened = true
                CobblemonExtendedBattleUI.LOGGER.info("Battle UI fixture opened in loaded world {}", client.world!!.registryKey.value)
                client.setScreen(CaptureScreen())
            }
        }
    }

    private class CaptureScreen : Screen(Text.literal("Battle UI rendering fixture")) {
        private var page = 0
        private var ticks = 0
        private var pending = false
        private val saved = AtomicBoolean(false)

        override fun tick() {
            val mc = client ?: return
            if (pending) {
                if (!saved.getAndSet(false)) return
                pending = false
                page++
                ticks = 0
                if (page >= 2) mc.scheduleStop()
                return
            }
            if (++ticks < 40) return
            pending = true
            val filename = "battle-ui-theme-${if (page == 0) "controls" else "info"}-${mc.options.language}.png"
            ScreenshotRecorder.saveScreenshot(mc.runDirectory, filename, mc.framebuffer) {
                CobblemonExtendedBattleUI.LOGGER.info("Battle UI fixture capture: {}", it.string)
                saved.set(true)
            }
        }

        override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) = Unit

        override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
            context.fillGradient(0, 0, width, height, 0xFF182A39.toInt(), 0xFF080E17.toInt())
            context.drawText(textRenderer, "DEVELOPMENT FIXTURE / ${client!!.options.language} / not a live battle", 12, 10, BattleUiTheme.MUTED, false)
            if (page != 0) {
                ChampionsBattleInfoOverlay.renderPreview(context)
                return
            }
            val left = (width - 390) / 2
            BattleSurfaceRenderer.draw(context, left - 16, 38, 422, 170, BattleUiTheme.shell)
            val labels = listOf("fight", "switch", "capture", "run")
            val styles = listOf(BattleUiTheme.primary, BattleUiTheme.secondary, BattleUiTheme.capture, BattleUiTheme.danger)
            labels.forEachIndexed { index, key ->
                BattleControlRenderer.drawOption(context, left + index * 100, 60,
                    Text.translatable("cobblemon.battle.ui.$key"), styles[index], index == 0, false)
            }
            val moves = listOf("thunderbolt", "quickattack", "protect", "irontail")
            val types = listOf("electric", "normal", "normal", "steel")
            val colors = listOf(0xFFF3D03E.toInt(), 0xFFA8A878.toInt(), 0xFFA8A878.toInt(), 0xFFB8B8D0.toInt())
            moves.forEachIndexed { index, name ->
                val move = requireNotNull(Moves.getByName(name)) { "Fixture move not loaded: $name" }
                BattleControlRenderer.drawMove(context, (left + index * 100).toFloat(), 120f,
                    move, ElementalTypes.get(types[index])!!, colors[index], if (index == 3) 0 else 10, 15,
                    index != 3, index == 1)
            }
            context.drawText(textRenderer, "Normal / keyboard focus / normal / disabled (0 PP)", left, 171, BattleUiTheme.MUTED, false)
            val message = if (client!!.options.language == "ko_kr") "피카츄의 10만볼트! 상대 리자몽에게 효과가 굉장했다!"
                else "Pikachu used Thunderbolt! It was super effective against Charizard!"
            BattleDialogue.renderMessage(context, Text.literal(message))
        }
    }
}
