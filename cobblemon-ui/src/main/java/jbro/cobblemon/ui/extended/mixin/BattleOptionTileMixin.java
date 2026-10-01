package jbro.cobblemon.ui.extended.mixin;

import com.cobblemon.mod.common.client.gui.battle.widgets.BattleOptionTile;
import jbro.cobblemon.ui.extended.navigation.KeyboardTileFocus;
import jbro.cobblemon.ui.extended.ui.shared.BattleControlRenderer;
import jbro.cobblemon.ui.navigation.BattleScreenGeometry;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = BattleOptionTile.class, remap = false)
public abstract class BattleOptionTileMixin {
    @Shadow
    public abstract int getX();

    @Shadow
    public abstract int getY();

    @Shadow
    public abstract boolean isFocused();

    @Shadow
    public abstract boolean isHovered(double mouseX, double mouseY);

    @Inject(method = "isHovered", at = @At("HEAD"), cancellable = true)
    private void cobblemonBattleUi$expandedHover(double mouseX, double mouseY,
            CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(mouseX >= getX() - BattleScreenGeometry.FOCUS_PROTRUSION
                && mouseX < getX() + BattleOptionTile.OPTION_WIDTH
                && mouseY >= getY() && mouseY < getY() + BattleOptionTile.OPTION_HEIGHT);
    }

    @Inject(method = "render", at = @At("HEAD"), remap = true, cancellable = true)
    private void cobblemonBattleUi$renderStyledOption(
            GuiGraphics context,
            int mouseX,
            int mouseY,
            float delta,
            CallbackInfo ci
    ) {
        boolean emphasized = KeyboardTileFocus.allowsMouseHover()
                ? isHovered(mouseX, mouseY)
                : isFocused();
        BattleControlRenderer.option(context, (BattleOptionTile) (Object) this, emphasized);
        ci.cancel();
    }
}
