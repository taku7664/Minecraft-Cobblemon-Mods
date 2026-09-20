package jbro.cobblemon.battleui.extended.mixin;

import com.cobblemon.mod.common.client.gui.battle.widgets.BattleOptionTile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = BattleOptionTile.class, remap = false)
public interface BattleOptionTileAccessor {
    @Mutable
    @Accessor("x")
    void cobblemonBattleUi$setX(int x);

    @Mutable
    @Accessor("y")
    void cobblemonBattleUi$setY(int y);
}
