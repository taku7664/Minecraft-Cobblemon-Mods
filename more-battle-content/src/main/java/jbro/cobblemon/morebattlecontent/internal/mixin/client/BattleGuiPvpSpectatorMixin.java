package jbro.cobblemon.morebattlecontent.internal.mixin.client;

import com.cobblemon.mod.common.client.gui.battle.BattleGUI;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleBackButton;
import jbro.cobblemon.morebattlecontent.client.PvpLoungeSpectatorControls;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(BattleGUI.class)
abstract class BattleGuiPvpSpectatorMixin {
    @Redirect(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lcom/cobblemon/mod/common/client/gui/battle/subscreen/BattleBackButton;render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V"
        )
    )
    private void mbc$hideNativeSpectatorBackButton(
        BattleBackButton button,
        GuiGraphics graphics,
        int mouseX,
        int mouseY,
        float partialTick
    ) {
        if (!PvpLoungeSpectatorControls.hidesNativeBackButton()) {
            button.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    @Redirect(
        method = "mouseClicked",
        at = @At(
            value = "INVOKE",
            target = "Lcom/cobblemon/mod/common/client/gui/battle/subscreen/BattleBackButton;isHovered(DD)Z"
        )
    )
    private boolean mbc$disableNativeSpectatorBackButton(BattleBackButton button, double mouseX, double mouseY) {
        return !PvpLoungeSpectatorControls.hidesNativeBackButton() && button.isHovered(mouseX, mouseY);
    }
}
