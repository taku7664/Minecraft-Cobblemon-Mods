package jbro.cobblemon.battleui.extended.mixin;

import com.cobblemon.mod.common.client.gui.battle.BattleGUI;
import jbro.cobblemon.battleui.extended.BattleDialogue;
import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Keyboard.class)
public class KeyboardBattleNavigationMixin {
    @Inject(method = "onKey", at = @At("HEAD"))
    private void cobblemonBattleUi$releaseDialogueConfirm(
            long window,
            int keyCode,
            int scanCode,
            int action,
            int modifiers,
            CallbackInfo ci
    ) {
        if (action == GLFW.GLFW_RELEASE && MinecraftClient.getInstance().currentScreen instanceof BattleGUI) {
            BattleDialogue.INSTANCE.releaseConfirm(keyCode, scanCode);
        }
    }
}
