package jbro.cobblemon.battleui.navigation;

public final class FocusOwnership {
    private boolean mousePositionKnown;
    private double lastMouseX;
    private double lastMouseY;
    private int selectedIndex = -1;
    private InputMethod inputMethod = InputMethod.KEYBOARD;

    public void mouseMoved(double mouseX, double mouseY, int hoveredIndex) {
        boolean moved = !mousePositionKnown || mouseX != lastMouseX || mouseY != lastMouseY;
        mousePositionKnown = true;
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        if (moved) {
            selectedIndex = hoveredIndex;
            inputMethod = InputMethod.MOUSE;
        }
    }

    public void mouseObservedWithoutMovement(int hoveredIndex) {
        // Rendering can observe a stationary cursor every frame. It must not steal keyboard focus.
    }

    public void keyboardSelected(int selectedIndex) {
        this.selectedIndex = selectedIndex;
        inputMethod = InputMethod.KEYBOARD;
    }

    public int selectedIndex() {
        return selectedIndex;
    }

    public InputMethod inputMethod() {
        return inputMethod;
    }
}
