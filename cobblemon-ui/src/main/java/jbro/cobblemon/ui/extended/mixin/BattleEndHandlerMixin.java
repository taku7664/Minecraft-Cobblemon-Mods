package jbro.cobblemon.ui.extended.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.cobblemon.mod.common.client.net.battle.BattleEndHandler;
import com.cobblemon.mod.common.net.messages.client.battle.BattleEndPacket;
import jbro.cobblemon.ui.extended.BattleInfoPanel;
import jbro.cobblemon.ui.extended.BattleLog;
import jbro.cobblemon.ui.extended.BattleDialogue;
import jbro.cobblemon.ui.extended.BattleLogWidget;
import jbro.cobblemon.ui.extended.BattleStateTracker;
import jbro.cobblemon.ui.extended.DamageTracker;
import jbro.cobblemon.ui.extended.MoveTooltipRenderer;
import jbro.cobblemon.ui.extended.TeamIndicatorUI;
import net.minecraft.client.MinecraftClient;

/**
 * Mixin to clear tracking when battle ends.
 */
@Mixin(value = BattleEndHandler.class, remap = false)
public class BattleEndHandlerMixin {

    /**
     * Inject at the start of handle() to clear our trackers.
     */
    @Inject(method = "handle", at = @At("HEAD"))
    private void onHandle(BattleEndPacket packet, MinecraftClient client, CallbackInfo ci) {
        BattleStateTracker.INSTANCE.clear();
        TeamIndicatorUI.INSTANCE.clear();
        BattleInfoPanel.INSTANCE.clearBattleState();
        BattleLog.INSTANCE.clear();
        BattleDialogue.INSTANCE.clear();
        BattleLogWidget.INSTANCE.clear();
        DamageTracker.INSTANCE.clear();
        MoveTooltipRenderer.INSTANCE.clear();
    }
}
