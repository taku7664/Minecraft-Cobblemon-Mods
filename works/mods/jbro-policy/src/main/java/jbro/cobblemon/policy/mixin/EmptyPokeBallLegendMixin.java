package jbro.cobblemon.policy.mixin;

import com.cobblemon.mod.common.entity.pokeball.EmptyPokeBallEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import jbro.cobblemon.policy.legend.LegendPolicy;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Refuses a Legend's capture before Cobblemon looks at the battle. In a battle Cobblemon queues the capture, announces
 * the throw and passes the thrower's turn before {@code THROWN_POKEBALL_HIT} fires, so cancelling that event left the
 * battle waiting forever on a ball that no longer existed.
 */
@Mixin(EmptyPokeBallEntity.class)
abstract class EmptyPokeBallLegendMixin {
    @Invoker(value = "drop", remap = false)
    abstract void jbroPolicy$drop();

    @Inject(method = "onHitEntity", at = @At("HEAD"), cancellable = true)
    private void jbroPolicy$refuseLegend(EntityHitResult hitResult, CallbackInfo ci) {
        EmptyPokeBallEntity ball = (EmptyPokeBallEntity) (Object) this;
        if (ball.getCaptureState() != EmptyPokeBallEntity.CaptureState.NOT) return;
        if (ball.level().isClientSide() || !(hitResult.getEntity() instanceof PokemonEntity pokemon)) return;
        if (!LegendPolicy.refuseCapture(ball, pokemon)) return;
        jbroPolicy$drop();
        ci.cancel();
    }
}
