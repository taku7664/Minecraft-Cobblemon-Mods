package jbro.cobblemon.popupemotes.client;

final class EmoteSoundGate {
    private boolean played;

    boolean shouldPlay(boolean emoteReady, boolean playerPresent) {
        if (played || !emoteReady || !playerPresent) {
            return false;
        }
        played = true;
        return true;
    }
}
