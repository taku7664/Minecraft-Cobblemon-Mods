package jbro.cobblemon.ui.extended;

/** Aligns a move tooltip against the full move column, not the hovered tile. */
public final class MoveTooltipAnchor {
    // TypeIcon starts 9 px left of the tile; reserve focus growth (1.06x) and a 4 px gap.
    public static final int VISUAL_CLEARANCE = 18;
    /** The gap between a tooltip and the gimmick column left of the moves. */
    public static final int GIMMICK_CLEARANCE = 2;
    /** The least distance a tooltip keeps from the screen's edges. */
    public static final int SCREEN_MARGIN = 4;
    private MoveTooltipAnchor() {
    }

    public record Position(int x, int y) {
    }

    public static Position position(
            int columnLeft, int columnBottom, int tooltipWidth, int tooltipHeight,
            int screenWidth, int screenHeight
    ) {
        return position(columnLeft, Integer.MAX_VALUE, columnBottom, tooltipWidth, tooltipHeight, screenWidth, screenHeight);
    }

    /** As above, also left of a gimmick column starting at [gimmickLeft] (Integer.MAX_VALUE for none). */
    public static Position position(
            int columnLeft, int gimmickLeft, int columnBottom, int tooltipWidth, int tooltipHeight,
            int screenWidth, int screenHeight
    ) {
        int right = gimmickLeft == Integer.MAX_VALUE ? columnLeft - VISUAL_CLEARANCE
                : Math.min(columnLeft - VISUAL_CLEARANCE, gimmickLeft - GIMMICK_CLEARANCE);
        return new Position(
            ViewportClamp.clamp(right - tooltipWidth, SCREEN_MARGIN, screenWidth, tooltipWidth, SCREEN_MARGIN),
            ViewportClamp.clamp(columnBottom - tooltipHeight, SCREEN_MARGIN, screenHeight, tooltipHeight, SCREEN_MARGIN)
        );
    }
}
