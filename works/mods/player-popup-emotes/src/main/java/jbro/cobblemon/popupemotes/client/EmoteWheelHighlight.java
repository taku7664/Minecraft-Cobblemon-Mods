package jbro.cobblemon.popupemotes.client;

final class EmoteWheelHighlight {
    private static final int SELECTED_COLOR = 0xF05A78AC;
    private static final int HOVERED_COLOR = 0xE0C18A4B;
    private static final int SELECTED_HOVERED_COLOR = 0xFF7DA7E8;

    private EmoteWheelHighlight() {
    }

    static int colorFor(int index, int selectedIndex, int hoveredIndex, int baseColor) {
        boolean selected = index == selectedIndex;
        boolean hovered = index == hoveredIndex;
        if (selected && hovered) {
            return SELECTED_HOVERED_COLOR;
        }
        if (selected) {
            return SELECTED_COLOR;
        }
        if (hovered) {
            return HOVERED_COLOR;
        }
        return baseColor;
    }

    static int withAlpha(int color, float multiplier) {
        int alpha = Math.clamp(Math.round((color >>> 24) * multiplier), 0, 255);
        return color & 0x00FFFFFF | alpha << 24;
    }
}
