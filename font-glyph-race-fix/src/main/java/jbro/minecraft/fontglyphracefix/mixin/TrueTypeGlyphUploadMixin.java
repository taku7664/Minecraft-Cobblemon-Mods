package jbro.minecraft.fontglyphracefix.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = "com.mojang.blaze3d.font.TrueTypeGlyphProvider$Glyph$1")
public abstract class TrueTypeGlyphUploadMixin {
    @WrapMethod(method = "upload")
    private void fontGlyphRaceFix$serializeUpload(int x, int y, Operation<Void> original) {
        FontGlyphLock.run(() -> original.call(x, y));
    }
}
