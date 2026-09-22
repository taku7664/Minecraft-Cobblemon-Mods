package jbro.cobblemon.battleui.extended;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.minecraft.text.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class BattleLogCategorizationTest {
    @AfterEach
    void clearLog() {
        BattleLog.INSTANCE.clear();
    }

    @Test
    void healingMessageVariantsUseTheHealingFilter() {
        assertCategory("cobblemon.battle.heal.item", BattleLog.EntryType.HEALING);
        assertCategory("cobblemon.battle.heal.leftovers", BattleLog.EntryType.HEALING);
    }

    @Test
    void hpAndSwitchVariantsUseTheHpFilter() {
        assertCategory("cobblemon.battle.sethp.painsplit", BattleLog.EntryType.HP);
        assertCategory("cobblemon.battle.switch.self", BattleLog.EntryType.HP);
        assertCategory("cobblemon.battle.withdraw.other", BattleLog.EntryType.HP);
        assertCategory("cobblemon.battle.dragged_out", BattleLog.EntryType.HP);
    }

    @Test
    void movePreparationVariantsUseTheMoveFilter() {
        assertCategory("cobblemon.battle.prepare.solarbeam", BattleLog.EntryType.MOVE);
        assertCategory("cobblemon.battle.cant.recharge", BattleLog.EntryType.MOVE);
    }

    @Test
    void battleEffectFamiliesUseTheEffectFilter() {
        assertCategory("cobblemon.battle.formechange.default.temporary", BattleLog.EntryType.EFFECT);
        assertCategory("cobblemon.battle.swapboost.guardswap", BattleLog.EntryType.EFFECT);
        assertCategory("cobblemon.battle.clearallnegativeboost.zeffect", BattleLog.EntryType.EFFECT);
        assertCategory("cobblemon.battle.singleturn.focuspunch", BattleLog.EntryType.EFFECT);
    }

    @Test
    void fieldActivationVariantsUseTheFieldFilter() {
        assertCategory("cobblemon.battle.fieldactivate.perishsong", BattleLog.EntryType.FIELD);
    }

    private static void assertCategory(String key, BattleLog.EntryType expected) {
        BattleLog.INSTANCE.clear();
        BattleLog.INSTANCE.processMessages(List.of(Text.translatable(key)));
        assertEquals(expected, BattleLog.INSTANCE.getEntries(null).getFirst().getType());
    }
}
