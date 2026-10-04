package jbro.cobblemon.ui.extended.mixin;

import com.cobblemon.mod.common.client.gui.battle.subscreen.ForfeitConfirmationSelection;
import jbro.cobblemon.ui.extended.navigation.ForfeitSelectionAccess;
import jbro.cobblemon.ui.extended.navigation.KeyboardTileFocus;
import jbro.cobblemon.ui.extended.ui.shared.BattleForfeitRenderer;
import jbro.cobblemon.ui.navigation.BattleScreenGeometry;
import jbro.cobblemon.ui.navigation.UiRect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps Cobblemon's original forfeit response handler while moving its visual hit targets. */
@Mixin(value = ForfeitConfirmationSelection.class, remap = false)
public abstract class BattleForfeitSelectionMixin implements ForfeitSelectionAccess {
    @Unique private boolean cobblemonBattleUi$remappingClick;
    @Unique private int cobblemonBattleUi$focusedChoice = 1; // Back is the safe default.

    @Override
    public void cobblemonBattleUi$setFocusedChoice(int choice) {
        if (choice == 0 || choice == 1) {
            cobblemonBattleUi$focusedChoice = choice;
        }
    }

    @Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true, remap = true)
    private void cobblemonBattleUi$drawConfirmation(GuiGraphics context, int mouseX, int mouseY,
            float delta, CallbackInfo ci) {
        ForfeitConfirmationSelection selection = (ForfeitConfirmationSelection) (Object) this;
        Minecraft client = Minecraft.getInstance();
        int width = client.getWindow().getGuiScaledWidth();
        int height = client.getWindow().getGuiScaledHeight();
        int focus = -1;
        if (KeyboardTileFocus.allowsMouseHover()) {
            if (BattleScreenGeometry.forfeitAccept(width, height).contains(mouseX, mouseY)) focus = 0;
            else if (BattleScreenGeometry.forfeitCancel(width, height).contains(mouseX, mouseY)) focus = 1;
        } else {
            focus = cobblemonBattleUi$focusedChoice;
        }
        BattleForfeitRenderer.draw(context, width, height,
                Component.translatable("cobblemon_ui.forfeit.title"),
                Component.translatable("cobblemon_ui.forfeit.detail"),
                Component.translatable("cobblemon_ui.forfeit.accept"),
                Component.translatable("cobblemon_ui.forfeit.back"),
                focus, selection.getOpacity());
        ci.cancel();
    }

    @Inject(method = "mousePrimaryClicked", at = @At("HEAD"), cancellable = true)
    private void cobblemonBattleUi$mapVisualClickToNative(double mouseX, double mouseY,
            CallbackInfoReturnable<Boolean> cir) {
        if (cobblemonBattleUi$remappingClick) return;
        ForfeitConfirmationSelection selection = (ForfeitConfirmationSelection) (Object) this;
        Minecraft client = Minecraft.getInstance();
        int width = client.getWindow().getGuiScaledWidth();
        int height = client.getWindow().getGuiScaledHeight();
        UiRect accept = BattleScreenGeometry.forfeitAccept(width, height);
        UiRect cancel = BattleScreenGeometry.forfeitCancel(width, height);
        boolean chooseAccept = accept.contains(mouseX, mouseY);
        if (!chooseAccept && !cancel.contains(mouseX, mouseY)) {
            cir.setReturnValue(false); // Ignore the hidden native button bounds.
            return;
        }
        var nativeButton = chooseAccept ? selection.getAcceptButton() : selection.getDeclineButton();
        cobblemonBattleUi$remappingClick = true;
        try {
            cir.setReturnValue(selection.mousePrimaryClicked(nativeButton.getX() + 1, nativeButton.getY() + 1));
        } finally {
            cobblemonBattleUi$remappingClick = false;
        }
    }
}
