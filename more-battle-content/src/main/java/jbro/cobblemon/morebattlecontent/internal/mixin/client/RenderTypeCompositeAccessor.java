package jbro.cobblemon.morebattlecontent.internal.mixin.client;

import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(RenderType.CompositeRenderType.class)
public interface RenderTypeCompositeAccessor {
    @Invoker("state")
    RenderType.CompositeState mbcState();
}
