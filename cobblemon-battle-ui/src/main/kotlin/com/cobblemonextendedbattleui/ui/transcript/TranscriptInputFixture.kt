package jbro.cobblemon.battleui.extended.ui.transcript

import com.cobblemon.mod.common.battles.BattleFormat
import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.client.battle.ClientBattle
import com.cobblemon.mod.common.client.gui.battle.BattleGUI
import jbro.cobblemon.battleui.extended.BattleDialogue
import jbro.cobblemon.battleui.extended.CobblemonExtendedBattleUI
import net.minecraft.text.Text
import org.lwjgl.glfw.GLFW
import java.util.UUID

/** Opt-in development fixture: exercises the transformed real BattleGUI key/click methods. */
internal object TranscriptInputFixture {
    fun verify() {
        check(CobblemonClient.battle == null) { "Input fixture must never replace a live battle" }
        val battle = ClientBattle(UUID(0, 1900), BattleFormat.GEN_9_SINGLES).also { it.minimised = false }
        CobblemonClient.battle = battle
        try {
            BattleTranscriptOverlay.clear()
            val gui = BattleGUI()
            check(gui.keyPressed(GLFW.GLFW_KEY_LEFT_SHIFT, 0, 0))
            check(BattleTranscriptOverlay.isOpen)
            gui.keyPressed(GLFW.GLFW_KEY_LEFT_SHIFT, 0, 0)
            check(BattleTranscriptOverlay.isOpen) { "Key repeat must not close the log" }
            BattleDialogue.enqueue(listOf(Text.literal("Fixture message")))
            check(gui.keyPressed(GLFW.GLFW_KEY_Z, 0, 0))
            check(BattleDialogue.hasPending()) { "Log must not acknowledge the dialogue behind it" }
            check(gui.mouseClicked(-1.0, -1.0, 0))
            check(gui.charTyped('r', 0))
            check(!battle.minimised) { "Character events must not minimize the battle behind the log" }
            check(gui.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0))
            check(!BattleTranscriptOverlay.isOpen)
            BattleTranscriptOverlay.releaseKey(GLFW.GLFW_KEY_LEFT_SHIFT, 0)
            gui.keyPressed(GLFW.GLFW_KEY_LEFT_SHIFT, 0, 0)
            check(BattleTranscriptOverlay.isOpen)
            BattleTranscriptOverlay.releaseKey(GLFW.GLFW_KEY_LEFT_SHIFT, 0)
            gui.keyPressed(GLFW.GLFW_KEY_LEFT_SHIFT, 0, 0)
            check(!BattleTranscriptOverlay.isOpen)
            check(battle.pendingActionRequests.isEmpty())
            CobblemonExtendedBattleUI.LOGGER.info("Transcript input fixture passed: Shift open/repeat/close, Esc, blocked Z/click/character, no battle requests")
        } finally {
            BattleDialogue.clear()
            BattleTranscriptOverlay.clear()
            CobblemonClient.battle = null
        }
    }
}
