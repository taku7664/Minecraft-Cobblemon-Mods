package jbro.cobblemon.morebattlecontent.internal.pvp.network

import java.util.UUID
import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpBattleFormat
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpBattleMechanic
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpRoomPhase
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpRoomSettings
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpRoomSide
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpRoomVisibility
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

internal data class PvpRoomMemberView(val playerId: UUID, val name: String)

internal data class PvpRoomSummaryView(
    val roomId: UUID,
    val host: PvpRoomMemberView,
    val settings: PvpRoomSettings,
    val phase: PvpRoomPhase,
    val leftPlayer: PvpRoomMemberView?,
    val rightPlayer: PvpRoomMemberView?,
    val spectatorCount: Int,
)

internal data class PvpRoomClientView(
    val roomId: UUID,
    val hostId: UUID,
    val settings: PvpRoomSettings,
    val phase: PvpRoomPhase,
    val leftPlayer: PvpRoomMemberView?,
    val rightPlayer: PvpRoomMemberView?,
    val spectators: List<PvpRoomMemberView>,
    val inviteCandidates: List<PvpRoomMemberView>,
)

internal sealed interface PvpRoomIntent {
    val requestId: UUID

    data class Refresh(override val requestId: UUID) : PvpRoomIntent
    data class Create(override val requestId: UUID, val settings: PvpRoomSettings) : PvpRoomIntent
    data class Join(override val requestId: UUID, val roomId: UUID) : PvpRoomIntent
    data class Leave(override val requestId: UUID, val roomId: UUID) : PvpRoomIntent
    data class ClaimSeat(override val requestId: UUID, val roomId: UUID, val side: PvpRoomSide) : PvpRoomIntent
    data class Observe(override val requestId: UUID, val roomId: UUID) : PvpRoomIntent
    data class UpdateSettings(
        override val requestId: UUID,
        val roomId: UUID,
        val settings: PvpRoomSettings,
    ) : PvpRoomIntent
    data class Invite(override val requestId: UUID, val roomId: UUID, val targetId: UUID) : PvpRoomIntent
    data class DeclineInvite(override val requestId: UUID, val roomId: UUID) : PvpRoomIntent
    data class TransferHost(override val requestId: UUID, val roomId: UUID, val targetId: UUID) : PvpRoomIntent
    data class Start(override val requestId: UUID, val roomId: UUID) : PvpRoomIntent
}

internal data class PvpRoomListStatePayload(
    val requestId: UUID?,
    val rooms: List<PvpRoomSummaryView>,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<PvpRoomListStatePayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<PvpRoomListStatePayload>(roomId("pvp_room_list_state"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, PvpRoomListStatePayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeNullableUuid(payload.requestId)
                buffer.writeVarInt(payload.rooms.size)
                payload.rooms.forEach(buffer::writeSummary)
            },
            { buffer ->
                val requestId = buffer.readNullableUuid()
                val rooms = buildList {
                    repeat(buffer.readBoundedCount(MAX_ROOMS, "room")) { add(buffer.readSummary()) }
                }
                PvpRoomListStatePayload(requestId, rooms)
            },
        )
    }
}

internal data class PvpRoomStatePayload(
    val requestId: UUID?,
    val room: PvpRoomClientView,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<PvpRoomStatePayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<PvpRoomStatePayload>(roomId("pvp_room_state"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, PvpRoomStatePayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeNullableUuid(payload.requestId)
                buffer.writeRoom(payload.room)
            },
            { buffer -> PvpRoomStatePayload(buffer.readNullableUuid(), buffer.readRoom()) },
        )
    }
}

internal data class PvpRoomIntentPayload(val intent: PvpRoomIntent) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<PvpRoomIntentPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<PvpRoomIntentPayload>(roomId("pvp_room_intent"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, PvpRoomIntentPayload> = StreamCodec.of(
            { buffer, payload -> buffer.writeIntent(payload.intent) },
            { buffer -> PvpRoomIntentPayload(buffer.readIntent()) },
        )
    }
}

internal data class PvpRoomRejectedPayload(val requestId: UUID, val messageKey: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<PvpRoomRejectedPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<PvpRoomRejectedPayload>(roomId("pvp_room_rejected"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, PvpRoomRejectedPayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeUUID(payload.requestId)
                buffer.writeRoomString(payload.messageKey)
            },
            { buffer -> PvpRoomRejectedPayload(buffer.readUUID(), buffer.readRoomString()) },
        )
    }
}

internal data class PvpRoomInvitePayload(val roomId: UUID, val hostName: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<PvpRoomInvitePayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<PvpRoomInvitePayload>(roomId("pvp_room_invite"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, PvpRoomInvitePayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeUUID(payload.roomId)
                buffer.writeRoomString(payload.hostName)
            },
            { buffer -> PvpRoomInvitePayload(buffer.readUUID(), buffer.readRoomString()) },
        )
    }
}

internal data class PvpLoungeSpectatorStatePayload(val active: Boolean) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<PvpLoungeSpectatorStatePayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<PvpLoungeSpectatorStatePayload>(roomId("pvp_lounge_spectator_state"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, PvpLoungeSpectatorStatePayload> = StreamCodec.of(
            { buffer, payload -> buffer.writeBoolean(payload.active) },
            { buffer -> PvpLoungeSpectatorStatePayload(buffer.readBoolean()) },
        )
    }
}

private fun RegistryFriendlyByteBuf.writeSummary(summary: PvpRoomSummaryView) {
    writeUUID(summary.roomId)
    writeMember(summary.host)
    writeSettings(summary.settings)
    writeRoomString(summary.phase.name.lowercase())
    writeNullableMember(summary.leftPlayer)
    writeNullableMember(summary.rightPlayer)
    writeVarInt(summary.spectatorCount)
}

private fun RegistryFriendlyByteBuf.readSummary() = PvpRoomSummaryView(
    roomId = readUUID(),
    host = readMember(),
    settings = readSettings(),
    phase = readEnum("phase", PvpRoomPhase.entries),
    leftPlayer = readNullableMember(),
    rightPlayer = readNullableMember(),
    spectatorCount = readVarInt().also { require(it in 0..MAX_MEMBERS) { "Invalid spectator count: $it" } },
)

private fun RegistryFriendlyByteBuf.writeRoom(room: PvpRoomClientView) {
    writeUUID(room.roomId)
    writeUUID(room.hostId)
    writeSettings(room.settings)
    writeRoomString(room.phase.name.lowercase())
    writeNullableMember(room.leftPlayer)
    writeNullableMember(room.rightPlayer)
    writeMemberList(room.spectators)
    writeMemberList(room.inviteCandidates)
}

private fun RegistryFriendlyByteBuf.readRoom() = PvpRoomClientView(
    roomId = readUUID(),
    hostId = readUUID(),
    settings = readSettings(),
    phase = readEnum("phase", PvpRoomPhase.entries),
    leftPlayer = readNullableMember(),
    rightPlayer = readNullableMember(),
    spectators = readMemberList("spectator"),
    inviteCandidates = readMemberList("invite candidate"),
)

private fun RegistryFriendlyByteBuf.writeSettings(settings: PvpRoomSettings) {
    writeRoomString(settings.visibility.name.lowercase())
    writeRoomString(settings.format.recordId)
    writeVarInt(settings.immutableEnabledMechanics.size)
    settings.immutableEnabledMechanics.forEach { writeRoomString(it.id) }
}

private fun RegistryFriendlyByteBuf.readSettings(): PvpRoomSettings {
    val visibility = readEnum("visibility", PvpRoomVisibility.entries)
    val formatId = readRoomString()
    val format = PvpBattleFormat.entries.singleOrNull { it.recordId == formatId }
        ?: throw IllegalArgumentException("Unsupported PvP format: $formatId")
    val mechanics = LinkedHashSet<PvpBattleMechanic>()
    repeat(readBoundedCount(PvpBattleMechanic.entries.size, "mechanic")) {
        val mechanicId = readRoomString()
        mechanics += PvpBattleMechanic.entries.singleOrNull { it.id == mechanicId }
            ?: throw IllegalArgumentException("Unsupported PvP mechanic: $mechanicId")
    }
    return PvpRoomSettings(visibility, format, mechanics)
}

private fun RegistryFriendlyByteBuf.writeIntent(intent: PvpRoomIntent) {
    writeRoomString(
        when (intent) {
            is PvpRoomIntent.Refresh -> "refresh"
            is PvpRoomIntent.Create -> "create"
            is PvpRoomIntent.Join -> "join"
            is PvpRoomIntent.Leave -> "leave"
            is PvpRoomIntent.ClaimSeat -> "claim_seat"
            is PvpRoomIntent.Observe -> "observe"
            is PvpRoomIntent.UpdateSettings -> "settings"
            is PvpRoomIntent.Invite -> "invite"
            is PvpRoomIntent.DeclineInvite -> "decline_invite"
            is PvpRoomIntent.TransferHost -> "transfer_host"
            is PvpRoomIntent.Start -> "start"
        },
    )
    writeUUID(intent.requestId)
    when (intent) {
        is PvpRoomIntent.Refresh -> Unit
        is PvpRoomIntent.Create -> writeSettings(intent.settings)
        is PvpRoomIntent.Join -> writeUUID(intent.roomId)
        is PvpRoomIntent.Leave -> writeUUID(intent.roomId)
        is PvpRoomIntent.ClaimSeat -> {
            writeUUID(intent.roomId)
            writeRoomString(intent.side.name.lowercase())
        }
        is PvpRoomIntent.Observe -> writeUUID(intent.roomId)
        is PvpRoomIntent.UpdateSettings -> {
            writeUUID(intent.roomId)
            writeSettings(intent.settings)
        }
        is PvpRoomIntent.Invite -> {
            writeUUID(intent.roomId)
            writeUUID(intent.targetId)
        }
        is PvpRoomIntent.DeclineInvite -> writeUUID(intent.roomId)
        is PvpRoomIntent.TransferHost -> {
            writeUUID(intent.roomId)
            writeUUID(intent.targetId)
        }
        is PvpRoomIntent.Start -> writeUUID(intent.roomId)
    }
}

private fun RegistryFriendlyByteBuf.readIntent(): PvpRoomIntent {
    val action = readRoomString()
    val requestId = readUUID()
    return when (action) {
        "refresh" -> PvpRoomIntent.Refresh(requestId)
        "create" -> PvpRoomIntent.Create(requestId, readSettings())
        "join" -> PvpRoomIntent.Join(requestId, readUUID())
        "leave" -> PvpRoomIntent.Leave(requestId, readUUID())
        "claim_seat" -> PvpRoomIntent.ClaimSeat(requestId, readUUID(), readEnum("side", PvpRoomSide.entries))
        "observe" -> PvpRoomIntent.Observe(requestId, readUUID())
        "settings" -> PvpRoomIntent.UpdateSettings(requestId, readUUID(), readSettings())
        "invite" -> PvpRoomIntent.Invite(requestId, readUUID(), readUUID())
        "decline_invite" -> PvpRoomIntent.DeclineInvite(requestId, readUUID())
        "transfer_host" -> PvpRoomIntent.TransferHost(requestId, readUUID(), readUUID())
        "start" -> PvpRoomIntent.Start(requestId, readUUID())
        else -> throw IllegalArgumentException("Unsupported PvP room action: $action")
    }
}

private fun RegistryFriendlyByteBuf.writeMember(member: PvpRoomMemberView) {
    writeUUID(member.playerId)
    writeRoomString(member.name)
}

private fun RegistryFriendlyByteBuf.readMember() = PvpRoomMemberView(readUUID(), readRoomString())

private fun RegistryFriendlyByteBuf.writeNullableMember(member: PvpRoomMemberView?) {
    writeBoolean(member != null)
    member?.let(::writeMember)
}

private fun RegistryFriendlyByteBuf.readNullableMember(): PvpRoomMemberView? =
    if (readBoolean()) readMember() else null

private fun RegistryFriendlyByteBuf.writeMemberList(members: List<PvpRoomMemberView>) {
    writeVarInt(members.size)
    members.forEach(::writeMember)
}

private fun RegistryFriendlyByteBuf.readMemberList(label: String) = buildList {
    repeat(readBoundedCount(MAX_MEMBERS, label)) { add(readMember()) }
}

private fun RegistryFriendlyByteBuf.writeNullableUuid(value: UUID?) {
    writeBoolean(value != null)
    value?.let(::writeUUID)
}

private fun RegistryFriendlyByteBuf.readNullableUuid(): UUID? = if (readBoolean()) readUUID() else null

private fun RegistryFriendlyByteBuf.writeRoomString(value: String) = writeUtf(value, MAX_STRING_LENGTH)
private fun RegistryFriendlyByteBuf.readRoomString(): String = readUtf(MAX_STRING_LENGTH)
private fun RegistryFriendlyByteBuf.readBoundedCount(maximum: Int, label: String): Int =
    readVarInt().also { require(it in 0..maximum) { "Invalid PvP $label count: $it" } }

private inline fun <reified E : Enum<E>> RegistryFriendlyByteBuf.readEnum(label: String, values: List<E>): E {
    val value = readRoomString()
    return values.singleOrNull { it.name.equals(value, true) }
        ?: throw IllegalArgumentException("Unsupported PvP $label: $value")
}

private fun roomId(path: String): ResourceLocation =
    ResourceLocation.fromNamespaceAndPath(MoreBattleContent.MOD_ID, path)

private const val MAX_ROOMS = 256
private const val MAX_MEMBERS = 128
private const val MAX_STRING_LENGTH = 160
