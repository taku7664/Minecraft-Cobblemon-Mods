package jbro.cobblemon.mcc.internal.battle

import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import java.util.UUID
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.compat.cobblemon173.runManagedCleanupForEachSafely
import jbro.cobblemon.mcc.api.battle.MccBattleTag
import jbro.cobblemon.mcc.api.battle.MccBattleTags
import com.cobblemon.mod.common.battles.BattleRegistry
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
    val tag: MccBattleTag,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ShowManagedBattleContentPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ShowManagedBattleContentPayload>(id("managed_battle_content_show"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, ShowManagedBattleContentPayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeUUID(payload.battleId)
                buffer.writeUtf(payload.tag.contentId)
                buffer.writeUtf(payload.tag.stage.orEmpty())
                buffer.writeUtf(payload.tag.opponentId.orEmpty())
            },
            { buffer ->
                ShowManagedBattleContentPayload(buffer.readUUID(), MccBattleTag(buffer.readUtf(),
                    buffer.readUtf().ifEmpty { null }, buffer.readUtf().ifEmpty { null }))
            },
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
        runManagedCleanupForEachSafely(
            items = battle.players,
            reportFailure = { player, failure ->
                MoreCobblemonContents.LOGGER.error(
                    "Managed mechanic visibility cleanup failed for player {} in battle {}",
                    player.uuid,
                    battle.battleId,
                    failure,
                )
            },
        ) { player ->
            if (ServerPlayNetworking.canSend(player, HideManagedBattleMechanicsPayload.TYPE)) {
                ServerPlayNetworking.send(player, payload)
            }
        }
    }
}

object ManagedBattleContentNetworking {
    fun registerServer() {
        PayloadTypeRegistry.playS2C().register(ShowManagedBattleContentPayload.TYPE, ShowManagedBattleContentPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(HideManagedBattleContentPayload.TYPE, HideManagedBattleContentPayload.CODEC)
    }

    /** Called from the battle constructor before Cobblemon sends its initial battle packets. */
    fun showBeforeBattleInitialization(battle: PokemonBattle, tag: MccBattleTag) {
        val payload = ShowManagedBattleContentPayload(battle.battleId, tag)
        battle.players.forEach { player ->
            if (ServerPlayNetworking.canSend(player, ShowManagedBattleContentPayload.TYPE)) {
                ServerPlayNetworking.send(player, payload)
            }
        }
    }

    fun showTo(player: net.minecraft.server.level.ServerPlayer, battleId: UUID, tag: MccBattleTag) {
        if (ServerPlayNetworking.canSend(player, ShowManagedBattleContentPayload.TYPE)) {
            ServerPlayNetworking.send(player, ShowManagedBattleContentPayload(battleId, tag))
        }
    }

    fun hideFrom(player: net.minecraft.server.level.ServerPlayer, battleId: UUID) {
        if (ServerPlayNetworking.canSend(player, HideManagedBattleContentPayload.TYPE)) {
            ServerPlayNetworking.send(player, HideManagedBattleContentPayload(battleId))
        }
    }

    /** Before [viewer] starts watching [target]'s battle, from `SpectateBattleHandlerMixin`: the battle's tag, if any. */
    fun showBeforeSpectating(target: net.minecraft.server.level.ServerPlayer, viewer: net.minecraft.server.level.ServerPlayer) {
        spectatorTagSafely(viewer) {
            val battle = BattleRegistry.getBattleByParticipatingPlayer(target) ?: return@spectatorTagSafely
            if (BattleRegistry.getBattleByParticipatingPlayer(viewer) != null) return@spectatorTagSafely
            MccBattleTags.of(battle.battleId)?.let { tag -> showTo(viewer, battle.battleId, tag) }
        }
    }

    /** After the attempt: Cobblemon refused [viewer] (distance, config, already watching), so the tag goes back. */
    fun withdrawUnlessSpectating(target: net.minecraft.server.level.ServerPlayer, viewer: net.minecraft.server.level.ServerPlayer) {
        spectatorTagSafely(viewer) {
            val battle = BattleRegistry.getBattleByParticipatingPlayer(target) ?: return@spectatorTagSafely
            if (MccBattleTags.of(battle.battleId) != null && viewer.uuid !in battle.spectators) hideFrom(viewer, battle.battleId)
        }
    }

    // A tag that fails to reach a spectator must not break Cobblemon's spectating.
    private inline fun spectatorTagSafely(viewer: net.minecraft.server.level.ServerPlayer, action: () -> Unit) {
        try {
            action()
        } catch (failure: RuntimeException) {
            MoreCobblemonContents.LOGGER.error("Spectator battle tag failed for player {}", viewer.uuid, failure)
        }
    }

    fun hide(battle: PokemonBattle) {
        val recipients = LinkedHashSet(battle.players)
        val server = battle.players.firstOrNull()?.server
        if (server != null) {
            battle.spectators.mapNotNullTo(recipients) { spectatorId -> server.playerList.getPlayer(spectatorId) }
        }
        runManagedCleanupForEachSafely(
            items = recipients,
            reportFailure = { player, failure ->
                MoreCobblemonContents.LOGGER.error(
                    "Managed content visibility cleanup failed for player {} in battle {}",
                    player.uuid,
                    battle.battleId,
                    failure,
                )
            },
        ) { player -> hideFrom(player, battle.battleId) }
    }
}

private fun id(path: String) = ResourceLocation.fromNamespaceAndPath(MoreCobblemonContents.MOD_ID, path)
