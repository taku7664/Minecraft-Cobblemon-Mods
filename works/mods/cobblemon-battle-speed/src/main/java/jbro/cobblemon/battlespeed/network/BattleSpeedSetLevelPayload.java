package jbro.cobblemon.battlespeed.network;

import jbro.cobblemon.battlespeed.BattleSpeed;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** The settings screen picking a level; handled exactly like /battlespeed <level>. */
public record BattleSpeedSetLevelPayload(int level) implements CustomPacketPayload {
    public static final Type<BattleSpeedSetLevelPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(BattleSpeed.MOD_ID, "set_level")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, BattleSpeedSetLevelPayload> CODEC = StreamCodec.of(
        (buffer, payload) -> buffer.writeVarInt(payload.level()),
        buffer -> new BattleSpeedSetLevelPayload(buffer.readVarInt())
    );

    @Override
    public Type<BattleSpeedSetLevelPayload> type() {
        return TYPE;
    }
}
