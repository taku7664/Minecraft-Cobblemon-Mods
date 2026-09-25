package jbro.cobblemon.battleui.extended.navigation;

/** Keyboard tooltip visibility belongs to one move-selection instance. */
public final class MoveTooltipMode {
    private Object selection;
    private boolean enabled;

    public void observe(Object currentSelection) {
        if (selection != currentSelection) {
            selection = currentSelection;
            enabled = false;
        }
    }

    public void setEnabled(Object currentSelection, boolean value) {
        observe(currentSelection);
        enabled = value;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void clear() {
        selection = null;
        enabled = false;
    }
}
