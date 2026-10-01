package jbro.cobblemon.ui.extended.mixin;

import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleActionSelection;
import jbro.cobblemon.ui.extended.BattleDialogue;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep the selection alive for battle state updates, but do not draw it over narration. */
@Mixin(AbstractWidget.class)
public abstract class BattleActionDialogueVisibilityMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void cobblemonBattleUi$hideActionSelectionDuringDialogue(
            GuiGraphics context, int mouseX, int mouseY, float delta, CallbackInfo ci
    ) {
        if ((Object) this instanceof BattleActionSelection && BattleDialogue.INSTANCE.hasPending()) {
            ci.cancel();
        }
    }
}
