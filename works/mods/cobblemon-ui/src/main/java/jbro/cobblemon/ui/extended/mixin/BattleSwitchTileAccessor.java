package jbro.cobblemon.ui.extended.mixin;

import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleSwitchPokemonSelection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = BattleSwitchPokemonSelection.SwitchTile.class, remap = false)
public interface BattleSwitchTileAccessor {
    @Mutable
    @Accessor("x")
    void cobblemonBattleUi$setX(float x);

    @Mutable
    @Accessor("y")
    void cobblemonBattleUi$setY(float y);
}
