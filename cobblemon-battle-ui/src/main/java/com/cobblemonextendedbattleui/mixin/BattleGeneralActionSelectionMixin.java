package jbro.cobblemon.battleui.extended.mixin;

import com.cobblemon.mod.common.client.battle.SingleActionRequest;
import com.cobblemon.mod.common.client.gui.battle.BattleGUI;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleGeneralActionSelection;
import com.cobblemon.mod.common.client.gui.battle.widgets.BattleOptionTile;
import com.cobblemon.mod.common.battles.ForfeitActionResponse;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import kotlin.Unit;

import java.util.List;

@Mixin(value = BattleGeneralActionSelection.class, remap = false)
public abstract class BattleGeneralActionSelectionMixin {
    @Shadow
    public abstract List<BattleOptionTile> getTiles();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void cobblemonBattleUi$placeCommandsAfterConstruction(
            BattleGUI battleGUI,
            SingleActionRequest request,
            CallbackInfo ci
    ) {
        cobblemonBattleUi$placeCommandsVertically();
    }

    @Unique
    private void cobblemonBattleUi$placeCommandsVertically() {
        MinecraftClient client = MinecraftClient.getInstance();
        BattleCommandLayout.place(
                getTiles(),
                client.getWindow().getScaledWidth(),
                client.getWindow().getScaledHeight()
        );
    }

    @Inject(method = "lambda$2$1", at = @At("HEAD"), cancellable = true)
    private static void cobblemonBattleUi$finishWildBattleImmediately(
            BattleGUI battleGUI,
            SingleActionRequest request,
            BattleGeneralActionSelection selection,
            CallbackInfoReturnable<Unit> cir
    ) {
        battleGUI.selectAction(request, new ForfeitActionResponse());
        selection.playDownSound(MinecraftClient.getInstance().getSoundManager());
        cir.setReturnValue(Unit.INSTANCE);
    }
}
