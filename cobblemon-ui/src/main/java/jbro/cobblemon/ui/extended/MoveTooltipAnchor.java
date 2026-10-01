package jbro.cobblemon.ui.extended;

/** Aligns a move tooltip against the full move column, not the hovered tile. */
public final class MoveTooltipAnchor {
    // TypeIcon starts 9 px left of the tile; reserve focus growth (1.06x) and a 4 px gap.
    private static final int VISUAL_CLEARANCE = 18;
    private MoveTooltipAnchor() {
    }

    public record Position(int x, int y) {
    }

    public static Position position(
            int columnLeft, int columnBottom, int tooltipWidth, int tooltipHeight,
            int screenWidth, int screenHeight
    ) {
        return new Position(
            ViewportClamp.clamp(columnLeft - VISUAL_CLEARANCE - tooltipWidth, 4, screenWidth, tooltipWidth, 4),
            ViewportClamp.clamp(columnBottom - tooltipHeight, 4, screenHeight, tooltipHeight, 4)
        );
    }
}
