package jbro.cobblemon.popupemotes.client.custom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;

final class CustomEmoteDraftsTest {
    @Test
    void movingAcrossAPageBoundaryKeepsTheMovedEntrySelected() {
        var entries = new ArrayList<CustomEmoteDefinition>();
        for (int index = 0; index < 10; index++) {
            entries.add(new CustomEmoteDefinition("emote " + index, "https://example.com/" + index + ".gif"));
        }
        var drafts = new CustomEmoteDrafts(entries);

        int movedIndex = drafts.move(9, -1);

        assertEquals(8, movedIndex);
        assertEquals("emote 9", drafts.get(8).name());
        assertEquals("emote 8", drafts.get(9).name());
        assertEquals(0, drafts.pageFor(movedIndex));
    }

    @Test
    void removingTheLastEntryReturnsTheNearestRemainingSelection() {
        var drafts = new CustomEmoteDrafts(java.util.List.of(
            new CustomEmoteDefinition("one", "https://example.com/1.png"),
            new CustomEmoteDefinition("two", "https://example.com/2.png")
        ));

        assertEquals(0, drafts.remove(1));
        assertEquals(-1, drafts.remove(0));
    }

    @Test
    void refusesEntriesBeyondTheCatalogLimit() {
        var drafts = new CustomEmoteDrafts(java.util.List.of());
        for (int index = 0; index < CustomEmoteCatalog.MAX_EMOTES; index++) {
            drafts.add("emote " + index, "");
        }

        assertThrows(IllegalStateException.class, () -> drafts.add("overflow", ""));
    }

    @Test
    void swapsDraggedAndDroppedSlotsWithoutShiftingTheOthers() {
        var drafts = new CustomEmoteDrafts(java.util.List.of(
            new CustomEmoteDefinition("one", "https://example.com/1.png"),
            new CustomEmoteDefinition("two", "https://example.com/2.png"),
            new CustomEmoteDefinition("three", "https://example.com/3.png")
        ));

        drafts.swap(0, 2);

        assertEquals("three", drafts.get(0).name());
        assertEquals("two", drafts.get(1).name());
        assertEquals("one", drafts.get(2).name());
    }

    @Test
    void snapshotDetectsUnfinishedEditsAndOrderingChanges() {
        var drafts = new CustomEmoteDrafts(java.util.List.of(
            new CustomEmoteDefinition("one", "https://example.com/1.png"),
            new CustomEmoteDefinition("two", "https://example.com/2.png")
        ));
        var initial = drafts.snapshot();

        assertEquals(initial, drafts.snapshot());
        drafts.set(0, "renamed", "");
        assertNotEquals(initial, drafts.snapshot());
        drafts.set(0, "one", "https://example.com/1.png");
        assertEquals(initial, drafts.snapshot());
        drafts.swap(0, 1);
        assertNotEquals(initial, drafts.snapshot());
        assertThrows(UnsupportedOperationException.class, () -> initial.add(drafts.get(0)));
    }
}
