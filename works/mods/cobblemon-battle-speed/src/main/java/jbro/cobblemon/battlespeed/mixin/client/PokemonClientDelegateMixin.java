package jbro.cobblemon.battlespeed.mixin.client;

import com.cobblemon.mod.common.client.entity.PokemonClientDelegate;
import com.cobblemon.mod.common.client.render.models.blockbench.PosableState;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import jbro.cobblemon.battlespeed.client.ClientBattleSpeedState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A Pokémon's model animations run on its age, which goes up by one each client tick. A battler in a sped-up battle
 * gets extra age ticks, so its attack, hit and faint motions play faster, and the particles, sounds and
 * animation-end checks tied to that clock keep pace. Partial ticks are stretched the same way so the motion stays
 * smooth between ticks; a fractional speed carries its remainder over to the next tick.
 */
@Mixin(value = PokemonClientDelegate.class, remap = false)
abstract class PokemonClientDelegateMixin {
    @Unique private float battleSpeed$carry;
    @Unique private float battleSpeed$speed = 1F;

    @Inject(method = "tick(Lcom/cobblemon/mod/common/entity/pokemon/PokemonEntity;)V", at = @At("TAIL"))
    private void battleSpeed$extraAge(PokemonEntity entity, CallbackInfo callback) {
        float speed = ClientBattleSpeedState.modelSpeedFor(entity.getBattleId());
        battleSpeed$speed = speed;
        if (speed <= 1F) {
            battleSpeed$carry = 0F;
            return;
        }
        float total = speed - 1F + battleSpeed$carry;
        int extra = (int) total;
        battleSpeed$carry = total - extra;
        var state = (PosableState) (Object) this;
        for (int i = 0; i < extra; i++) {
            state.incrementAge(entity);
        }
    }

    @ModifyVariable(method = "updatePartialTicks", at = @At("HEAD"), argsOnly = true)
    private float battleSpeed$stretchPartialTicks(float partialTicks) {
        return battleSpeed$speed > 1F ? partialTicks * battleSpeed$speed : partialTicks;
    }
}
