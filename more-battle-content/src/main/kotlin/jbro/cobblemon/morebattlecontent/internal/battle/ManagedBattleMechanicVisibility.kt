package jbro.cobblemon.morebattlecontent.internal.battle

import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import java.util.UUID
import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.api.presentation.ManagedBattleContentIds
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

internal enum class ManagedBattleMechanic(val mask: Int) {
    MEGA(1 shl 0),
    DYNAMAX(1 shl 1),
    TERA(1 shl 2),
    Z_MOVE(1 shl 3),
    ;

    companion object {
        fun encode(mechanics: Set<ManagedBattleMechanic>): Int = mechanics.fold(0) { mask, mechanic ->
            mask or mechanic.mask
        }

        fun decode(mask: Int): Set<ManagedBattleMechanic> {
            require(mask and ALL_MASK.inv() == 0) { "Unknown managed battle mechanic bits: $mask" }
            return entries.filterTo(LinkedHashSet()) { mechanic -> mask and mechanic.mask != 0 }
        }

        private val ALL_MASK = entries.fold(0) { mask, mechanic -> mask or mechanic.mask }
    }
}

internal data class ShowManagedBattleMechanicsPayload(
    val battleId: UUID,
    val mechanics: Set<ManagedBattleMechanic>,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ShowManagedBattleMechanicsPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ShowManagedBattleMechanicsPayload>(id("managed_battle_mechanics_show"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, ShowManagedBattleMechanicsPayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeUUID(payload.battleId)
                buffer.writeVarInt(ManagedBattleMechanic.encode(payload.mechanics))
            },
            { buffer ->
                ShowManagedBattleMechanicsPayload(
                    battleId = buffer.readUUID(),
                    mechanics = ManagedBattleMechanic.decode(buffer.readVarInt()),
                )
            },
        )
    }
}

internal data class HideManagedBattleMechanicsPayload(
    val battleId: UUID,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<HideManagedBattleMechanicsPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<HideManagedBattleMechanicsPayload>(id("managed_battle_mechanics_hide"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, HideManagedBattleMechanicsPayload> = StreamCodec.of(
            { buffer, payload -> buffer.writeUUID(payload.battleId) },
            { buffer -> HideManagedBattleMechanicsPayload(buffer.readUUID()) },
        )
    }
}

internal data class ShowManagedBattleContentPayload(
    val battleId: UUID,
    val contentId: String,
) : CustomPacketPayload {
    init {
        require(ManagedBattleContentIds.isValid(contentId)) { "contentId must be a lowercase namespaced ID" }
    }

    override fun type(): CustomPacketPayload.Type<ShowManagedBattleContentPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ShowManagedBattleContentPayload>(id("managed_battle_content_show"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, ShowManagedBattleContentPayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeUUID(payload.battleId)
                buffer.writeUtf(payload.contentId)
            },
            { buffer -> ShowManagedBattleContentPayload(buffer.readUUID(), buffer.readUtf()) },
        )
    }
}

internal data class HideManagedBattleContentPayload(
    val battleId: UUID,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<HideManagedBattleContentPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<HideManagedBattleContentPayload>(id("managed_battle_content_hide"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, HideManagedBattleContentPayload> = StreamCodec.of(
            { buffer, payload -> buffer.writeUUID(payload.battleId) },
            { buffer -> HideManagedBattleContentPayload(buffer.readUUID()) },
        )
    }
}

internal object ManagedBattleMechanicVisibilityNetworking {
    fun registerServer() {
        PayloadTypeRegistry.playS2C().register(ShowManagedBattleMechanicsPayload.TYPE, ShowManagedBattleMechanicsPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(HideManagedBattleMechanicsPayload.TYPE, HideManagedBattleMechanicsPayload.CODEC)
    }

    /** Called from the battle constructor before Cobblemon starts Showdown or sends its initial battle packets. */
    fun showBeforeBattleInitialization(battle: PokemonBattle, mechanics: Set<ManagedBattleMechanic>) {
        val payload = ShowManagedBattleMechanicsPayload(battle.battleId, mechanics)
        battle.players.forEach { player ->
            if (ServerPlayNetworking.canSend(player, ShowManagedBattleMechanicsPayload.TYPE)) {
                ServerPlayNetworking.send(player, payload)
            }
        }
    }

    fun hide(battle: PokemonBattle) {
        val payload = HideManagedBattleMechanicsPayload(battle.battleId)
        battle.players.forEach { player ->
            if (ServerPlayNetworking.canSend(player, HideManagedBattleMechanicsPayload.TYPE)) {
                ServerPlayNetworking.send(player, payload)
            }
        }
    }
}

internal object ManagedBattleContentNetworking {
    fun registerServer() {
        PayloadTypeRegistry.playS2C().register(ShowManagedBattleContentPayload.TYPE, ShowManagedBattleContentPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(HideManagedBattleContentPayload.TYPE, HideManagedBattleContentPayload.CODEC)
    }

    /** Called from the battle constructor before Cobblemon sends its initial battle packets. */
    fun showBeforeBattleInitialization(battle: PokemonBattle, contentId: String) {
        val payload = ShowManagedBattleContentPayload(battle.battleId, contentId)
        battle.players.forEach { player ->
            if (ServerPlayNetworking.canSend(player, ShowManagedBattleContentPayload.TYPE)) {
                ServerPlayNetworking.send(player, payload)
            }
        }
    }

    fun showTo(player: net.minecraft.server.level.ServerPlayer, battleId: UUID, contentId: String) {
        if (ServerPlayNetworking.canSend(player, ShowManagedBattleContentPayload.TYPE)) {
            ServerPlayNetworking.send(player, ShowManagedBattleContentPayload(battleId, contentId))
        }
    }

    fun hideFrom(player: net.minecraft.server.level.ServerPlayer, battleId: UUID) {
        if (ServerPlayNetworking.canSend(player, HideManagedBattleContentPayload.TYPE)) {
            ServerPlayNetworking.send(player, HideManagedBattleContentPayload(battleId))
        }
    }

    fun hide(battle: PokemonBattle) {
        val recipients = LinkedHashSet(battle.players)
        val server = battle.players.firstOrNull()?.server
        if (server != null) {
            battle.spectators.mapNotNullTo(recipients) { spectatorId -> server.playerList.getPlayer(spectatorId) }
        }
        recipients.forEach { player -> hideFrom(player, battle.battleId) }
    }
}

private fun id(path: String) = ResourceLocation.fromNamespaceAndPath(MoreBattleContent.MOD_ID, path)
