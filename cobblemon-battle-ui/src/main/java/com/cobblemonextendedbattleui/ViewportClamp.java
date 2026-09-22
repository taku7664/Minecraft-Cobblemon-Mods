package jbro.cobblemon.battleui.extended;

/** Safe viewport positioning for content that may be larger than the scaled window. */
public final class ViewportClamp {
    private ViewportClamp() {
    }

    public static int clamp(int value, int leadingMargin, int viewportSize, int contentSize, int trailingMargin) {
        int maximum = viewportSize - contentSize - trailingMargin;
        if (maximum < leadingMargin) {
            return leadingMargin;
        }
        return Math.max(leadingMargin, Math.min(value, maximum));
    }

    public static int resolve(
        Integer customValue,
        int defaultValue,
        int leadingMargin,
        int viewportSize,
        int contentSize,
        int trailingMargin
    ) {
        return clamp(
            customValue != null ? customValue : defaultValue,
            leadingMargin,
            viewportSize,
            contentSize,
            trailingMargin
        );
    }
}
