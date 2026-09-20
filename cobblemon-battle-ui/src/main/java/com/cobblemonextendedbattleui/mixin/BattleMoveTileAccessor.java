package jbro.cobblemon.battleui.extended.mixin;

import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = BattleMoveSelection.MoveTile.class, remap = false)
public interface BattleMoveTileAccessor {
    @Mutable
    @Accessor("x")
    void cobblemonBattleUi$setX(float x);

    @Mutable
    @Accessor("y")
    void cobblemonBattleUi$setY(float y);
}
