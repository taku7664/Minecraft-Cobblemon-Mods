package jbro.cobblemon.ui.extended.mixin;

import com.cobblemon.mod.common.client.gui.battle.BattleGUI;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** The confirmation panel already states the consequence; the native banner crosses both HUDs. */
@Mixin(value = BattleGUI.class, remap = false)
public abstract class BattleGuiForfeitPromptMixin {
    @ModifyArg(method = "render", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lcom/cobblemon/mod/common/client/render/RenderHelperKt;drawScaledText$default(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/network/chat/MutableComponent;Ljava/lang/Number;Ljava/lang/Number;FLjava/lang/Number;IIZZLjava/lang/Integer;Ljava/lang/Integer;ILjava/lang/Object;)V", remap = false),
            index = 4, remap = true)
    private Number cobblemonBattleUi$moveRootHintAboveHud(Number original) {
        return 10;
    }

    @ModifyArg(method = "render", at = @At(value = "INVOKE", ordinal = 1,
            target = "Lcom/cobblemon/mod/common/client/render/RenderHelperKt;drawScaledText$default(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/network/chat/MutableComponent;Ljava/lang/Number;Ljava/lang/Number;FLjava/lang/Number;IIZZLjava/lang/Integer;Ljava/lang/Integer;ILjava/lang/Object;)V", remap = false),
            index = 2, remap = true)
    private MutableComponent cobblemonBattleUi$hideDuplicateForfeitPrompt(MutableComponent original) {
        return Component.empty();
    }
}
