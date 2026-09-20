package jbro.cobblemon.battleui.extended.mixin;

import jbro.cobblemon.battleui.extended.BattleInfoPanel;
import jbro.cobblemon.battleui.extended.BattleLogWidget;
import jbro.cobblemon.battleui.extended.MoveTooltipRenderer;
import jbro.cobblemon.battleui.extended.PanelConfig;
import com.cobblemon.mod.common.client.CobblemonClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to intercept mouse scroll events for panel scaling.
 */
@Mixin(Mouse.class)
public abstract class MouseScrollMixin {

    @Shadow
    private double x;

    @Shadow
    private double y;

    @Inject(
        method = "onMouseScroll",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        // Only intercept during battle
        if (CobblemonClient.INSTANCE.getBattle() != null) {
            if (BattleInfoPanel.INSTANCE.isExpanded()) {
                MoveTooltipRenderer.INSTANCE.suspendForModal();
                ci.cancel();
                return;
            }

            // Let the move tooltip try to handle the scroll first (highest priority when in Fight menu)
            if (PanelConfig.INSTANCE.getEnableMoveTooltipsEffective() &&
                MoveTooltipRenderer.INSTANCE.handleScroll(vertical)) {
                ci.cancel();
                return;
            }
            // Then let the battle log widget try
            if (PanelConfig.INSTANCE.getEnableBattleLogEffective() &&
                BattleLogWidget.INSTANCE.onScroll(this.x, this.y, vertical)) {
                ci.cancel();
                return;
            }
            // Finally try the info panel
            if (PanelConfig.INSTANCE.getEnableBattleInfoPanelEffective() &&
                BattleInfoPanel.INSTANCE.onScroll(this.x, this.y, vertical)) {
                ci.cancel();
            }
        }
    }
}
