package jbro.cobblemon.policy.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import jbro.cobblemon.policy.chat.HardChampionGradient;
import net.minecraft.Util;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Letters drawn in the hard Champion font take their colour from a moving red gradient instead of their style. The
 * font only renames the default glyphs, so every other text is untouched.
 */
@Mixin(targets = "net.minecraft.client.gui.Font$StringRenderOutput")
abstract class FontHardChampionGradientMixin {
    @ModifyExpressionValue(method = "accept", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/chat/Style;getColor()Lnet/minecraft/network/chat/TextColor;"))
    private TextColor jbroPolicy$hardChampionGradient(TextColor original,
                                                       @Local(argsOnly = true, ordinal = 0) int index,
                                                       @Local(argsOnly = true) Style style) {
        if (!HardChampionGradient.FONT.equals(style.getFont())) return original;
        return TextColor.fromRgb(HardChampionGradient.color(index, Util.getMillis()));
    }
}
