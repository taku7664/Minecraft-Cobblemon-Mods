package jbro.cobblemon.mcc.internal.tower.network

import java.util.UUID
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.api.rules.MajorBattleMechanic
import jbro.cobblemon.mcc.internal.tower.TowerBattleFormat
import jbro.cobblemon.mcc.internal.tower.TowerMode
import jbro.cobblemon.mcc.internal.tower.TOWER_REGISTERED_TEAM_SIZE
import jbro.cobblemon.mcc.internal.tower.ui.TowerPlayIntent
import jbro.cobblemon.mcc.internal.tower.ui.TowerPlayMutationResult
import jbro.cobblemon.mcc.internal.tower.ui.TowerPlayPartySlot
import jbro.cobblemon.mcc.internal.tower.ui.TowerPlayPhase
import jbro.cobblemon.mcc.internal.tower.ui.TowerPlayViewState
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

internal data class TowerPlayStatePayload(
    val requestId: UUID?,
    val state: TowerPlayViewState,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<TowerPlayStatePayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<TowerPlayStatePayload>(id("tower_play_state"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, TowerPlayStatePayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeBoolean(payload.requestId != null)
                payload.requestId?.let(buffer::writeUUID)
                buffer.writeState(payload.state)
            },
            { buffer ->
                TowerPlayStatePayload(
                    if (buffer.readBoolean()) buffer.readUUID() else null,
                    buffer.readState(),
                )
            },
        )
    }
}

internal data class TowerPlayIntentPayload(
    val intent: TowerPlayIntent,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<TowerPlayIntentPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<TowerPlayIntentPayload>(id("tower_play_intent"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, TowerPlayIntentPayload> = StreamCodec.of(
            { buffer, payload -> buffer.writeIntent(payload.intent) },
            { buffer -> TowerPlayIntentPayload(buffer.readIntent()) },
        )
    }
}

internal data class TowerPlayRejectedPayload(
    val result: TowerPlayMutationResult.Rejected,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<TowerPlayRejectedPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<TowerPlayRejectedPayload>(id("tower_play_rejected"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, TowerPlayRejectedPayload> = StreamCodec.of(
            { buffer, payload ->
                val result = payload.result
                buffer.writeUUID(result.requestId)
                buffer.writeVarLong(result.currentRevision)
                buffer.writeBoundedString(result.messageKey)
                buffer.writeVarInt(result.fieldErrors.size)
                result.fieldErrors.forEach { (field, error) ->
                    buffer.writeBoundedString(field)
                    buffer.writeBoundedString(error)
                }
            },
            { buffer ->
                val requestId = buffer.readUUID()
                val revision = buffer.readVarLong()
                val messageKey = buffer.readBoundedString()
                val errors = LinkedHashMap<String, String>()
                repeat(buffer.readBoundedSize(MAX_FIELD_ERRORS, "field error")) {
                    errors[buffer.readBoundedString()] = buffer.readBoundedString()
                }
                TowerPlayRejectedPayload(
                    TowerPlayMutationResult.Rejected(requestId, revision, messageKey, errors),
                )
            },
        )
    }
}

private fun RegistryFriendlyByteBuf.writeState(state: TowerPlayViewState) {
    writeUUID(state.entryContextId)
    writeVarLong(state.revision)
    writeBoundedString(state.format.recordId)
    writeBoundedString(state.mode.id)
    writeBoolean(state.endlessUnlocked)
    writeBoundedString(state.phase.name.lowercase())
    writeBoolean(state.selectedMechanic != null)
    state.selectedMechanic?.let { writeBoundedString(it.id) }
    writeBoolean(state.runStarted)
    writeVarInt(state.party.size)
    state.party.forEach(::writePartySlot)
    writeVarInt(state.selectedPokemonOrder.size)
    state.selectedPokemonOrder.forEach(::writeUUID)
    writeVarInt(state.currentWinStreak)
    writeVarInt(state.bestWinStreak)
    writeVarLong(state.bpBalance)
    writeVarInt(state.errorKeys.size)
    state.errorKeys.forEach(::writeBoundedString)
}

private fun RegistryFriendlyByteBuf.readState(): TowerPlayViewState {
    val contextId = readUUID()
    val revision = readVarLong()
    val formatId = readBoundedString()
    val format = TowerBattleFormat.entries.singleOrNull { it.recordId == formatId }
        ?: throw IllegalArgumentException("Unsupported tower format: $formatId")
    val mode = readMode()
    val endlessUnlocked = readBoolean()
    val phaseId = readBoundedString()
    val phase = TowerPlayPhase.entries.singleOrNull { it.name.equals(phaseId, ignoreCase = true) }
        ?: throw IllegalArgumentException("Unsupported tower play phase: $phaseId")
    val mechanic = if (readBoolean()) {
        val mechanicId = readBoundedString()
        MajorBattleMechanic.entries.singleOrNull { it.id == mechanicId }
            ?: throw IllegalArgumentException("Unsupported tower mechanic: $mechanicId")
    } else {
        null
    }
    val runStarted = readBoolean()
    val party = ArrayList<TowerPlayPartySlot>()
    repeat(readBoundedSize(TOWER_REGISTERED_TEAM_SIZE, "party")) { party += readPartySlot() }
    val selected = LinkedHashSet<UUID>()
    repeat(readBoundedSize(MAX_TOWER_SELECTION_SIZE, "selection")) { selected += readUUID() }
    val currentWinStreak = readVarInt()
    val bestWinStreak = readVarInt()
    val bpBalance = readVarLong()
    val errors = ArrayList<String>()
    repeat(readBoundedSize(MAX_ERROR_KEYS, "error key")) { errors += readBoundedString() }
    return TowerPlayViewState(
        entryContextId = contextId,
        revision = revision,
        format = format,
        phase = phase,
        party = party,
        selectedPokemonIds = selected,
        currentWinStreak = currentWinStreak,
        bestWinStreak = bestWinStreak,
        bpBalance = bpBalance,
        errorKeys = errors,
        selectedMechanic = mechanic,
        runStarted = runStarted,
        mode = mode,
        endlessUnlocked = endlessUnlocked,
    )
}

private fun RegistryFriendlyByteBuf.readMode(): TowerMode {
    val modeId = readBoundedString()
    return TowerMode.entries.singleOrNull { it.id == modeId }
        ?: throw IllegalArgumentException("Unsupported tower mode: $modeId")
}

private fun RegistryFriendlyByteBuf.writePartySlot(slot: TowerPlayPartySlot) {
    writeVarInt(slot.slot)
    writeUUID(slot.pokemonId)
    writeBoundedString(slot.speciesId)
    writeBoolean(slot.heldItemId != null)
    slot.heldItemId?.let(::writeBoundedString)
    writeVarInt(slot.level)
    writeVarInt(slot.battleLevel)
    writeBoolean(slot.legendaryClass)
    writeBoolean(slot.formId != null)
    slot.formId?.let(::writeBoundedString)
}

private fun RegistryFriendlyByteBuf.readPartySlot(): TowerPlayPartySlot = TowerPlayPartySlot(
    slot = readVarInt(),
    pokemonId = readUUID(),
    speciesId = readBoundedString(),
    heldItemId = if (readBoolean()) readBoundedString() else null,
    level = readVarInt(),
    battleLevel = readVarInt(),
    legendaryClass = readBoolean(),
    formId = if (readBoolean()) readBoundedString() else null,
)

private fun RegistryFriendlyByteBuf.writeIntent(intent: TowerPlayIntent) {
    val actionId = when (intent) {
        is TowerPlayIntent.ToggleSelection -> "toggle_selection"
        is TowerPlayIntent.ChangeFormat -> "change_format"
        is TowerPlayIntent.ChangeMode -> "change_mode"
        is TowerPlayIntent.ChangeMechanic -> "change_mechanic"
        is TowerPlayIntent.LockTeam -> "lock_team"
        is TowerPlayIntent.Start -> "start"
        is TowerPlayIntent.ChangeTeam -> "change_team"
        is TowerPlayIntent.Forfeit -> "forfeit"
        is TowerPlayIntent.Retire -> "retire"
    }
    writeBoundedString(actionId)
    writeUUID(intent.requestId)
    writeUUID(intent.entryContextId)
    writeVarLong(intent.expectedRevision)
    when (intent) {
        is TowerPlayIntent.ToggleSelection -> writeUUID(intent.pokemonId)
        is TowerPlayIntent.ChangeFormat -> writeBoundedString(intent.format.recordId)
        is TowerPlayIntent.ChangeMode -> writeBoundedString(intent.mode.id)
        is TowerPlayIntent.ChangeMechanic -> writeBoundedString(intent.mechanic.id)
        else -> Unit
    }
}

private fun RegistryFriendlyByteBuf.readIntent(): TowerPlayIntent {
    val actionId = readBoundedString()
    val requestId = readUUID()
    val contextId = readUUID()
    val revision = readVarLong()
    return when (actionId) {
        "toggle_selection" -> TowerPlayIntent.ToggleSelection(requestId, contextId, revision, readUUID())
        "change_format" -> {
            val formatId = readBoundedString()
            val format = TowerBattleFormat.entries.singleOrNull { it.recordId == formatId }
                ?: throw IllegalArgumentException("Unsupported tower format: $formatId")
            TowerPlayIntent.ChangeFormat(requestId, contextId, revision, format)
        }
        "change_mode" -> TowerPlayIntent.ChangeMode(requestId, contextId, revision, readMode())
        "change_mechanic" -> {
            val mechanicId = readBoundedString()
            val mechanic = MajorBattleMechanic.entries.singleOrNull { it.id == mechanicId }
                ?: throw IllegalArgumentException("Unsupported tower mechanic: $mechanicId")
            TowerPlayIntent.ChangeMechanic(requestId, contextId, revision, mechanic)
        }
        "lock_team" -> TowerPlayIntent.LockTeam(requestId, contextId, revision)
        "start" -> TowerPlayIntent.Start(requestId, contextId, revision)
        "change_team" -> TowerPlayIntent.ChangeTeam(requestId, contextId, revision)
        "forfeit" -> TowerPlayIntent.Forfeit(requestId, contextId, revision)
        "retire" -> TowerPlayIntent.Retire(requestId, contextId, revision)
        else -> throw IllegalArgumentException("Unsupported tower play action: $actionId")
    }
}

private fun RegistryFriendlyByteBuf.writeBoundedString(value: String) = writeUtf(value, MAX_STRING_LENGTH)

private fun RegistryFriendlyByteBuf.readBoundedString(): String = readUtf(MAX_STRING_LENGTH)

private fun RegistryFriendlyByteBuf.readBoundedSize(maximum: Int, label: String): Int =
    readVarInt().also { require(it in 0..maximum) { "Invalid $label count: $it" } }

private fun id(path: String): ResourceLocation =
    ResourceLocation.fromNamespaceAndPath(MoreCobblemonContents.MOD_ID, path)

private val MAX_TOWER_SELECTION_SIZE = TowerBattleFormat.entries.maxOf(TowerBattleFormat::selectionSize)
private const val MAX_ERROR_KEYS = 16
private const val MAX_FIELD_ERRORS = 16
private const val MAX_STRING_LENGTH = 160
