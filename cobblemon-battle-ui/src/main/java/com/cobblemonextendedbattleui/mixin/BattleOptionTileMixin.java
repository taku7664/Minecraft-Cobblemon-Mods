package jbro.cobblemon.battleui.extended.mixin;

import com.cobblemon.mod.common.client.gui.battle.widgets.BattleOptionTile;
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

@Mixin(value = BattleOptionTile.class, remap = false)
public abstract class BattleOptionTileMixin {
    @Unique
    private static final SmoothButtonScale COBBLEMON_BATTLE_UI_SCALE =
            new SmoothButtonScale(1.0, 1.06, 0.10);

    @Unique
    private double cobblemonBattleUi$currentScale = 1.0;

    @Unique
    private long cobblemonBattleUi$lastFrameNanos = System.nanoTime();

    @Shadow
    public abstract int getX();

    @Shadow
    public abstract int getY();

    @Shadow
    public abstract boolean isFocused();

    @Shadow
    public abstract boolean isHovered(double mouseX, double mouseY);

    @Inject(method = "render", at = @At("HEAD"), remap = true)
    private void cobblemonBattleUi$beginSmoothScale(
            DrawContext context,
            int mouseX,
            int mouseY,
            float delta,
            CallbackInfo ci
    ) {
        long now = System.nanoTime();
        double elapsedSeconds = Math.min(0.05, (now - cobblemonBattleUi$lastFrameNanos) / 1_000_000_000.0);
        cobblemonBattleUi$lastFrameNanos = now;
        boolean emphasized = KeyboardTileFocus.allowsMouseHover()
                ? isHovered(mouseX, mouseY)
                : isFocused();
        cobblemonBattleUi$currentScale = COBBLEMON_BATTLE_UI_SCALE.advance(
                cobblemonBattleUi$currentScale,
                emphasized,
                elapsedSeconds
        );

        float centerX = getX() + BattleOptionTile.OPTION_WIDTH / 2.0f;
        float centerY = getY() + BattleOptionTile.OPTION_HEIGHT / 2.0f;
        float scale = (float) cobblemonBattleUi$currentScale;
        context.getMatrices().push();
        context.getMatrices().translate(centerX, centerY, 0.0f);
        context.getMatrices().scale(scale, scale, 1.0f);
        context.getMatrices().translate(-centerX, -centerY, 0.0f);
    }

    @Inject(method = "render", at = @At("RETURN"), remap = true)
    private void cobblemonBattleUi$endSmoothScale(
            DrawContext context,
            int mouseX,
            int mouseY,
            float delta,
            CallbackInfo ci
    ) {
        FocusBorderRenderer.draw(
                context,
                getX(),
                getY(),
                BattleOptionTile.OPTION_WIDTH,
                BattleOptionTile.OPTION_HEIGHT,
                cobblemonBattleUi$currentScale
        );
        context.getMatrices().pop();
    }
}
