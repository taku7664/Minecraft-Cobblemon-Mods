package jbro.cobblemon.popupemotes.client;

import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

record WheelEmote(String reference, Component label, Supplier<ResourceLocation> texture) {
    WheelEmote {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(texture, "texture");
    }
}
