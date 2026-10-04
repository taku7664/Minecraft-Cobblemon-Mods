package jbro.cobblemon.popupemotes.network;

import java.util.Objects;
import java.util.UUID;
import jbro.cobblemon.popupemotes.PlayerPopupEmotes;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ShowEmotePayload(UUID playerId, String emoteId, float heightOffset) implements CustomPacketPayload {
    public static final Type<ShowEmotePayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(PlayerPopupEmotes.MOD_ID, "show_emote")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, ShowEmotePayload> CODEC = StreamCodec.of(
        (buffer, payload) -> {
            buffer.writeUUID(payload.playerId());
            buffer.writeUtf(payload.emoteId(), UseEmotePayload.MAX_EMOTE_REFERENCE_LENGTH);
            buffer.writeFloat(payload.heightOffset());
        },
        buffer -> new ShowEmotePayload(
            buffer.readUUID(),
            buffer.readUtf(UseEmotePayload.MAX_EMOTE_REFERENCE_LENGTH),
            buffer.readFloat()
        )
    );

    public ShowEmotePayload {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(emoteId, "emoteId");
        if (!EmoteRequestPolicy.accepts(emoteId, heightOffset)) {
            throw new IllegalArgumentException("Invalid show-emote payload");
        }
    }

    @Override
    public Type<ShowEmotePayload> type() {
        return TYPE;
    }
}
