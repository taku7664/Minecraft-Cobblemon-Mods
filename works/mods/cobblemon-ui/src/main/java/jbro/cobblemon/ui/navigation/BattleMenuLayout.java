package jbro.cobblemon.ui.navigation;

import jbro.cobblemon.ui.extended.ui.shared.BattleControlLayout;
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

        return BattleControlLayout.vertical(screenWidth, screenHeight, buttonWidth, buttonHeight,
                        gap, rightMargin, bottomMargin, count).stream()
                .map(rect -> new UiRect(rect.getX(), rect.getY(), rect.getWidth(), rect.getHeight()))
                .toList();
    }
}
