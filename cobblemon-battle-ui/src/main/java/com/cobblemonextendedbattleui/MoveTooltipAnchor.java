package jbro.cobblemon.battleui.extended;

/** Aligns a move tooltip against the full move column, not the hovered tile. */
public final class MoveTooltipAnchor {
    private MoveTooltipAnchor() {
    }

    public record Position(int x, int y) {
    }

    public static Position position(
            int columnLeft, int columnBottom, int tooltipWidth, int tooltipHeight,
            int screenWidth, int screenHeight
    ) {
        return new Position(
            ViewportClamp.clamp(columnLeft - tooltipWidth, 4, screenWidth, tooltipWidth, 4),
            ViewportClamp.clamp(columnBottom - tooltipHeight, 4, screenHeight, tooltipHeight, 4)
        );
    }
}
