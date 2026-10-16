package jbro.cobblemon.mcc.league.client

import jbro.cobblemon.mcc.league.MoreCobblemonContentsLeagueChallenge as Mod
import jbro.cobblemon.uikit.CobblemonUiSharedTheme
import jbro.cobblemon.uikit.UiDialogSpec
import jbro.cobblemon.uikit.UiOverlayTone
import jbro.cobblemon.uikit.client.CobblemonUiDialogScreen
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

internal object LeagueErrorPopup {
    private const val KEY = "screen.${Mod.MOD_ID}.error_popup."

    fun show(translationKey: String) {
        val client = Minecraft.getInstance()
        try {
            client.setScreen(CobblemonUiDialogScreen(
                client.screen,
                UiDialogSpec(
                    Component.translatable(KEY + "title"),
                    Component.translatable(translationKey),
                    Component.translatable(KEY + "confirm"),
                    tone = UiOverlayTone.DANGER,
                ),
                confirm = {},
                themeOverride = CobblemonUiSharedTheme.contentSnapshot(),
            ))
        } catch (failure: RuntimeException) {
            Mod.LOGGER.warn("League error popup could not open", failure)
            client.player?.displayClientMessage(Component.translatable(translationKey), false)
        } catch (failure: LinkageError) {
            Mod.LOGGER.warn("League error popup UI is unavailable", failure)
            client.player?.displayClientMessage(Component.translatable(translationKey), false)
        }
    }
}
