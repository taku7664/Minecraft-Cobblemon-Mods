package jbro.cobblemon.popupemotes.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class EmoteWheelSelectionTest {
    @Test
    void mapsCardinalDirectionsClockwiseFromTheTop() {
        assertEquals(0, EmoteWheelSelection.index(0.0, -62.0, 9));
        assertEquals(2, EmoteWheelSelection.index(62.0, 0.0, 9));
        assertEquals(4, EmoteWheelSelection.index(0.0, 62.0, 9));
        assertEquals(6, EmoteWheelSelection.index(-62.0, 0.0, 9));
    }

    @Test
    void mapsTheCenterAfterTheOuterSlicesAndIgnoresPointsOutsideTheWheel() {
        assertEquals(8, EmoteWheelSelection.index(0.0, 0.0, 9));
        assertEquals(8, EmoteWheelSelection.index(20.0, 0.0, 9));
        assertEquals(-1, EmoteWheelSelection.index(93.0, 0.0, 9));
        assertEquals(0, EmoteWheelSelection.index(0.0, 0.0, 1));
    }
}
