package jbro.cobblemon.battleui.extended.mixin;

import com.cobblemon.mod.common.client.battle.SingleActionRequest;
import com.cobblemon.mod.common.client.gui.battle.BattleGUI;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleSwitchPokemonSelection;
import jbro.cobblemon.battleui.extended.ui.shared.BattleSwitchRenderer;
import jbro.cobblemon.battleui.navigation.BattleScreenGeometry;
import jbro.cobblemon.battleui.navigation.UiRect;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Places party visuals and Cobblemon's native click targets at exactly the same bounds. */
@Mixin(value = BattleSwitchPokemonSelection.class, remap = false)
public abstract class BattleSwitchSelectionMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void cobblemonBattleUi$placeInitialTiles(BattleGUI gui, SingleActionRequest request, CallbackInfo ci) {
        cobblemonBattleUi$placeTiles();
    }

    @Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true, remap = true)
    private void cobblemonBattleUi$drawParty(DrawContext context, int mouseX, int mouseY,
            float delta, CallbackInfo ci) {
        BattleSwitchPokemonSelection selection = (BattleSwitchPokemonSelection) (Object) this;
        cobblemonBattleUi$placeTiles();
        MinecraftClient client = MinecraftClient.getInstance();
        if (selection.getOpacity() > .05f) {
            BattleSwitchRenderer.drawSelection(context, selection,
                    client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight(), mouseX, mouseY);
        }
        ci.cancel();
    }

    @Inject(method = "mousePrimaryClicked", at = @At("HEAD"), cancellable = true)
    private void cobblemonBattleUi$partyClick(double mouseX, double mouseY,
            CallbackInfoReturnable<Boolean> cir) {
        BattleSwitchPokemonSelection selection = (BattleSwitchPokemonSelection) (Object) this;
        MinecraftClient client = MinecraftClient.getInstance();
        int width = client.getWindow().getScaledWidth();
        int height = client.getWindow().getScaledHeight();
        UiRect panel = BattleScreenGeometry.switchPanel(width, height);
        UiRect back = new UiRect(panel.x() + panel.width() - 104, panel.y(), 104, 17);
        if (!selection.getRequest().getForceSwitch() && back.contains(mouseX, mouseY)) {
            selection.getBattleGUI().changeActionSelection(null);
            selection.playDownSound(client.getSoundManager());
            cir.setReturnValue(true);
            return;
        }
        cobblemonBattleUi$placeTiles();
        for (BattleSwitchPokemonSelection.SwitchTile tile : selection.getTiles()) {
            if (tile.isHovered(mouseX, mouseY)) return; // Let Cobblemon enforce fainted/revive/active rules.
        }
        cir.setReturnValue(false); // No invisible native back button.
    }

    @Unique
    private void cobblemonBattleUi$placeTiles() {
        BattleSwitchPokemonSelection selection = (BattleSwitchPokemonSelection) (Object) this;
        MinecraftClient client = MinecraftClient.getInstance();
        List<UiRect> bounds = BattleScreenGeometry.switchTiles(client.getWindow().getScaledWidth(),
                client.getWindow().getScaledHeight(), selection.getTiles().size());
        for (int index = 0; index < bounds.size(); index++) {
            BattleSwitchTileAccessor tile = (BattleSwitchTileAccessor) (Object) selection.getTiles().get(index);
            tile.cobblemonBattleUi$setX(bounds.get(index).x());
            tile.cobblemonBattleUi$setY(bounds.get(index).y());
        }
    }
}
