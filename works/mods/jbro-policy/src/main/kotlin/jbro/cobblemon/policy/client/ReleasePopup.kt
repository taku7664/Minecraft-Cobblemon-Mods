package jbro.cobblemon.policy.client

import jbro.cobblemon.policy.pokemon.ReleaseAskPayload
import jbro.cobblemon.uikit.CobblemonUiSharedTheme
import jbro.cobblemon.uikit.UiDialogSpec
import jbro.cobblemon.uikit.UiOverlayTone
import jbro.cobblemon.uikit.client.CobblemonUiDialogScreen
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * `/release`'s question as the red confirm popup the hub uses for what cannot be undone, naming the slot, the Pokemon
 * and its level. Confirming sends the same `/release confirm <token>` the chat's [Yes] does; backing out sends nothing,
 * and the token simply runs out.
 */
internal object ReleasePopup {
    private const val KEY = "message.jbro_policy.release.popup."

    fun show(ask: ReleaseAskPayload) {
        val client = Minecraft.getInstance()
        client.setScreen(CobblemonUiDialogScreen(client.screen,
            UiDialogSpec(
                Component.translatable(KEY + "title"),
                Component.translatable(KEY + "body", ask.slot, ask.name, ask.level),
                Component.translatable(KEY + "confirm"),
                Component.translatable(KEY + "back"),
                UiOverlayTone.DANGER,
            ),
            confirm = { client.player?.connection?.sendCommand("release confirm ${ask.token}") },
            themeOverride = CobblemonUiSharedTheme.contentSnapshot()))
    }
}
