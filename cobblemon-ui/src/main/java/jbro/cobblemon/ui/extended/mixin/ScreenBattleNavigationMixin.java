package jbro.cobblemon.ui.extended.mixin;

import com.cobblemon.mod.common.client.gui.battle.BattleGUI;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleActionSelection;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleGeneralActionSelection;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleSwitchPokemonSelection;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleTargetSelection;
import com.cobblemon.mod.common.client.gui.battle.subscreen.ForfeitConfirmationSelection;
import jbro.cobblemon.ui.extended.navigation.BattleGuiNavigationAccess;
import jbro.cobblemon.ui.extended.BattleInfoPanel;
import jbro.cobblemon.ui.extended.BattleDialogue;
import jbro.cobblemon.ui.extended.CobblemonUiClient;
import jbro.cobblemon.ui.extended.ui.shared.BattleUiSounds;
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
        if (jbro.cobblemon.ui.extended.ui.transcript.BattleTranscriptOverlay.INSTANCE.keyPressed(keyCode, scanCode)) {
            cir.setReturnValue(true);
            return;
        }
        if (BattleInfoPanel.INSTANCE.isExpanded()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE
                    || CobblemonUiClient.INSTANCE.getCancelActionKey().matchesKey(keyCode, scanCode)) {
                BattleInfoPanel.INSTANCE.toggle();
            } else {
                BattleInfoPanel.INSTANCE.handleKeyPressed(keyCode, scanCode);
            }
            cir.setReturnValue(true);
            return;
        }
        if (BattleDialogue.INSTANCE.hasPending()) {
            BattleDialogue.INSTANCE.confirm(keyCode, scanCode);
            cir.setReturnValue(true);
            return;
        }
        if (BattleDialogue.INSTANCE.confirm(keyCode, scanCode)) {
            cir.setReturnValue(true);
            return;
        }
        BattleGUI battleGUI = (BattleGUI) (Object) this;
        BattleActionSelection selection = battleGUI.getCurrentActionSelection();
        boolean cancel = keyCode == GLFW.GLFW_KEY_ESCAPE
                || CobblemonUiClient.INSTANCE.getCancelActionKey().matchesKey(keyCode, scanCode);
        if (cancel && selection != null) {
            // Backing out by keyboard clicks as Cobblemon's own back button does.
            if (selection instanceof ForfeitConfirmationSelection) {
                BattleUiSounds.click();
                battleGUI.changeActionSelection(null);
                cir.setReturnValue(true);
                return;
            }
            if (selection instanceof BattleSwitchPokemonSelection
                    && selection.getRequest().getForceSwitch()) {
                cir.setReturnValue(true);
                return;
            }
            if (selection instanceof BattleTargetSelection) {
                BattleUiSounds.click();
                battleGUI.changeActionSelection(new BattleMoveSelection(battleGUI, selection.getRequest()));
                cir.setReturnValue(true);
                return;
            }
            if (selection instanceof BattleMoveSelection || selection instanceof BattleSwitchPokemonSelection) {
                BattleUiSounds.click();
                battleGUI.changeActionSelection(null);
                cir.setReturnValue(true);
                return;
            }
            if (selection instanceof BattleGeneralActionSelection
                    && CobblemonUiClient.INSTANCE.getCancelActionKey().matchesKey(keyCode, scanCode)) {
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
