package jbro.cobblemon.policy.mixin;

import jbro.cobblemon.policy.plaza.PlazaProtection;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * An item is used on a block only after the block itself declined the click, so doors, PCs and healers still work.
 * What stops here is the item changing the world: placing blocks, tilling, bone meal, flint and steel, spawn eggs.
 * PASS, not FAIL: on FAIL the client gives up the click, so food could not be eaten while looking at a block.
 */
@Mixin(ItemStack.class)
abstract class ItemStackPlazaMixin {
    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
    private void jbroPolicy$keepPlazaBlocks(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (PlazaProtection.deniesWorldEdit(context.getPlayer(), context.getLevel())) cir.setReturnValue(InteractionResult.PASS);
    }
}
