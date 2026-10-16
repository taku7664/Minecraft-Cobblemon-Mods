package jbro.cobblemon.ui.extended;

/** Aligns a move tooltip against the full move column, not the hovered tile. */
public final class MoveTooltipAnchor {
    // TypeIcon starts 9 px left of the tile; reserve focus growth (1.06x) and a 4 px gap.
    public static final int VISUAL_CLEARANCE = 18;
    /** The gap between a tooltip and the gimmick buttons under it. */
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

    /**
     * As above, also above the gimmick buttons left of the moves, whose top is [gimmickTop] (Integer.MAX_VALUE for
     * none): they sit at the grid's bottom, so the tooltip's bottom rises to clear them.
     */
    public static Position position(
            int columnLeft, int gimmickTop, int columnBottom, int tooltipWidth, int tooltipHeight,
            int screenWidth, int screenHeight
    ) {
        int bottom = gimmickTop == Integer.MAX_VALUE ? columnBottom : Math.min(columnBottom, gimmickTop - GIMMICK_CLEARANCE);
        return new Position(
            ViewportClamp.clamp(columnLeft - VISUAL_CLEARANCE - tooltipWidth, SCREEN_MARGIN, screenWidth, tooltipWidth, SCREEN_MARGIN),
            ViewportClamp.clamp(bottom - tooltipHeight, SCREEN_MARGIN, screenHeight, tooltipHeight, SCREEN_MARGIN)
        );
    }
}
