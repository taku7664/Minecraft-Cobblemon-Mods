package jbro.cobblemon.battlespeed.mixin;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import jbro.cobblemon.battlespeed.core.SpeedScope;
import org.spongepowered.asm.mixin.Mixin;

/** Every dispatch of a battle runs inside its tick, so the waits it creates there belong to this battle. */
@Mixin(value = PokemonBattle.class, remap = false)
abstract class PokemonBattleMixin {
    @WrapMethod(method = "tick")
    private void battleSpeed$markBattle(Operation<Void> original) {
        SpeedScope.push(this, SpeedScope.Kind.BATTLE_TICK);
        try {
            original.call();
        } finally {
            SpeedScope.pop();
        }
    }
}
