package jbro.cobblemon.battleui.navigation;

public record UiRect(int x, int y, int width, int height) {
    public UiRect {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("UI rectangle dimensions must be non-negative");
        }
    }

    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}
