package jbro.cobblemon.battleui.extended

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import org.lwjgl.glfw.GLFW

object CobblemonExtendedBattleUIClient : ClientModInitializer {

    lateinit var togglePanelKey: KeyBinding
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
        CobblemonExtendedBattleUI.LOGGER.info("Cobblemon: Battle UI client initializing...")

        BattleInfoPanel.initialize()
        registerKeybindings()
        registerHudRenderer()
        verifyBattleUiMixinTargets()

        CobblemonExtendedBattleUI.LOGGER.info("Cobblemon: Battle UI client initialized!")
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
        CobblemonExtendedBattleUI.LOGGER.info("Verified {} battle UI mixin targets", targets.size)
    }

    private fun registerHudRenderer() {
        HudRenderCallback.EVENT.register { context, _ ->
            BattleInfoRenderer.render(context)
        }
    }

    private fun registerKeybindings() {
        togglePanelKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.cobblemon_battle_ui.toggle_panel",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_TAB,
                "category.cobblemon_battle_ui"
            )
        )

        increaseFontKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.cobblemon_battle_ui.increase_font",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_BRACKET,
                "category.cobblemon_battle_ui"
            )
        )

        decreaseFontKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.cobblemon_battle_ui.decrease_font",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_LEFT_BRACKET,
                "category.cobblemon_battle_ui"
            )
        )

        selectActionKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.cobblemon_battle_ui.select_action",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_Z,
                "category.cobblemon_battle_ui"
            )
        )
        cancelActionKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.cobblemon_battle_ui.cancel_action",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_X,
                "category.cobblemon_battle_ui"
            )
        )
    }
}
