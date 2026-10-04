package jbro.cobblemon.betterbattlepresentation.network;

import java.util.Objects;
import java.util.UUID;
import jbro.cobblemon.betterbattlepresentation.BetterBattlePresentation;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record DynamaxAtmospherePayload(UUID battleId, boolean active) implements CustomPacketPayload {
    public static final Type<DynamaxAtmospherePayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(BetterBattlePresentation.MOD_ID, "dynamax_atmosphere")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, DynamaxAtmospherePayload> CODEC = StreamCodec.of(
        (buffer, payload) -> {
            buffer.writeUUID(payload.battleId());
            buffer.writeBoolean(payload.active());
        },
        buffer -> new DynamaxAtmospherePayload(buffer.readUUID(), buffer.readBoolean())
    );

    public DynamaxAtmospherePayload {
        Objects.requireNonNull(battleId, "battleId");
    }

    @Override
    public Type<DynamaxAtmospherePayload> type() {
        return TYPE;
    }
}
