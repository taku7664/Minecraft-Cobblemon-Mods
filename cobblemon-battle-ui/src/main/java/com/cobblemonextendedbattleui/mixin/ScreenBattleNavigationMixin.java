package jbro.cobblemon.battleui.extended.mixin;

import com.cobblemon.mod.common.client.gui.battle.BattleGUI;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleActionSelection;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleSwitchPokemonSelection;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleTargetSelection;
import jbro.cobblemon.battleui.extended.navigation.BattleGuiNavigationAccess;
import jbro.cobblemon.battleui.extended.BattleInfoPanel;
import net.minecraft.client.gui.screen.Screen;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Screen.class)
public abstract class ScreenBattleNavigationMixin {
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void cobblemonBattleUi$forwardBattleNavigation(
            int keyCode,
            int scanCode,
            int modifiers,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (!((Object) this instanceof BattleGUI)) {
            return;
        }
        if (BattleInfoPanel.INSTANCE.isExpanded()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                BattleInfoPanel.INSTANCE.toggle();
            } else {
                BattleInfoPanel.INSTANCE.handleKeyPressed(keyCode, scanCode);
            }
            cir.setReturnValue(true);
            return;
        }
        BattleGUI battleGUI = (BattleGUI) (Object) this;
        BattleActionSelection selection = battleGUI.getCurrentActionSelection();
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && selection != null) {
            if (selection instanceof BattleSwitchPokemonSelection
                    && selection.getRequest().getForceSwitch()) {
                cir.setReturnValue(true);
                return;
            }
            if (selection instanceof BattleTargetSelection) {
                battleGUI.changeActionSelection(new BattleMoveSelection(battleGUI, selection.getRequest()));
                cir.setReturnValue(true);
                return;
            }
            if (selection instanceof BattleMoveSelection || selection instanceof BattleSwitchPokemonSelection) {
                battleGUI.changeActionSelection(null);
                cir.setReturnValue(true);
                return;
            }
        }
        if ((Object) this instanceof BattleGuiNavigationAccess navigation
                && navigation.cobblemonBattleUi$handleNavigationKey(keyCode, scanCode, modifiers)) {
            cir.setReturnValue(true);
        }
    }
}
