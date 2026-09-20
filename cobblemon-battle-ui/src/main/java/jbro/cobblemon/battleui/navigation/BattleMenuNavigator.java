package jbro.cobblemon.battleui.navigation;

import java.util.List;

public final class BattleMenuNavigator {
    private final List<Boolean> enabled;
    private int selectedIndex;

    public BattleMenuNavigator(List<Boolean> enabled, int selectedIndex) {
        this.enabled = List.copyOf(enabled);
        this.selectedIndex = isEnabled(selectedIndex) ? selectedIndex : -1;
    }

    public int selectedIndex() {
        return selectedIndex;
    }

    public int move(int direction) {
        if (enabled.isEmpty() || direction == 0) {
            return selectedIndex;
        }

        int step = direction > 0 ? 1 : -1;
        int candidate = selectedIndex;
        if (candidate < 0) {
            candidate = step > 0 ? enabled.size() - 1 : 0;
        }

        for (int checked = 0; checked < enabled.size(); checked++) {
            candidate = Math.floorMod(candidate + step, enabled.size());
            if (isEnabled(candidate)) {
                selectedIndex = candidate;
                return selectedIndex;
            }
        }
        selectedIndex = -1;
        return selectedIndex;
    }

    public void select(int index) {
        if (isEnabled(index)) {
            selectedIndex = index;
        }
    }

    private boolean isEnabled(int index) {
        return index >= 0 && index < enabled.size() && enabled.get(index);
    }
}
