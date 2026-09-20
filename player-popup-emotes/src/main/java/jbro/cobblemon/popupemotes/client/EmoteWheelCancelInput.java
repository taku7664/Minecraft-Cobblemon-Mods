package jbro.cobblemon.popupemotes.client;

final class EmoteWheelCancelInput {
    private EmoteWheelCancelInput() {
    }

    static boolean requested(boolean escapeDown, boolean rightMouseDown) {
        return escapeDown || rightMouseDown;
    }
}
