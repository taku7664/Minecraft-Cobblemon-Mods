package jbro.cobblemon.battleui.extended.mixin;

import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleTargetSelection;
import jbro.cobblemon.battleui.extended.navigation.KeyboardTileFocus;
import jbro.cobblemon.battleui.navigation.FocusBorderRenderer;
import jbro.cobblemon.battleui.navigation.SmoothButtonScale;
import net.minecraft.client.gui.DrawContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BattleTargetSelection.TargetTile.class, remap = false)
public abstract class BattleTargetTileScaleMixin {
    @Unique private final SmoothButtonScale cobblemonBattleUi$scale = new SmoothButtonScale(1.0, 1.06, 0.10);
    @Unique private double cobblemonBattleUi$currentScale = 1.0;
    @Unique private long cobblemonBattleUi$lastFrame = System.nanoTime();

    @Shadow public abstract float getX();
    @Shadow public abstract float getY();
    @Shadow public abstract boolean isHovered(double mouseX, double mouseY);
    @Shadow public abstract boolean getSelectable();

    @Inject(method = "render", at = @At("HEAD"))
    private void cobblemonBattleUi$begin(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        long now = System.nanoTime();
        double elapsed = Math.min(0.05, (now - cobblemonBattleUi$lastFrame) / 1_000_000_000.0);
        cobblemonBattleUi$lastFrame = now;
        boolean emphasized = getSelectable() && (KeyboardTileFocus.allowsMouseHover()
                ? isHovered(mouseX, mouseY)
                : KeyboardTileFocus.isFocused(this));
        cobblemonBattleUi$currentScale = cobblemonBattleUi$scale.advance(cobblemonBattleUi$currentScale, emphasized, elapsed);
        float centerX = getX() + BattleTargetSelection.TARGET_WIDTH / 2.0f;
        float centerY = getY() + BattleTargetSelection.TARGET_HEIGHT / 2.0f;
        float scale = (float) cobblemonBattleUi$currentScale;
        context.getMatrices().push();
        context.getMatrices().translate(centerX, centerY, 0.0f);
        context.getMatrices().scale(scale, scale, 1.0f);
        context.getMatrices().translate(-centerX, -centerY, 0.0f);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void cobblemonBattleUi$end(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        FocusBorderRenderer.draw(
                context,
                getX(),
                getY(),
                BattleTargetSelection.TARGET_WIDTH,
                BattleTargetSelection.TARGET_HEIGHT,
                cobblemonBattleUi$currentScale
        );
        context.getMatrices().pop();
    }
}
