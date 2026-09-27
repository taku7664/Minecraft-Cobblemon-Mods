package jbro.cobblemon.battleui.transcript;

import jbro.cobblemon.battleui.extended.BattleLog;
import jbro.cobblemon.battleui.extended.BattleStateTracker;
import jbro.cobblemon.battleui.extended.ui.transcript.TranscriptSources;
import jbro.cobblemon.battleui.extended.ui.transcript.TranscriptSpeaker;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TranscriptSourcesTest {
    private TranscriptSpeaker speaker(String owner, String name, boolean left, boolean wild) {
        return new TranscriptSpeaker(UUID.randomUUID(), left, owner, Text.literal(name),
                Identifier.of("cobblemon", "eevee"), Set.of(), wild);
    }
    private Text move(String owner, String name) {
        return Text.translatable("cobblemon.battle.used_move",
                Text.translatable("cobblemon.battle.owned_pokemon", Text.literal(owner), Text.literal(name)), Text.literal("Tackle"));
    }
    @AfterEach void clear() { BattleLog.INSTANCE.clear(); BattleStateTracker.INSTANCE.clear(); }

    @Test void sameSpeciesOnBothSidesUsesStructuredOwnerNotSentence() {
        var self = speaker("빡대리", "이브이", true, false);
        var enemy = speaker("Opponent", "이브이", false, false);
        assertSame(self, TranscriptSources.INSTANCE.resolve(move("빡대리", "이브이"), List.of(self, enemy)));
        assertSame(enemy, TranscriptSources.INSTANCE.resolve(move("Opponent", "이브이"), List.of(self, enemy)));
    }
    @Test void wildNameMustBeUniqueAndMustNotMatchAnOwnedPokemon() {
        var owned = speaker("Player", "Eevee", true, false);
        var wild = speaker("", "Eevee", false, true);
        var text = Text.translatable("cobblemon.battle.used_move", Text.literal("Eevee"), Text.literal("Tackle"));
        assertSame(wild, TranscriptSources.INSTANCE.resolve(text, List.of(owned, wild)));
        assertNull(TranscriptSources.INSTANCE.resolve(text, List.of(owned)));
        assertNull(TranscriptSources.INSTANCE.resolve(text, List.of(wild, speaker("", "Eevee", false, true))));
    }
    @Test void ambiguousOwnedNicknameIsNotGuessed() {
        var candidates = List.of(speaker("Player", "Eevee", true, false), speaker("Player", "Eevee", true, false));
        assertNull(TranscriptSources.INSTANCE.resolve(move("Player", "Eevee"), candidates));
        assertNull(TranscriptSources.INSTANCE.resolve(Text.literal("Player's Eevee used Tackle!"), candidates));
    }
    @Test void siblingWrappedMessagesAreResolved() {
        var self = speaker("Player", "Eevee", true, false);
        assertSame(self, TranscriptSources.INSTANCE.resolve(Text.empty().append(move("Player", "Eevee")), List.of(self)));
    }
    @Test void previousTurnActionsDoNotBorrowTheAlreadyUpdatedTrackerTurn() {
        BattleLog.INSTANCE.processMessages(List.of(Text.translatable("cobblemon.battle.turn", 1)));
        BattleStateTracker.INSTANCE.setTurn(2);
        BattleLog.INSTANCE.processMessages(List.of(move("Player", "Eevee"), Text.translatable("cobblemon.battle.turn", 2)));
        assertEquals(List.of(1, 1, 2), BattleLog.INSTANCE.getEntries(null).stream().map(BattleLog.LogEntry::getTurn).toList());
    }
}
