package jbro.cobblemon.battleui.extended.state;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class ActiveSlotTrackerTest {
    @Test
    void identifiesTheExactSlotThatWasReplaced() {
        ActiveSlotTracker<String> tracker = new ActiveSlotTracker<>();
        tracker.update(Map.of("p1a", "lead", "p1b", "partner"));

        var changes = tracker.update(Map.of("p1a", "lead", "p1b", "receiver"));

        assertEquals(1, changes.size());
        assertEquals("p1b", changes.getFirst().slot());
        assertEquals("partner", changes.getFirst().outgoing());
        assertEquals("receiver", changes.getFirst().incoming());
    }

    @Test
    void preservesTheSlotAcrossSeparateRemovalAndArrivalFrames() {
        ActiveSlotTracker<String> tracker = new ActiveSlotTracker<>();
        tracker.update(Map.of("p1a", "lead"));

        var removal = tracker.update(Map.of());
        var arrival = tracker.update(Map.of("p1a", "receiver"));

        assertEquals("lead", removal.getFirst().outgoing());
        assertEquals(null, removal.getFirst().incoming());
        assertEquals(null, arrival.getFirst().outgoing());
        assertEquals("receiver", arrival.getFirst().incoming());
    }
}
