package jbro.cobblemon.partysidebar.mixin.client;

import jbro.cobblemon.partysidebar.client.PresetClient;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Storage items wear a small chest in their corner wherever they are drawn as a slot item. */
@Mixin(GuiGraphics.class)
public abstract class GuiGraphicsMixin {
    @Inject(method = "renderItemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V", at = @At("TAIL"))
    private void cobblemonPartySidebar$storageBadge(Font font, ItemStack stack, int x, int y, String text, CallbackInfo ci) {
        PresetClient.renderBadge((GuiGraphics) (Object) this, stack, x, y);
    }
}
