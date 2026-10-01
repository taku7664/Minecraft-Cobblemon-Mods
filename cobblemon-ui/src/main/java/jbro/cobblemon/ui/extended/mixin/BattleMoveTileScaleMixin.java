package jbro.cobblemon.ui.extended.mixin;

import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection;
import jbro.cobblemon.ui.extended.navigation.KeyboardTileFocus;
import jbro.cobblemon.ui.extended.ui.shared.BattleControlRenderer;
import jbro.cobblemon.ui.navigation.BattleScreenGeometry;
import net.minecraft.client.gui.DrawContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = BattleMoveSelection.MoveTile.class, remap = false)
public abstract class BattleMoveTileScaleMixin {
    @Shadow public abstract float getX();
    @Shadow public abstract float getY();
    @Shadow public abstract boolean isHovered(double mouseX, double mouseY);
    @Shadow public abstract boolean getSelectable();

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void cobblemonBattleUi$begin(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        boolean emphasized = getSelectable() && (KeyboardTileFocus.allowsMouseHover()
                ? isHovered(mouseX, mouseY)
                : KeyboardTileFocus.isFocused(this));
        BattleControlRenderer.move(context, (BattleMoveSelection.MoveTile) (Object) this, emphasized);
        ci.cancel();
    }

    @Inject(method = "isHovered", at = @At("HEAD"), cancellable = true)
    private void cobblemonBattleUi$expandedHover(double mouseX, double mouseY,
            CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(mouseX >= getX() - BattleScreenGeometry.FOCUS_PROTRUSION
                && mouseX < getX() + BattleScreenGeometry.MOVE_WIDTH
                && mouseY >= getY() && mouseY < getY() + BattleScreenGeometry.MOVE_HEIGHT);
    }
}
