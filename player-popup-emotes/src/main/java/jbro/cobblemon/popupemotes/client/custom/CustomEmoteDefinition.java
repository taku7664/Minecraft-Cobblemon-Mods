package jbro.cobblemon.popupemotes.client.custom;

import java.util.Objects;
import jbro.cobblemon.popupemotes.emote.RemoteEmoteUrlPolicy;

public record CustomEmoteDefinition(String name, String url) {
    public static final int MAX_NAME_LENGTH = 32;

    public CustomEmoteDefinition {
        name = Objects.requireNonNull(name, "name").trim();
        url = Objects.requireNonNull(url, "url").trim();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Custom emote name must contain 1-32 characters");
        }
        if (!RemoteEmoteUrlPolicy.acceptsSyntax(url)) {
            throw new IllegalArgumentException("Custom emote URL is not allowed: " + url);
        }
    }
}
