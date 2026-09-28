package jbro.cobblemon.battleui.extended.mixin;

import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleSwitchPokemonSelection;
import jbro.cobblemon.battleui.extended.navigation.KeyboardTileFocus;
import jbro.cobblemon.battleui.navigation.SmoothButtonScale;
import jbro.cobblemon.battleui.navigation.BattleScreenGeometry;
import net.minecraft.client.gui.DrawContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = BattleSwitchPokemonSelection.SwitchTile.class, remap = false)
public abstract class BattleSwitchTileScaleMixin {
    @Unique private final SmoothButtonScale cobblemonBattleUi$scale = new SmoothButtonScale(1.0, 1.06, 0.10);
    @Unique private double cobblemonBattleUi$currentScale = 1.0;
    @Unique private long cobblemonBattleUi$lastFrame = System.nanoTime();

    @Shadow public abstract float getX();
    @Shadow public abstract float getY();
    @Shadow public abstract boolean isHovered(double mouseX, double mouseY);
    @Shadow public abstract boolean isFainted();
    @Shadow public abstract boolean isCurrentlyInBattle();

    @Inject(method = "isHovered", at = @At("HEAD"), cancellable = true)
    private void cobblemonBattleUi$expandedHover(double mouseX, double mouseY,
            CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(mouseX >= getX() && mouseX < getX() + BattleScreenGeometry.SWITCH_WIDTH
                && mouseY >= getY() && mouseY < getY() + BattleScreenGeometry.SWITCH_HEIGHT);
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void cobblemonBattleUi$begin(DrawContext context, double mouseX, double mouseY, float delta, CallbackInfo ci) {
        long now = System.nanoTime();
        double elapsed = Math.min(0.05, (now - cobblemonBattleUi$lastFrame) / 1_000_000_000.0);
        cobblemonBattleUi$lastFrame = now;
        boolean selectable = !isFainted() && !isCurrentlyInBattle();
        boolean emphasized = selectable && (KeyboardTileFocus.allowsMouseHover()
                ? isHovered(mouseX, mouseY)
                : KeyboardTileFocus.isFocused(this));
        cobblemonBattleUi$currentScale = cobblemonBattleUi$scale.advance(cobblemonBattleUi$currentScale, emphasized, elapsed);
        float centerX = getX() + BattleSwitchPokemonSelection.SwitchTile.SELECT_WIDTH / 2.0f;
        float centerY = getY() + BattleSwitchPokemonSelection.SwitchTile.SELECT_HEIGHT / 2.0f;
        float scale = (float) cobblemonBattleUi$currentScale;
        context.getMatrices().push();
        context.getMatrices().translate(centerX, centerY, 0.0f);
        context.getMatrices().scale(scale, scale, 1.0f);
        context.getMatrices().translate(-centerX, -centerY, 0.0f);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void cobblemonBattleUi$end(DrawContext context, double mouseX, double mouseY, float delta, CallbackInfo ci) {
        context.getMatrices().pop();
    }
}
