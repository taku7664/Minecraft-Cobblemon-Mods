package jbro.cobblemon.ui.extended.mixin;

import com.cobblemon.mod.common.client.battle.SingleActionRequest;
import com.cobblemon.mod.common.client.gui.battle.BattleGUI;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleSwitchPokemonSelection;
import jbro.cobblemon.ui.extended.ui.shared.BattleSwitchRenderer;
import jbro.cobblemon.ui.navigation.BattleScreenGeometry;
import jbro.cobblemon.ui.navigation.UiRect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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
    private void cobblemonBattleUi$drawParty(GuiGraphics context, int mouseX, int mouseY,
            float delta, CallbackInfo ci) {
        BattleSwitchPokemonSelection selection = (BattleSwitchPokemonSelection) (Object) this;
        cobblemonBattleUi$placeTiles();
        Minecraft client = Minecraft.getInstance();
        if (selection.getOpacity() > .05f) {
            BattleSwitchRenderer.drawSelection(context, selection,
                    client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight(), mouseX, mouseY);
        }
        ci.cancel();
    }

    @Inject(method = "mousePrimaryClicked", at = @At("HEAD"), cancellable = true)
    private void cobblemonBattleUi$partyClick(double mouseX, double mouseY,
            CallbackInfoReturnable<Boolean> cir) {
        BattleSwitchPokemonSelection selection = (BattleSwitchPokemonSelection) (Object) this;
        Minecraft client = Minecraft.getInstance();
        int width = client.getWindow().getGuiScaledWidth();
        int height = client.getWindow().getGuiScaledHeight();
        UiRect back = BattleScreenGeometry.switchBack(width, height);
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
        Minecraft client = Minecraft.getInstance();
        List<UiRect> bounds = BattleScreenGeometry.switchTiles(client.getWindow().getGuiScaledWidth(),
                client.getWindow().getGuiScaledHeight(), selection.getTiles().size());
        for (int index = 0; index < bounds.size(); index++) {
            BattleSwitchTileAccessor tile = (BattleSwitchTileAccessor) (Object) selection.getTiles().get(index);
            tile.cobblemonBattleUi$setX(bounds.get(index).x());
            tile.cobblemonBattleUi$setY(bounds.get(index).y());
        }
    }
}
