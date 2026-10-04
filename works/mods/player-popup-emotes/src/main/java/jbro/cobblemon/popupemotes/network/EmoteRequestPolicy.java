package jbro.cobblemon.popupemotes.network;

import jbro.cobblemon.popupemotes.emote.BuiltInEmotes;
import jbro.cobblemon.popupemotes.emote.RemoteEmoteUrlPolicy;

public final class EmoteRequestPolicy {
    public static final float MIN_HEIGHT_OFFSET = -1.0F;
    public static final float MAX_HEIGHT_OFFSET = 1.0F;

    private EmoteRequestPolicy() {
    }

    public static boolean accepts(String emoteId, float heightOffset) {
        return (BuiltInEmotes.contains(emoteId)
            || (RemoteEmoteUrlPolicy.acceptsSyntax(emoteId) && !RemoteEmoteUrlPolicy.isKnownWebPage(emoteId)))
            && Float.isFinite(heightOffset)
            && heightOffset >= MIN_HEIGHT_OFFSET
            && heightOffset <= MAX_HEIGHT_OFFSET;
    }
}
