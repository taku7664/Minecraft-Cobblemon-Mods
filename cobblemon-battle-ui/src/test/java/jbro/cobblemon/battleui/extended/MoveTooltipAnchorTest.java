package jbro.cobblemon.battleui.extended;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class MoveTooltipAnchorTest {
    @Test
    void tooltipLeavesRoomForProtrudingIconAndFocusGrowth() {
        MoveTooltipAnchor.Position position = MoveTooltipAnchor.position(720, 480, 210, 180, 960, 540);
        assertEquals(720 - 18, position.x() + 210);
        assertEquals(480, position.y() + 180);
    }

    @Test
    void impossibleFitClampsToViewportMargin() {
        MoveTooltipAnchor.Position position = MoveTooltipAnchor.position(100, 100, 210, 180, 320, 240);
        assertEquals(4, position.x());
        assertEquals(4, position.y());
    }
}
