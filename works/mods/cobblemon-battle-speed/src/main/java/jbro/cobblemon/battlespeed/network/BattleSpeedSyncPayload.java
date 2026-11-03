package jbro.cobblemon.battlespeed.network;

import jbro.cobblemon.battlespeed.BattleSpeed;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** The player's own level and the server config, so the settings screen can show and edit them. */
public record BattleSpeedSyncPayload(int level, String configJson) implements CustomPacketPayload {
    public static final Type<BattleSpeedSyncPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(BattleSpeed.MOD_ID, "sync")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, BattleSpeedSyncPayload> CODEC = StreamCodec.of(
        (buffer, payload) -> {
            buffer.writeVarInt(payload.level());
            buffer.writeUtf(payload.configJson(), BattleSpeedSaveConfigPayload.MAX_JSON_LENGTH);
        },
        buffer -> new BattleSpeedSyncPayload(
            buffer.readVarInt(),
            buffer.readUtf(BattleSpeedSaveConfigPayload.MAX_JSON_LENGTH)
        )
    );

    public BattleSpeedSyncPayload {
        Objects.requireNonNull(configJson, "configJson");
    }

    @Override
    public Type<BattleSpeedSyncPayload> type() {
        return TYPE;
    }
}
