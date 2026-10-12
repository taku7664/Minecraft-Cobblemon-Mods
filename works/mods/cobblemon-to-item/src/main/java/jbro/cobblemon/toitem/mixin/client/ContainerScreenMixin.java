package jbro.cobblemon.toitem.mixin.client;

import jbro.cobblemon.toitem.api.client.PokemonTooltip;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Container tooltips use GuiGraphics' list overload, bypassing its ItemStack overload. */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerScreenMixin<T extends AbstractContainerMenu> {
    @Shadow @Final protected T menu;
    @Shadow protected Slot hoveredSlot;

    @Inject(method = "renderTooltip", at = @At("HEAD"), cancellable = true)
    private void cobblemonToItem$containerTooltip(GuiGraphics graphics, int x, int y, CallbackInfo ci) {
        if (menu.getCarried().isEmpty() && hoveredSlot != null && hoveredSlot.hasItem()
                && PokemonTooltip.renderItemTooltip(graphics, hoveredSlot.getItem(), x, y)) ci.cancel();
    }
}
