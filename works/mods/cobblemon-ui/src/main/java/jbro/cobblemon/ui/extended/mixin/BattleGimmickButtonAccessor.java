package jbro.cobblemon.ui.extended.mixin;

import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleGimmickButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = BattleGimmickButton.class, remap = false)
public interface BattleGimmickButtonAccessor {
    @Accessor("texture")
    String cobblemonBattleUi$getTextureId();
}
