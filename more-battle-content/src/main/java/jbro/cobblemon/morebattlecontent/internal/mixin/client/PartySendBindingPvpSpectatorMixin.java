package jbro.cobblemon.morebattlecontent.internal.mixin.client;

import com.cobblemon.mod.common.client.keybind.keybinds.PartySendBinding;
import jbro.cobblemon.morebattlecontent.client.PvpLoungeSpectatorControls;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PartySendBinding.class)
abstract class PartySendBindingPvpSpectatorMixin {
    @Inject(method = "onRelease", at = @At("HEAD"))
    private void mbc$restoreDetailedSpectatorView(CallbackInfo callbackInfo) {
        PartySendBinding binding = (PartySendBinding) (Object) this;
        PvpLoungeSpectatorControls.restoreDetailedView(binding.getCanApplyChange());
    }
}
