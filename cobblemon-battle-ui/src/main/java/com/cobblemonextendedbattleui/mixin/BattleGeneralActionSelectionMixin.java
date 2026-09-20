package jbro.cobblemon.battleui.extended.mixin;

import com.cobblemon.mod.common.client.battle.SingleActionRequest;
import com.cobblemon.mod.common.client.gui.battle.BattleGUI;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleGeneralActionSelection;
import com.cobblemon.mod.common.client.gui.battle.widgets.BattleOptionTile;
import com.cobblemon.mod.common.battles.ForfeitActionResponse;
import jbro.cobblemon.battleui.navigation.BattleMenuLayout;
import jbro.cobblemon.battleui.navigation.UiRect;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import kotlin.Unit;

import java.util.List;

@Mixin(value = BattleGeneralActionSelection.class, remap = false)
public abstract class BattleGeneralActionSelectionMixin {
    private static final int BUTTON_GAP = 4;
    private static final int RIGHT_MARGIN = 12;
    private static final int BOTTOM_MARGIN = 10;

    @Shadow
    public abstract List<BattleOptionTile> getTiles();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void cobblemonBattleUi$placeCommandsVertically(
            BattleGUI battleGUI,
            SingleActionRequest request,
            CallbackInfo ci
    ) {
        List<BattleOptionTile> tiles = getTiles();
        MinecraftClient client = MinecraftClient.getInstance();
        List<UiRect> bounds = BattleMenuLayout.vertical(
                client.getWindow().getScaledWidth(),
                client.getWindow().getScaledHeight(),
                BattleOptionTile.OPTION_WIDTH,
                BattleOptionTile.OPTION_HEIGHT,
                BUTTON_GAP,
                RIGHT_MARGIN,
                BOTTOM_MARGIN,
                tiles.size()
        );

        for (int index = 0; index < tiles.size(); index++) {
            BattleOptionTileAccessor tile = (BattleOptionTileAccessor) (Object) tiles.get(index);
            UiRect bound = bounds.get(index);
            tile.cobblemonBattleUi$setX(bound.x());
            tile.cobblemonBattleUi$setY(bound.y());
        }
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
