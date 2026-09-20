package jbro.minecraft.fontglyphracefix.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.TrueTypeGlyphProvider;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(TrueTypeGlyphProvider.class)
public abstract class TrueTypeGlyphProviderMixin {
    @WrapMethod(method = "getGlyph")
    private GlyphInfo fontGlyphRaceFix$serializeMeasurement(int codePoint, Operation<GlyphInfo> original) {
        return FontGlyphLock.call(() -> original.call(codePoint));
    }
}
