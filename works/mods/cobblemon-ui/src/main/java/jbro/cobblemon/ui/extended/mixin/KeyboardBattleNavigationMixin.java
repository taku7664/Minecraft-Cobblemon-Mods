package jbro.cobblemon.ui.extended.mixin;

import com.cobblemon.mod.common.client.gui.battle.BattleGUI;
import jbro.cobblemon.ui.extended.BattleDialogue;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public class KeyboardBattleNavigationMixin {
    @Inject(method = "keyPress", at = @At("HEAD"))
    private void cobblemonBattleUi$releaseDialogueConfirm(
            long window,
            int keyCode,
            int scanCode,
            int action,
            int modifiers,
            CallbackInfo ci
    ) {
        boolean confirm = jbro.cobblemon.ui.extended.CobblemonUiClient.INSTANCE.getSelectActionKey().matches(keyCode, scanCode);
        if (action == GLFW.GLFW_PRESS && confirm) {
            // Remember whether this press was made for the menu, before the screen sees it.
            boolean menuShown = Minecraft.getInstance().screen instanceof BattleGUI gui
                    && gui.getCurrentActionSelection() != null && !BattleDialogue.INSTANCE.hasPending()
                    && !jbro.cobblemon.ui.extended.SceneDialogue.isActive();
            jbro.cobblemon.ui.extended.BattleConfirmGate.pressed(menuShown);
        }
        if (action == GLFW.GLFW_RELEASE && confirm) {
            jbro.cobblemon.ui.extended.BattleConfirmGate.released();
        }
        if (action == GLFW.GLFW_RELEASE && Minecraft.getInstance().screen instanceof BattleGUI) {
            BattleDialogue.INSTANCE.releaseConfirm(keyCode, scanCode);
        }
        if (action == GLFW.GLFW_RELEASE) {
            jbro.cobblemon.ui.extended.ui.transcript.BattleTranscriptOverlay.INSTANCE.releaseKey(keyCode, scanCode);
        }
    }
}
