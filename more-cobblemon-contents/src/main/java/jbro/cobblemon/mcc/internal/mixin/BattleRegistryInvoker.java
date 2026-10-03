package jbro.cobblemon.mcc.internal.mixin;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.battles.BattleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Starts Showdown for a battle {@link jbro.cobblemon.mcc.internal.battle.BattleEntryHold} held. */
@Mixin(value = BattleRegistry.class, remap = false)
public interface BattleRegistryInvoker {
    @Invoker("startShowdown")
    void mcc$startShowdown(PokemonBattle battle);
}
