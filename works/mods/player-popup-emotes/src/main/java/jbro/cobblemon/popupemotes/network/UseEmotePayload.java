package jbro.cobblemon.popupemotes.network;

import java.util.Objects;
import jbro.cobblemon.popupemotes.PlayerPopupEmotes;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record UseEmotePayload(String emoteId, float heightOffset) implements CustomPacketPayload {
    public static final int MAX_EMOTE_REFERENCE_LENGTH = 2048;
    public static final Type<UseEmotePayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(PlayerPopupEmotes.MOD_ID, "use_emote")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, UseEmotePayload> CODEC = StreamCodec.of(
        (buffer, payload) -> {
            buffer.writeUtf(payload.emoteId(), MAX_EMOTE_REFERENCE_LENGTH);
            buffer.writeFloat(payload.heightOffset());
        },
        buffer -> new UseEmotePayload(buffer.readUtf(MAX_EMOTE_REFERENCE_LENGTH), buffer.readFloat())
    );

    public UseEmotePayload {
        Objects.requireNonNull(emoteId, "emoteId");
    }

    @Override
    public Type<UseEmotePayload> type() {
        return TYPE;
    }
}
