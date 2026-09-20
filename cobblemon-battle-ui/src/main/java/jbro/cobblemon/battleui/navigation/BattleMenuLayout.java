package jbro.cobblemon.battleui.navigation;

import java.util.ArrayList;
import java.util.List;

public final class BattleMenuLayout {
    private BattleMenuLayout() {
    }

    public static List<UiRect> vertical(
            int screenWidth,
            int screenHeight,
            int buttonWidth,
            int buttonHeight,
            int gap,
            int rightMargin,
            int bottomMargin,
            int count
    ) {
        if (count < 0 || buttonWidth < 0 || buttonHeight < 0 || gap < 0) {
            throw new IllegalArgumentException("Button dimensions, gap, and count must be non-negative");
        }

        int x = screenWidth - rightMargin - buttonWidth;
        int totalHeight = count == 0 ? 0 : count * buttonHeight + (count - 1) * gap;
        int firstY = screenHeight - bottomMargin - totalHeight;
        List<UiRect> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            result.add(new UiRect(x, firstY + index * (buttonHeight + gap), buttonWidth, buttonHeight));
        }
        return List.copyOf(result);
    }
}
