package jbro.cobblemon.battleui.extended.ui.shared

import com.cobblemon.mod.common.CobblemonSounds
import net.minecraft.client.MinecraftClient
import net.minecraft.client.sound.PositionedSoundInstance

/**
 * Cobblemon's own battle menu click, for the inputs this mod handles itself.
 *
 * Cobblemon's selection screens play it when their controls are pressed. The message box, the battle log, the
 * information panel and the keyboard's back action take their input before Cobblemon sees it, so without this they
 * worked in silence. Only an input that does something clicks; one that is swallowed stays quiet.
 */
object BattleUiSounds {
    @JvmStatic
    fun click() {
        MinecraftClient.getInstance().soundManager.play(PositionedSoundInstance.master(CobblemonSounds.GUI_CLICK, 1.0f))
    }
}
