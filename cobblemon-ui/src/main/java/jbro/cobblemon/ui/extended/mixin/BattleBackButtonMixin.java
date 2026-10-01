package jbro.cobblemon.ui.extended.mixin;

import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleBackButton;
import jbro.cobblemon.ui.extended.ui.shared.BattleControlRenderer;
import jbro.cobblemon.ui.navigation.BattleScreenGeometry;
import net.minecraft.client.gui.DrawContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = BattleBackButton.class, remap = false)
public abstract class BattleBackButtonMixin {
    @Shadow public abstract float getX();
    @Shadow public abstract float getY();
    @Shadow public abstract boolean isHovered(double mouseX, double mouseY);

    @Inject(method = "isHovered", at = @At("HEAD"), cancellable = true)
    private void cobblemonBattleUi$expandedHover(double mouseX, double mouseY,
            CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(mouseX >= getX() - BattleScreenGeometry.FOCUS_PROTRUSION
                && mouseX < getX() + 29
                && mouseY >= getY() && mouseY < getY() + 17);
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void cobblemonBattleUi$renderBorderlessBack(
            DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        BattleControlRenderer.back(context, Math.round(getX()), Math.round(getY()),
                isHovered(mouseX, mouseY));
        ci.cancel();
    }
}
