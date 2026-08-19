package jbro.cobblemon.morebattlecontent.internal.mixin.client;

import com.cobblemon.mod.common.battles.ShowdownMoveset;
import com.cobblemon.mod.common.client.battle.SingleActionRequest;
import com.cobblemon.mod.common.client.gui.battle.BattleGUI;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection;
import java.util.List;
import jbro.cobblemon.morebattlecontent.client.ManagedBattleMechanicVisibilityClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(BattleMoveSelection.class)
abstract class BattleMoveSelectionMixin {
    @Redirect(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lcom/cobblemon/mod/common/battles/ShowdownMoveset;getGimmicks()Ljava/util/List;"
        )
    )
    private List<ShowdownMoveset.Gimmick> mbc$hideDisabledManagedBattleGimmicks(
        ShowdownMoveset moveset,
        BattleGUI battleGUI,
        SingleActionRequest request
    ) {
        return ManagedBattleMechanicVisibilityClient.filterGimmicks(battleGUI, moveset.getGimmicks());
    }
}
