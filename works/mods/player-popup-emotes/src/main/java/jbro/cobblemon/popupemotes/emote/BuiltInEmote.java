package jbro.cobblemon.popupemotes.emote;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

public record BuiltInEmote(String id, ResourceLocation texture, String translationKey) {
    public BuiltInEmote {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(texture, "texture");
        Objects.requireNonNull(translationKey, "translationKey");
        if (!id.matches("[a-z0-9_]{1,24}")) {
            throw new IllegalArgumentException("Invalid built-in emote id: " + id);
        }
    }
}
