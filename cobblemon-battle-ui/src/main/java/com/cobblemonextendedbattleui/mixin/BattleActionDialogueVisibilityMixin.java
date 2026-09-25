package jbro.cobblemon.battleui.extended.mixin;

import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleActionSelection;
import jbro.cobblemon.battleui.extended.BattleDialogue;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep the selection alive for battle state updates, but do not draw it over narration. */
@Mixin(ClickableWidget.class)
public abstract class BattleActionDialogueVisibilityMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void cobblemonBattleUi$hideActionSelectionDuringDialogue(
            DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci
    ) {
        if ((Object) this instanceof BattleActionSelection && BattleDialogue.INSTANCE.hasPending()) {
            ci.cancel();
        }
    }
}
