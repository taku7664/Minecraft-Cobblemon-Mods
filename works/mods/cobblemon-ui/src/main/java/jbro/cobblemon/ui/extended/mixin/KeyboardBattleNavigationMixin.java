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
        if (action == GLFW.GLFW_RELEASE && Minecraft.getInstance().screen instanceof BattleGUI) {
            BattleDialogue.INSTANCE.releaseConfirm(keyCode, scanCode);
        }
        if (action == GLFW.GLFW_RELEASE) {
            jbro.cobblemon.ui.extended.ui.transcript.BattleTranscriptOverlay.INSTANCE.releaseKey(keyCode, scanCode);
        }
    }
}
