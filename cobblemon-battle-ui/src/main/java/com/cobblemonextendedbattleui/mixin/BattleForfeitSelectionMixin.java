package jbro.cobblemon.battleui.extended.mixin;

import com.cobblemon.mod.common.client.gui.battle.subscreen.ForfeitConfirmationSelection;
import jbro.cobblemon.battleui.extended.navigation.ForfeitSelectionAccess;
import jbro.cobblemon.battleui.extended.navigation.KeyboardTileFocus;
import jbro.cobblemon.battleui.extended.ui.shared.BattleForfeitRenderer;
import jbro.cobblemon.battleui.navigation.BattleScreenGeometry;
import jbro.cobblemon.battleui.navigation.UiRect;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
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
    private void cobblemonBattleUi$drawConfirmation(DrawContext context, int mouseX, int mouseY,
            float delta, CallbackInfo ci) {
        ForfeitConfirmationSelection selection = (ForfeitConfirmationSelection) (Object) this;
        MinecraftClient client = MinecraftClient.getInstance();
        int width = client.getWindow().getScaledWidth();
        int height = client.getWindow().getScaledHeight();
        int focus = -1;
        if (KeyboardTileFocus.allowsMouseHover()) {
            if (BattleScreenGeometry.forfeitAccept(width, height).contains(mouseX, mouseY)) focus = 0;
            else if (BattleScreenGeometry.forfeitCancel(width, height).contains(mouseX, mouseY)) focus = 1;
        } else {
            focus = cobblemonBattleUi$focusedChoice;
        }
        BattleForfeitRenderer.draw(context, width, height,
                Text.translatable("cobblemon_battle_ui.forfeit.title"),
                Text.translatable("cobblemon_battle_ui.forfeit.detail"),
                Text.translatable("cobblemon_battle_ui.forfeit.accept"),
                Text.translatable("cobblemon_battle_ui.forfeit.back"),
                focus, selection.getOpacity());
        ci.cancel();
    }

    @Inject(method = "mousePrimaryClicked", at = @At("HEAD"), cancellable = true)
    private void cobblemonBattleUi$mapVisualClickToNative(double mouseX, double mouseY,
            CallbackInfoReturnable<Boolean> cir) {
        if (cobblemonBattleUi$remappingClick) return;
        ForfeitConfirmationSelection selection = (ForfeitConfirmationSelection) (Object) this;
        MinecraftClient client = MinecraftClient.getInstance();
        int width = client.getWindow().getScaledWidth();
        int height = client.getWindow().getScaledHeight();
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
