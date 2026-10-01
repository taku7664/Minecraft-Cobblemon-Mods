package jbro.cobblemon.ui.extended.ui.shared

import com.cobblemon.mod.common.CobblemonSounds
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance

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
        Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(CobblemonSounds.GUI_CLICK, 1.0f))
    }
}
