package jbro.cobblemon.ui.extended

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.components.Button
import net.minecraft.network.chat.Component

/** Mod Menu entrypoint that remains loadable when the suggested Cloth Config mod is absent. */
class ModMenuIntegration : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> = ConfigScreenFactory { parent ->
        if (FabricLoader.getInstance().isModLoaded("cloth-config")) {
            ClothConfigScreenBuilder.create(parent)
        } else {
            MissingClothConfigScreen(parent)
        }
    }
}

private class MissingClothConfigScreen(private val parent: Screen) :
    Screen(Component.translatable("cobblemon_ui.config.title")) {

    override fun init() {
        addRenderableWidget(
            Button.builder(Component.translatable("gui.back")) { onClose() }
                .bounds(width / 2 - 100, height / 2 + 24, 200, 20)
                .build()
        )
    }

    override fun render(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        context.drawCenteredString(font, title, width / 2, height / 2 - 36, 0xFFFFFF)
        context.drawCenteredString(
            font,
            Component.translatable("cobblemon_ui.config.cloth_missing"),
            width / 2,
            height / 2 - 10,
            0xA0A0A0
        )
    }

    override fun onClose() {
        minecraft?.setScreen(parent)
    }
}
