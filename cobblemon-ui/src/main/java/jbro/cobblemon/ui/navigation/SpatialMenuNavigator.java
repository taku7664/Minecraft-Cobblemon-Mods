package jbro.cobblemon.ui.navigation;

import java.util.List;

/** Moves focus by rendered position, even when Cobblemon groups tile objects by side. */
public final class SpatialMenuNavigator {
    private SpatialMenuNavigator() {
    }

    public static int move(List<UiRect> bounds, List<Boolean> enabled, int current, int dx, int dy) {
        if (bounds.size() != enabled.size()) {
            throw new IllegalArgumentException("Each visible bound needs one eligibility flag");
        }
        if ((dx == 0) == (dy == 0) && (dx != 0 || dy != 0)) {
            throw new IllegalArgumentException("Move on one axis at a time");
        }
        if (current < 0 || current >= bounds.size() || !enabled.get(current)) {
            int first = -1;
            for (int index = 0; index < bounds.size(); index++) {
                if (!enabled.get(index)) continue;
                if (first < 0 || bounds.get(index).x() < bounds.get(first).x()
                        || (bounds.get(index).x() == bounds.get(first).x()
                        && bounds.get(index).y() < bounds.get(first).y())) first = index;
            }
            return first;
        }
        if (dx == 0 && dy == 0) return current;
        UiRect origin = bounds.get(current);
        int originX = origin.x() * 2 + origin.width();
        int originY = origin.y() * 2 + origin.height();
        int best = current;
        long bestScore = Long.MAX_VALUE;
        for (int index = 0; index < bounds.size(); index++) {
            if (index == current || !enabled.get(index)) continue;
            UiRect candidate = bounds.get(index);
            int deltaX = candidate.x() * 2 + candidate.width() - originX;
            int deltaY = candidate.y() * 2 + candidate.height() - originY;
            int primary = dx != 0 ? deltaX * dx : deltaY * dy;
            if (primary <= 0) continue;
            int secondary = Math.abs(dx != 0 ? deltaY : deltaX);
            long score = (long) secondary * 1_000_000 + primary;
            if (score < bestScore) {
                bestScore = score;
                best = index;
            }
        }
        return best;
    }
}
