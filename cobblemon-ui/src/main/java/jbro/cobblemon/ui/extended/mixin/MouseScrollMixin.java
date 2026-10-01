package jbro.cobblemon.ui.extended.mixin;

import jbro.cobblemon.ui.extended.BattleInfoPanel;
import jbro.cobblemon.ui.extended.MoveTooltipRenderer;
import jbro.cobblemon.ui.extended.PanelConfig;
import com.cobblemon.mod.common.client.CobblemonClient;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to intercept mouse scroll events for panel scaling.
 */
@Mixin(MouseHandler.class)
public abstract class MouseScrollMixin {

    @Shadow
    private double x;

    @Shadow
    private double y;

    @Inject(
        method = "onScroll",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        // Only intercept during battle
        if (CobblemonClient.INSTANCE.getBattle() != null) {
            if (jbro.cobblemon.ui.extended.ui.transcript.BattleTranscriptOverlay.INSTANCE.isOpen()) {
                jbro.cobblemon.ui.extended.ui.transcript.BattleTranscriptOverlay.INSTANCE.scrollBy((int) Math.round(-vertical * 24));
                ci.cancel();
                return;
            }
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
            // Try the info panel after move tooltips.
            if (PanelConfig.INSTANCE.getEnableBattleInfoPanelEffective() &&
                BattleInfoPanel.INSTANCE.onScroll(this.x, this.y, vertical)) {
                ci.cancel();
            }
        }
    }
}
