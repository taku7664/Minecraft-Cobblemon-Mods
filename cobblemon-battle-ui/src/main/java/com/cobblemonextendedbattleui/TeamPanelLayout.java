package jbro.cobblemon.battleui.extended;

/** Shared model and background geometry for team-indicator rendering and input. */
public record TeamPanelLayout(int modelWidth, int modelHeight, int panelWidth, int panelHeight) {
    public static final int HORIZONTAL_PADDING = 5;
    public static final int VERTICAL_PADDING = 2;

    public static TeamPanelLayout calculate(int teamSize, int modelSize, int modelSpacing, boolean vertical) {
        if (teamSize <= 0) {
            return new TeamPanelLayout(0, 0, 0, 0);
        }

        int lineExtent = teamSize * modelSize + (teamSize - 1) * modelSpacing;
        int modelWidth = vertical ? modelSize : lineExtent;
        int modelHeight = vertical ? lineExtent : modelSize;
        return new TeamPanelLayout(
            modelWidth,
            modelHeight,
            modelWidth + HORIZONTAL_PADDING * 2,
            modelHeight + VERTICAL_PADDING * 2
        );
    }

    public static TeamPanelLayout fromPanelSize(int panelWidth, int panelHeight) {
        return new TeamPanelLayout(
            Math.max(0, panelWidth - HORIZONTAL_PADDING * 2),
            Math.max(0, panelHeight - VERTICAL_PADDING * 2),
            Math.max(0, panelWidth),
            Math.max(0, panelHeight)
        );
    }

    public int resolveX(Integer customX, int defaultX, int screenWidth) {
        return ViewportClamp.resolve(
            customX,
            defaultX,
            HORIZONTAL_PADDING,
            screenWidth,
            modelWidth,
            HORIZONTAL_PADDING
        );
    }

    public int resolveY(Integer customY, int defaultY, int screenHeight) {
        return ViewportClamp.resolve(
            customY,
            defaultY,
            VERTICAL_PADDING,
            screenHeight,
            modelHeight,
            VERTICAL_PADDING
        );
    }
}
