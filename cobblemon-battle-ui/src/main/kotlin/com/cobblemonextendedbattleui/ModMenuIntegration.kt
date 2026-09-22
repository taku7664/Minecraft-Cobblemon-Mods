package jbro.cobblemon.battleui.extended

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text

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
    Screen(Text.translatable("cobblemon_battle_ui.config.title")) {

    override fun init() {
        addDrawableChild(
            ButtonWidget.builder(Text.translatable("gui.back")) { close() }
                .dimensions(width / 2 - 100, height / 2 + 24, 200, 20)
                .build()
        )
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 36, 0xFFFFFF)
        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.translatable("cobblemon_battle_ui.config.cloth_missing"),
            width / 2,
            height / 2 - 10,
            0xA0A0A0
        )
    }

    override fun close() {
        client?.setScreen(parent)
    }
}
