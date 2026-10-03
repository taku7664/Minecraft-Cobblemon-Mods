package jbro.cobblemon.clientsetup.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

final class XaeroWaypointBridgeTest {
    @Test void optionalNameGetsDefaultAndNamedInputIsPreserved() {
        assertEquals("Waypoint", XaeroWaypointBridge.normalizeName(null));
        assertEquals("Waypoint", XaeroWaypointBridge.normalizeName("  "));
        assertEquals("우리 집", XaeroWaypointBridge.normalizeName("  우리 집  "));
    }

    @Test void lineBreaksAndFileDelimitersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> XaeroWaypointBridge.normalizeName("bad:name"));
        assertThrows(IllegalArgumentException.class, () -> XaeroWaypointBridge.normalizeName("bad\nname"));
        assertThrows(IllegalArgumentException.class, () -> XaeroWaypointBridge.normalizeName("x".repeat(65)));
    }
}
