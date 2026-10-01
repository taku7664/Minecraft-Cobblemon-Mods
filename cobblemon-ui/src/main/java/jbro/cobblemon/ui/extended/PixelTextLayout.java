package jbro.cobblemon.ui.extended;

/** Pixel-grid metrics for text in battle overlays and tooltips. */
public final class PixelTextLayout {
    private PixelTextLayout() {}

    /**
     * The scale to draw text at under a parent transform, so the text lands at a whole multiple of the game's own
     * text size (at least 1). Fonts are drawn crisply only there: a fractional size, such as the earlier 1.5x or 2x
     * physical pixels, drops strokes from Hangul glyphs in both Minecraft's unifont and oversampled pixel fonts.
     */
    public static float fontScale(float requested, float parentScale, float guiScale) {
        return Math.max(1f, Math.round(requested * parentScale)) / parentScale;
    }

    /** [fontScale] for text drawn without a parent transform. */
    public static float crisp(float requested) {
        return Math.max(1f, Math.round(requested));
    }

    public static float snap(float local, float translation, float parentScale, float guiScale) {
        return (Math.round((local * parentScale + translation) * guiScale) / guiScale - translation) / parentScale;
    }
}
