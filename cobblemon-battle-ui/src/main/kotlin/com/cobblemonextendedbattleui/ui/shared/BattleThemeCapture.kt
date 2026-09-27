package jbro.cobblemon.battleui.extended.ui.shared

import com.cobblemon.mod.common.api.moves.Moves
import com.cobblemon.mod.common.api.types.ElementalTypes
import jbro.cobblemon.battleui.extended.BattleDialogue
import jbro.cobblemon.battleui.extended.MoveTooltipRenderer
import jbro.cobblemon.battleui.navigation.BattleMenuLayout
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection
import jbro.cobblemon.battleui.extended.CobblemonExtendedBattleUI
import jbro.cobblemon.battleui.extended.mixin.BattleSwitchTileAccessor
import jbro.cobblemon.battleui.extended.navigation.ForfeitSelectionAccess
import jbro.cobblemon.battleui.extended.ui.champions.ChampionsBattleInfoOverlay
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.screen.TitleScreen
import net.minecraft.client.gui.screen.world.CreateWorldScreen
import net.minecraft.client.gui.screen.world.WorldCreator
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.tutorial.TutorialStep
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
        val liveBattle = System.getenv("COBBLEMON_BATTLE_UI_CAPTURE_LIVE_BATTLE") == "1"
        if (liveBattle) BattleLiveCapture.install()
        var started = false
        var created = false
        var opened = false
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!started && client.currentScreen is TitleScreen && client.overlay == null) {
                started = true
                val captureWidth = System.getenv("COBBLEMON_BATTLE_UI_CAPTURE_WIDTH")?.toInt() ?: 1600
                val captureHeight = System.getenv("COBBLEMON_BATTLE_UI_CAPTURE_HEIGHT")?.toInt() ?: 900
                org.lwjgl.glfw.GLFW.glfwSetWindowSize(client.window.handle, captureWidth, captureHeight)
                client.options.guiScale.value = System.getenv("COBBLEMON_BATTLE_UI_CAPTURE_GUI_SCALE")?.toInt() ?: 2
                client.options.tutorialStep = TutorialStep.NONE
                client.onResolutionChanged()
                val language = System.getenv("COBBLEMON_BATTLE_UI_CAPTURE_LANGUAGE") ?: "en_us"
                require(language == "en_us" || language == "ko_kr")
                client.options.language = language
                client.languageManager.language = language
                System.getenv("COBBLEMON_BATTLE_UI_CAPTURE_FONT_PACK")?.let { filename ->
                    require(!filename.contains('/') && !filename.contains('\\'))
                    client.resourcePackManager.scanPacks()
                    check(client.resourcePackManager.enable("file/$filename")) { "Missing capture font pack: $filename" }
                }
                client.reloadResources().thenRun { client.execute { CreateWorldScreen.create(client, TitleScreen()) } }
            }
            val creation = client.currentScreen as? CreateWorldScreen
            if (started && !created && creation != null) {
                created = true
                creation.worldCreator.worldName = if (liveBattle) "Battle UI live fixture" else "Battle UI capture fixture"
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
                if (System.getenv("COBBLEMON_BATTLE_UI_VERIFY_SELECTION_MIXINS") == "1") {
                    val loader = Thread.currentThread().contextClassLoader
                    val forfeit = Class.forName(
                        "com.cobblemon.mod.common.client.gui.battle.subscreen.ForfeitConfirmationSelection", false, loader)
                    val switchTile = Class.forName(
                        "com.cobblemon.mod.common.client.gui.battle.subscreen.BattleSwitchPokemonSelection${'$'}SwitchTile", false, loader)
                    check(ForfeitSelectionAccess::class.java.isAssignableFrom(forfeit)) { "Forfeit navigation mixin missing" }
                    check(BattleSwitchTileAccessor::class.java.isAssignableFrom(switchTile)) { "Switch hitbox accessor mixin missing" }
                    CobblemonExtendedBattleUI.LOGGER.info("Battle UI selection mixins applied to Cobblemon 1.8.1 classes")
                }
                if (System.getenv("COBBLEMON_BATTLE_UI_CAPTURE_TRANSCRIPT_ONLY") == "1") {
                    jbro.cobblemon.battleui.extended.ui.transcript.TranscriptInputFixture.verify()
                }
                if (liveBattle) BattleLiveCapture.start(client) else client.setScreen(CaptureScreen())
            }
        }
    }

    private class CaptureScreen : Screen(Text.literal("Battle UI rendering fixture")) {
        private val pages = when {
            System.getenv("COBBLEMON_BATTLE_UI_CAPTURE_DRAFT_ONLY") == "1" ->
                listOf("draft-menu-rail", "draft-menu-blade", "draft-moves", "draft-hud-double",
                    "draft-hud-triple", "draft-switch", "draft-forfeit")
            System.getenv("COBBLEMON_BATTLE_UI_CAPTURE_TRANSCRIPT_ONLY") == "1" ->
                listOf("log", "log-long", "log-empty")
            else -> listOf("controls", "info", "info-double", "info-triple", "tooltip")
        }
        private var page = 0
        private var ticks = 0
        private var pending = false
        private val saved = AtomicBoolean(false)

        override fun tick() {
            if (page !in pages.indices) return
            val mc = client ?: return
            if (pending) {
                if (!saved.getAndSet(false)) return
                pending = false
                page++
                ticks = 0
                if (page >= pages.size) mc.scheduleStop()
                return
            }
            val waitTicks = System.getenv("COBBLEMON_BATTLE_UI_CAPTURE_WAIT_TICKS")?.toInt() ?: 40
            require(waitTicks in 20..400)
            if (++ticks < waitTicks) return
            pending = true
            val label = System.getenv("COBBLEMON_BATTLE_UI_CAPTURE_LABEL") ?: "theme"
            require(label.matches(Regex("[a-z0-9-]+")))
            val filename = "battle-ui-$label-${pages[page]}-${mc.options.language}.png"
            ScreenshotRecorder.saveScreenshot(mc.runDirectory, filename, mc.framebuffer) {
                CobblemonExtendedBattleUI.LOGGER.info("Battle UI fixture capture: {}", it.string)
                saved.set(true)
            }
        }

        override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) = Unit

        override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
            if (page !in pages.indices) return
            if (pages[page].startsWith("draft-")) {
                BattleScreenDraft.render(context, pages[page], width, height)
                return
            }
            context.fillGradient(0, 0, width, height, 0xFF182A39.toInt(), 0xFF080E17.toInt())
            context.drawText(textRenderer, "DEVELOPMENT FIXTURE / ${client!!.options.language} / not a live battle", 12, 10, BattleUiTheme.MUTED, false)
            if (pages[page].startsWith("log")) {
                jbro.cobblemon.battleui.extended.ui.transcript.TranscriptPreview.render(context, pages[page])
                return
            }
            if (page in 1..3) {
                ChampionsBattleInfoOverlay.renderPreview(context, page)
                return
            }
            if (page == 4) {
                renderTooltipFixture(context)
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
            if (height >= 300) BattleDialogue.renderMessage(context, Text.literal(message))
        }

        private fun renderTooltipFixture(context: DrawContext) {
            val bounds = BattleMenuLayout.vertical(width, height, BattleMoveSelection.MOVE_WIDTH,
                BattleMoveSelection.MOVE_HEIGHT, 4, 12, 10, 4)
            val names = listOf("thunderbolt", "dazzlinggleam", "trick", "aromatherapy")
            val types = listOf("electric", "fairy", "psychic", "grass")
            val colors = listOf(0xFFF3D03E.toInt(), 0xFFEE99AC.toInt(), 0xFFF85888.toInt(), 0xFF78C850.toInt())
            MoveTooltipRenderer.clear()
            names.forEachIndexed { index, name ->
                val move = requireNotNull(Moves.getByName(name))
                val bound = bounds[index]
                BattleControlRenderer.drawMove(context, bound.x().toFloat(), bound.y().toFloat(),
                    move, ElementalTypes.get(types[index])!!, colors[index], 10, 10, true, index == 1)
                MoveTooltipRenderer.registerMoveTile(bound.x().toFloat(), bound.y().toFloat(),
                    bound.width(), bound.height(), move, 10, 10)
            }
            MoveTooltipRenderer.updateHoverState(bounds[1].x() + 30, bounds[1].y() + 10)
            MoveTooltipRenderer.renderTooltip(context)
        }
    }
}
