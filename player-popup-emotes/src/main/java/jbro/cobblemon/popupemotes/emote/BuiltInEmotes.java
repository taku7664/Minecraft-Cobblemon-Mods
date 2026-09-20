package jbro.cobblemon.popupemotes.emote;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

public final class BuiltInEmotes {
    private static final List<BuiltInEmote> ORDERED = List.of(
        emote("heart", "heart_of_the_sea"),
        emote("cheer", "firework_rocket"),
        emote("sparkle", "nether_star"),
        emote("idea", "glowstone_dust"),
        emote("surprise", "fire_charge"),
        emote("sad", "ghast_tear"),
        emote("sleep", "phantom_membrane"),
        emote("angry", "magma_cream"),
        emote("hello", "totem_of_undying")
    );
    private static final Map<String, BuiltInEmote> BY_ID = index(ORDERED);

    private BuiltInEmotes() {
    }

    public static List<BuiltInEmote> all() {
        return ORDERED;
    }

    public static boolean contains(String id) {
        return id != null && BY_ID.containsKey(id);
    }

    public static BuiltInEmote require(String id) {
        BuiltInEmote emote = BY_ID.get(id);
        if (emote == null) {
            throw new IllegalArgumentException("Unknown built-in emote: " + id);
        }
        return emote;
    }

    private static BuiltInEmote emote(String id, String itemTexture) {
        return new BuiltInEmote(
            id,
            ResourceLocation.withDefaultNamespace("textures/item/" + itemTexture + ".png"),
            "emote.player_popup_emotes." + id
        );
    }

    private static Map<String, BuiltInEmote> index(List<BuiltInEmote> emotes) {
        var result = new LinkedHashMap<String, BuiltInEmote>();
        for (BuiltInEmote emote : emotes) {
            if (result.put(emote.id(), emote) != null) {
                throw new IllegalStateException("Duplicate built-in emote id: " + emote.id());
            }
        }
        return Map.copyOf(result);
    }
}
