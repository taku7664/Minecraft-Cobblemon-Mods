package jbro.cobblemon.battlespeed.network;

import jbro.cobblemon.battlespeed.BattleSpeed;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

/** Tells a client how fast to run one battle's HUD animations and its Pokémon model animations. */
public record BattleSpeedPayload(UUID battleId, float animationSpeed, float modelSpeed) implements CustomPacketPayload {
    public static final Type<BattleSpeedPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(BattleSpeed.MOD_ID, "battle_speed")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, BattleSpeedPayload> CODEC = StreamCodec.of(
        (buffer, payload) -> {
            buffer.writeUUID(payload.battleId());
            buffer.writeFloat(payload.animationSpeed());
            buffer.writeFloat(payload.modelSpeed());
        },
        buffer -> new BattleSpeedPayload(buffer.readUUID(), buffer.readFloat(), buffer.readFloat())
    );

    public BattleSpeedPayload {
        Objects.requireNonNull(battleId, "battleId");
    }

    @Override
    public Type<BattleSpeedPayload> type() {
        return TYPE;
    }
}
