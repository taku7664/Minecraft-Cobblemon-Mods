package jbro.cobblemon.battleui.dialogue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import jbro.cobblemon.battleui.extended.BattleDialogue;
import net.minecraft.text.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class BattleDialoguePresentationTest {
    @AfterEach
    void clearQueue() {
        BattleDialogue.INSTANCE.clear();
    }

    @Test
    void turnAnnouncementIsNotQueuedButOtherBattleMessagesAre() {
        BattleDialogue.INSTANCE.enqueue(List.of(Text.translatable("cobblemon.battle.turn", 3)));
        assertFalse(BattleDialogue.INSTANCE.hasPending());

        BattleDialogue.INSTANCE.enqueue(List.of(
            Text.translatable("cobblemon.battle.turn", 4),
            Text.translatable("cobblemon.battle.used_move", "Pokemon", "Tackle")
        ));
        assertTrue(BattleDialogue.INSTANCE.hasPending());
    }
}
