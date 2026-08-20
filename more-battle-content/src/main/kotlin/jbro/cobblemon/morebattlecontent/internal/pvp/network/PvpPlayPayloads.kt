package jbro.cobblemon.morebattlecontent.internal.pvp.network

import java.util.UUID
import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpBattleFormat
import jbro.cobblemon.morebattlecontent.internal.pvp.ui.PvpSelectionIntent
import jbro.cobblemon.morebattlecontent.internal.pvp.ui.PvpSelectionOpponentSlot
import jbro.cobblemon.morebattlecontent.internal.pvp.ui.PvpSelectionPartySlot
import jbro.cobblemon.morebattlecontent.internal.pvp.ui.PvpSelectionSpectator
import jbro.cobblemon.morebattlecontent.internal.pvp.ui.PvpSelectionViewState
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

internal data class PvpSelectionStatePayload(
    val requestId: UUID?,
    val state: PvpSelectionViewState,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<PvpSelectionStatePayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<PvpSelectionStatePayload>(id("pvp_selection_state"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, PvpSelectionStatePayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeBoolean(payload.requestId != null)
                payload.requestId?.let(buffer::writeUUID)
                buffer.writeState(payload.state)
            },
            { buffer ->
                PvpSelectionStatePayload(
                    requestId = if (buffer.readBoolean()) buffer.readUUID() else null,
                    state = buffer.readState(),
                )
            },
        )
    }
}

internal data class PvpSelectionIntentPayload(
    val intent: PvpSelectionIntent,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<PvpSelectionIntentPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<PvpSelectionIntentPayload>(id("pvp_selection_intent"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, PvpSelectionIntentPayload> = StreamCodec.of(
            { buffer, payload -> buffer.writeIntent(payload.intent) },
            { buffer -> PvpSelectionIntentPayload(buffer.readIntent()) },
        )
    }
}

internal data class PvpSelectionRejectedPayload(
    val requestId: UUID,
    val matchId: UUID,
    val messageKey: String,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<PvpSelectionRejectedPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<PvpSelectionRejectedPayload>(id("pvp_selection_rejected"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, PvpSelectionRejectedPayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeUUID(payload.requestId)
                buffer.writeUUID(payload.matchId)
                buffer.writeBoundedString(payload.messageKey)
            },
            { buffer ->
                PvpSelectionRejectedPayload(buffer.readUUID(), buffer.readUUID(), buffer.readBoundedString())
            },
        )
    }
}

internal data class PvpSelectionClosedPayload(
    val matchId: UUID,
    val messageKey: String,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<PvpSelectionClosedPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<PvpSelectionClosedPayload>(id("pvp_selection_closed"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, PvpSelectionClosedPayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeUUID(payload.matchId)
                buffer.writeBoundedString(payload.messageKey)
            },
            { buffer -> PvpSelectionClosedPayload(buffer.readUUID(), buffer.readBoundedString()) },
        )
    }
}

private fun RegistryFriendlyByteBuf.writeState(state: PvpSelectionViewState) {
    writeUUID(state.matchId)
    writeBoundedString(state.format.recordId)
    writeBoundedString(state.opponentName)
    writeVarInt(state.immutableOwnParty.size)
    state.immutableOwnParty.forEach { slot ->
        writeUUID(slot.pokemonId)
        writeBoundedString(slot.speciesId)
        writeBoolean(slot.heldItemId != null)
        slot.heldItemId?.let(::writeBoundedString)
        writeVarInt(slot.originalLevel)
        writeVarInt(slot.battleLevel)
        writeBoolean(slot.formId != null)
        slot.formId?.let(::writeBoundedString)
    }
    writeVarInt(state.immutableOpponentParty.size)
    state.immutableOpponentParty.forEach { slot ->
        writeBoundedString(slot.speciesId)
        writeBoolean(slot.formId != null)
        slot.formId?.let(::writeBoundedString)
    }
    writeVarInt(state.immutableSelectedPokemonIds.size)
    state.immutableSelectedPokemonIds.forEach(::writeUUID)
    writeLong(state.selectionDeadlineEpochMillis)
    writeBoolean(state.waitingForOpponent)
    writeBoolean(state.battleStartRetryAvailable)
    writeBoolean(state.playerOnLeft)
    writeBoundedString(state.leftPlayerName)
    writeBoundedString(state.rightPlayerName)
    writeVarInt(state.immutableSpectators.size)
    state.immutableSpectators.forEach { spectator ->
        writeUUID(spectator.playerId)
        writeBoundedString(spectator.name)
    }
}

private fun RegistryFriendlyByteBuf.readState(): PvpSelectionViewState {
    val matchId = readUUID()
    val formatId = readBoundedString()
    val format = PvpBattleFormat.entries.singleOrNull { it.recordId == formatId }
        ?: throw IllegalArgumentException("Unsupported PvP format: $formatId")
    val opponentName = readBoundedString()
    val ownParty = ArrayList<PvpSelectionPartySlot>()
    repeat(readBoundedSize(MAX_PARTY_SIZE, "own party")) {
        ownParty += PvpSelectionPartySlot(
            pokemonId = readUUID(),
            speciesId = readBoundedString(),
            heldItemId = if (readBoolean()) readBoundedString() else null,
            originalLevel = readVarInt(),
            battleLevel = readVarInt(),
            formId = if (readBoolean()) readBoundedString() else null,
        )
    }
    val opponentParty = ArrayList<PvpSelectionOpponentSlot>()
    repeat(readBoundedSize(MAX_PARTY_SIZE, "opponent party")) {
        opponentParty += PvpSelectionOpponentSlot(
            speciesId = readBoundedString(),
            formId = if (readBoolean()) readBoundedString() else null,
        )
    }
    val selected = LinkedHashSet<UUID>()
    repeat(readBoundedSize(MAX_SELECTION_SIZE, "selection")) { selected += readUUID() }
    val base = PvpSelectionViewState(
        matchId = matchId,
        format = format,
        opponentName = opponentName,
        ownParty = ownParty,
        opponentParty = opponentParty,
        selectedPokemonIds = selected,
        selectionDeadlineEpochMillis = readLong(),
        waitingForOpponent = readBoolean(),
        battleStartRetryAvailable = readBoolean(),
        playerOnLeft = readBoolean(),
        leftPlayerName = readBoundedString(),
        rightPlayerName = readBoundedString(),
        spectators = buildList {
            repeat(readBoundedSize(MAX_SPECTATORS, "spectator")) {
                add(PvpSelectionSpectator(readUUID(), readBoundedString()))
            }
        },
    )
    return base
}

private fun RegistryFriendlyByteBuf.writeIntent(intent: PvpSelectionIntent) {
    writeBoundedString(
        when (intent) {
            is PvpSelectionIntent.Submit -> "submit"
            is PvpSelectionIntent.Cancel -> "cancel"
            is PvpSelectionIntent.Retry -> "retry"
            is PvpSelectionIntent.Unready -> "unready"
        },
    )
    writeUUID(intent.requestId)
    writeUUID(intent.matchId)
    if (intent is PvpSelectionIntent.Submit) {
        writeVarInt(intent.pokemonIds.size)
        intent.pokemonIds.forEach(::writeUUID)
    }
}

private fun RegistryFriendlyByteBuf.readIntent(): PvpSelectionIntent {
    val actionId = readBoundedString()
    val requestId = readUUID()
    val matchId = readUUID()
    return when (actionId) {
        "submit" -> {
            val selected = ArrayList<UUID>()
            repeat(readBoundedSize(MAX_SELECTION_SIZE, "selection")) { selected += readUUID() }
            PvpSelectionIntent.Submit(requestId, matchId, selected)
        }
        "cancel" -> PvpSelectionIntent.Cancel(requestId, matchId)
        "retry" -> PvpSelectionIntent.Retry(requestId, matchId)
        "unready" -> PvpSelectionIntent.Unready(requestId, matchId)
        else -> throw IllegalArgumentException("Unsupported PvP selection action: $actionId")
    }
}

private fun RegistryFriendlyByteBuf.writeBoundedString(value: String) = writeUtf(value, MAX_STRING_LENGTH)

private fun RegistryFriendlyByteBuf.readBoundedString(): String = readUtf(MAX_STRING_LENGTH)

private fun RegistryFriendlyByteBuf.readBoundedSize(maximum: Int, label: String): Int =
    readVarInt().also { require(it in 0..maximum) { "Invalid $label count: $it" } }

private fun id(path: String): ResourceLocation =
    ResourceLocation.fromNamespaceAndPath(MoreBattleContent.MOD_ID, path)

private val MAX_PARTY_SIZE = PvpBattleFormat.entries.maxOf { it.registrationRange.last }
private val MAX_SELECTION_SIZE = PvpBattleFormat.entries.maxOf(PvpBattleFormat::selectionSize)
private const val MAX_STRING_LENGTH = 160
private const val MAX_SPECTATORS = 128
