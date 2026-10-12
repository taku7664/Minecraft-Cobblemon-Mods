package jbro.cobblemon.toitem.mixin.client;

import jbro.cobblemon.toitem.api.client.PokemonTooltip;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiGraphics.class)
public abstract class GuiGraphicsMixin {
    @Inject(method = "renderTooltip(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;II)V",
            at = @At("HEAD"), cancellable = true)
    private void cobblemonToItem$tooltip(Font font, ItemStack stack, int x, int y, CallbackInfo ci) {
        if (PokemonTooltip.renderItemTooltip((GuiGraphics) (Object) this, stack, x, y)) ci.cancel();
    }
}
