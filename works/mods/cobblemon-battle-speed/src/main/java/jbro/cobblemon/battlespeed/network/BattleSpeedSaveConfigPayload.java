package jbro.cobblemon.battlespeed.network;

import jbro.cobblemon.battlespeed.BattleSpeed;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** An operator saving the server config from the settings screen; the server checks the permission again. */
public record BattleSpeedSaveConfigPayload(String configJson) implements CustomPacketPayload {
    /** Far above any real config; keeps a bad client from sending a huge string. */
    public static final int MAX_JSON_LENGTH = 32_768;

    public static final Type<BattleSpeedSaveConfigPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(BattleSpeed.MOD_ID, "save_config")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, BattleSpeedSaveConfigPayload> CODEC = StreamCodec.of(
        (buffer, payload) -> buffer.writeUtf(payload.configJson(), MAX_JSON_LENGTH),
        buffer -> new BattleSpeedSaveConfigPayload(buffer.readUtf(MAX_JSON_LENGTH))
    );

    public BattleSpeedSaveConfigPayload {
        Objects.requireNonNull(configJson, "configJson");
    }

    @Override
    public Type<BattleSpeedSaveConfigPayload> type() {
        return TYPE;
    }
}
