package jbro.cobblemon.battleui.navigation;

import java.util.List;

public final class GridMenuNavigator {
    private final List<Boolean> enabled;
    private final int columns;
    private int selectedIndex;

    public GridMenuNavigator(List<Boolean> enabled, int columns, int selectedIndex) {
        if (columns <= 0) {
            throw new IllegalArgumentException("Column count must be positive");
        }
        this.enabled = List.copyOf(enabled);
        this.columns = columns;
        this.selectedIndex = isEnabled(selectedIndex) ? selectedIndex : -1;
    }

    public int move(int horizontal, int vertical) {
        if (enabled.isEmpty() || (horizontal == 0 && vertical == 0)) {
            return selectedIndex;
        }
        if (selectedIndex < 0) {
            selectedIndex = firstEnabled();
            return selectedIndex;
        }

        int rows = (enabled.size() + columns - 1) / columns;
        int row = selectedIndex / columns;
        int column = selectedIndex % columns;

        if (horizontal != 0) {
            int step = horizontal > 0 ? 1 : -1;
            for (int checked = 0; checked < columns; checked++) {
                column = Math.floorMod(column + step, columns);
                int candidate = row * columns + column;
                if (isEnabled(candidate)) {
                    selectedIndex = candidate;
                    return selectedIndex;
                }
            }
        } else {
            int step = vertical > 0 ? 1 : -1;
            for (int checked = 0; checked < rows; checked++) {
                row = Math.floorMod(row + step, rows);
                int candidate = row * columns + column;
                if (isEnabled(candidate)) {
                    selectedIndex = candidate;
                    return selectedIndex;
                }
            }
        }
        return selectedIndex;
    }

    private int firstEnabled() {
        for (int index = 0; index < enabled.size(); index++) {
            if (isEnabled(index)) {
                return index;
            }
        }
        return -1;
    }

    private boolean isEnabled(int index) {
        return index >= 0 && index < enabled.size() && enabled.get(index);
    }
}
