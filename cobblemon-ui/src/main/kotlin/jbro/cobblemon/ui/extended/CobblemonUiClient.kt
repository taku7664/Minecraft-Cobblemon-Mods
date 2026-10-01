package jbro.cobblemon.ui.extended

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import org.lwjgl.glfw.GLFW

object CobblemonUiClient : ClientModInitializer {

    lateinit var togglePanelKey: KeyBinding
        private set
    lateinit var toggleLogKey: KeyBinding
        private set
    lateinit var increaseFontKey: KeyBinding
        private set
    lateinit var decreaseFontKey: KeyBinding
        private set
    lateinit var selectActionKey: KeyBinding
        private set
    lateinit var cancelActionKey: KeyBinding
        private set

    override fun onInitializeClient() {
        CobblemonUi.LOGGER.info("Cobblemon UI client initializing...")

        BattleInfoPanel.initialize()
        registerKeybindings()
        registerHudRenderer()
        verifyBattleUiMixinTargets()
        jbro.cobblemon.ui.extended.ui.shared.BattleThemeCapture.install()
        BattleUiCaptureHarness.installFromEnvironment()

        CobblemonUi.LOGGER.info("Cobblemon UI client initialized!")
    }

    private fun verifyBattleUiMixinTargets() {
        val targets = listOf(
            "com.cobblemon.mod.common.client.gui.battle.BattleGUI",
            "com.cobblemon.mod.common.client.gui.battle.widgets.BattleOptionTile",
            "com.cobblemon.mod.common.client.gui.battle.subscreen.BattleGeneralActionSelection",
            "com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection\$MoveTile",
            "com.cobblemon.mod.common.client.gui.battle.subscreen.BattleSwitchPokemonSelection\$SwitchTile",
            "com.cobblemon.mod.common.client.gui.battle.subscreen.BattleTargetSelection\$TargetTile"
        )
        targets.forEach { Class.forName(it, true, javaClass.classLoader) }
        CobblemonUi.LOGGER.info("Verified {} battle UI mixin targets", targets.size)
    }

    private fun registerHudRenderer() {
        HudRenderCallback.EVENT.register { context, _ ->
            BattleInfoRenderer.render(context)
        }
    }

    private fun registerKeybindings() {
        toggleLogKey = KeyBindingHelper.registerKeyBinding(KeyBinding(
            "key.cobblemon_ui.toggle_log", InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT_SHIFT, "category.cobblemon_ui"))
        togglePanelKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.cobblemon_ui.toggle_panel",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_TAB,
                "category.cobblemon_ui"
            )
        )

        increaseFontKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.cobblemon_ui.increase_font",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_BRACKET,
                "category.cobblemon_ui"
            )
        )

        decreaseFontKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.cobblemon_ui.decrease_font",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_LEFT_BRACKET,
                "category.cobblemon_ui"
            )
        )

        selectActionKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.cobblemon_ui.select_action",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_Z,
                "category.cobblemon_ui"
            )
        )
        cancelActionKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.cobblemon_ui.cancel_action",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_X,
                "category.cobblemon_ui"
            )
        )
    }
}
