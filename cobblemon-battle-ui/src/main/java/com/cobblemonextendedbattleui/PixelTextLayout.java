package jbro.cobblemon.battleui.extended;

/** Pixel-grid metrics for text inside the scaled battle-info modal. */
public final class PixelTextLayout {
    private PixelTextLayout() {}

    public static float fontScale(float requested, float parentScale, float guiScale) {
        float physicalParent = parentScale * guiScale;
        // The active Galmuri font is rasterized at 1.5x oversampling. Below 1.5 physical
        // scale its one-pixel strokes disappear even when the origin is snapped.
        return Math.max(1.5f, Math.round(requested * physicalParent)) / physicalParent;
    }

    public static float snap(float local, float translation, float parentScale, float guiScale) {
        return (Math.round((local * parentScale + translation) * guiScale) / guiScale - translation) / parentScale;
    }
}
