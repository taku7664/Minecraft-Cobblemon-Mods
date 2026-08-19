package jbro.cobblemon.morebattlecontent.internal.factory.network

import java.util.UUID
import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryBattleFormat
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryLevelMode
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryPlayError
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryPlayPhase
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryPlayView
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryRentalSet
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryStatSpread
import jbro.cobblemon.morebattlecontent.internal.factory.FactorySwapOffer
import jbro.cobblemon.morebattlecontent.internal.factory.ui.FactoryPlayIntent
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

internal data class FactoryPlayStatePayload(
    val requestId: UUID?,
    val state: FactoryPlayView,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<FactoryPlayStatePayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<FactoryPlayStatePayload>(id("factory_play_state"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, FactoryPlayStatePayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeBoolean(payload.requestId != null)
                payload.requestId?.let(buffer::writeUUID)
                buffer.writeState(payload.state)
            },
            { buffer -> FactoryPlayStatePayload(if (buffer.readBoolean()) buffer.readUUID() else null, buffer.readState()) },
        )
    }
}

internal data class FactoryPlayIntentPayload(val intent: FactoryPlayIntent) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<FactoryPlayIntentPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<FactoryPlayIntentPayload>(id("factory_play_intent"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, FactoryPlayIntentPayload> = StreamCodec.of(
            { buffer, payload -> buffer.writeIntent(payload.intent) },
            { buffer -> FactoryPlayIntentPayload(buffer.readIntent()) },
        )
    }
}

internal data class FactoryPlayRejectedPayload(
    val requestId: UUID,
    val error: FactoryPlayError,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<FactoryPlayRejectedPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<FactoryPlayRejectedPayload>(id("factory_play_rejected"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, FactoryPlayRejectedPayload> = StreamCodec.of(
            { buffer, payload ->
                buffer.writeUUID(payload.requestId)
                buffer.writeBoundedString(payload.error.name.lowercase())
            },
            { buffer ->
                val requestId = buffer.readUUID()
                val errorId = buffer.readBoundedString()
                FactoryPlayRejectedPayload(
                    requestId,
                    FactoryPlayError.entries.singleOrNull { it.name.equals(errorId, true) }
                        ?: throw IllegalArgumentException("Unsupported Factory error: $errorId"),
                )
            },
        )
    }
}

private fun RegistryFriendlyByteBuf.writeState(state: FactoryPlayView) {
    writeUUID(state.playerId)
    writeBoundedString(state.phase.name.lowercase())
    writeNullableEnum(state.format?.name)
    writeNullableEnum(state.levelMode?.id)
    writeVarInt(state.wins)
    writeVarInt(state.rentAndTradeCount)
    writeRentals(state.teamSets)
    writeRentals(state.draftSets)
    writeVarInt(state.swapOffers.size)
    state.swapOffers.forEach { offer ->
        writeUUID(offer.token)
        writeBoundedString(offer.speciesId)
        writeVarInt(offer.revealedMoveIds.size)
        offer.revealedMoveIds.forEach(::writeBoundedString)
        writeNullableString(offer.revealedAbilityId)
        writeNullableString(offer.revealedHeldItemId)
        writeNullableString(offer.formId)
    }
    writeBoolean(state.canReviseSelection)
    writeBoolean(state.activeBattleId != null)
    state.activeBattleId?.let(::writeUUID)
}

private fun RegistryFriendlyByteBuf.readState(): FactoryPlayView {
    val playerId = readUUID()
    val phaseId = readBoundedString()
    val phase = FactoryPlayPhase.entries.singleOrNull { it.name.equals(phaseId, true) }
        ?: throw IllegalArgumentException("Unsupported Factory phase: $phaseId")
    val format = readNullableString()?.let { value ->
        FactoryBattleFormat.entries.singleOrNull { it.name.equals(value, true) }
            ?: throw IllegalArgumentException("Unsupported Factory format: $value")
    }
    val levelMode = readNullableString()?.let(FactoryLevelMode::fromId)
    val wins = readVarInt().also { require(it >= 0) { "Factory wins must be non-negative" } }
    val trades = readVarInt().also { require(it >= 0) { "Factory rent and trade count must be non-negative" } }
    val team = readRentals(MAX_TEAM_SIZE, "team")
    val draft = readRentals(MAX_DRAFT_SIZE, "draft")
    val offers = ArrayList<FactorySwapOffer>()
    repeat(readBoundedSize(MAX_TEAM_SIZE, "swap offer")) {
        val token = readUUID()
        val speciesId = readBoundedString()
        val moves = LinkedHashSet<String>()
        repeat(readBoundedSize(MAX_MOVES, "revealed move")) { moves += readBoundedString() }
        offers += FactorySwapOffer(token, speciesId, moves, readNullableString(), readNullableString(), readNullableString())
    }
    val canReviseSelection = readBoolean()
    return FactoryPlayView(
        playerId,
        phase,
        format,
        levelMode,
        wins,
        trades,
        team,
        draft,
        offers,
        if (readBoolean()) readUUID() else null,
        canReviseSelection,
    )
}

private fun RegistryFriendlyByteBuf.writeRentals(rentals: List<FactoryRentalSet>) {
    writeVarInt(rentals.size)
    rentals.forEach { rental ->
        writeBoundedString(rental.setId)
        writeBoundedString(rental.speciesId)
        writeVarInt(rental.moveIds.size)
        rental.moveIds.forEach(::writeBoundedString)
        writeBoundedString(rental.abilityId)
        writeNullableString(rental.heldItemId)
        writeBoundedString(rental.natureId)
        writeSpread(rental.ivs)
        writeSpread(rental.evs)
        writeNullableString(rental.formId)
    }
}

private fun RegistryFriendlyByteBuf.readRentals(maximum: Int, label: String): List<FactoryRentalSet> = buildList {
    repeat(readBoundedSize(maximum, label)) {
        val setId = readBoundedString()
        val speciesId = readBoundedString()
        val moves = ArrayList<String>()
        repeat(readBoundedSize(MAX_MOVES, "move")) { moves += readBoundedString() }
        add(
            FactoryRentalSet(
                setId,
                speciesId,
                moves,
                readBoundedString(),
                readNullableString(),
                readBoundedString(),
                readSpread(),
                readSpread(),
                readNullableString(),
            ),
        )
    }
}

private fun RegistryFriendlyByteBuf.writeSpread(spread: FactoryStatSpread) {
    listOf(spread.hp, spread.attack, spread.defense, spread.specialAttack, spread.specialDefense, spread.speed)
        .forEach(::writeVarInt)
}

private fun RegistryFriendlyByteBuf.readSpread() = FactoryStatSpread(
    readVarInt(), readVarInt(), readVarInt(), readVarInt(), readVarInt(), readVarInt(),
)

private fun RegistryFriendlyByteBuf.writeIntent(intent: FactoryPlayIntent) {
    writeBoundedString(
        when (intent) {
            is FactoryPlayIntent.Start -> "start"
            is FactoryPlayIntent.SelectRentals -> "select"
            is FactoryPlayIntent.ReviseSelection -> "revise"
            is FactoryPlayIntent.BeginBattle -> "battle"
            is FactoryPlayIntent.KeepTeam -> "keep"
            is FactoryPlayIntent.Swap -> "swap"
            is FactoryPlayIntent.Abandon -> "abandon"
        },
    )
    writeUUID(intent.requestId)
    when (intent) {
        is FactoryPlayIntent.Start -> {
            writeBoundedString(intent.format.name.lowercase())
            writeBoundedString(intent.levelMode.id)
        }
        is FactoryPlayIntent.SelectRentals -> {
            writeVarInt(intent.setIds.size)
            intent.setIds.forEach(::writeBoundedString)
        }
        is FactoryPlayIntent.BeginBattle -> {
            writeVarInt(intent.orderedSetIds.size)
            intent.orderedSetIds.forEach(::writeBoundedString)
        }
        is FactoryPlayIntent.Swap -> {
            writeBoundedString(intent.outgoingSetId)
            writeUUID(intent.incomingToken)
        }
        else -> Unit
    }
}

private fun RegistryFriendlyByteBuf.readIntent(): FactoryPlayIntent {
    val action = readBoundedString()
    val requestId = readUUID()
    return when (action) {
        "start" -> {
            val formatId = readBoundedString()
            val format = FactoryBattleFormat.entries.singleOrNull { it.name.equals(formatId, true) }
                ?: throw IllegalArgumentException("Unsupported Factory format: $formatId")
            FactoryPlayIntent.Start(requestId, format, FactoryLevelMode.fromId(readBoundedString()))
        }
        "select" -> {
            val ids = ArrayList<String>()
            repeat(readBoundedSize(MAX_TEAM_SIZE, "selection")) { ids += readBoundedString() }
            FactoryPlayIntent.SelectRentals(requestId, ids)
        }
        "revise" -> FactoryPlayIntent.ReviseSelection(requestId)
        "battle" -> {
            val ids = ArrayList<String>()
            repeat(readBoundedSize(MAX_TEAM_SIZE, "battle order")) { ids += readBoundedString() }
            FactoryPlayIntent.BeginBattle(requestId, ids)
        }
        "keep" -> FactoryPlayIntent.KeepTeam(requestId)
        "swap" -> FactoryPlayIntent.Swap(requestId, readBoundedString(), readUUID())
        "abandon" -> FactoryPlayIntent.Abandon(requestId)
        else -> throw IllegalArgumentException("Unsupported Factory action: $action")
    }
}

private fun RegistryFriendlyByteBuf.writeNullableEnum(value: String?) = writeNullableString(value)

private fun RegistryFriendlyByteBuf.writeNullableString(value: String?) {
    writeBoolean(value != null)
    value?.let(::writeBoundedString)
}

private fun RegistryFriendlyByteBuf.readNullableString(): String? = if (readBoolean()) readBoundedString() else null

private fun RegistryFriendlyByteBuf.writeBoundedString(value: String) = writeUtf(value, MAX_STRING_LENGTH)
private fun RegistryFriendlyByteBuf.readBoundedString(): String = readUtf(MAX_STRING_LENGTH)
private fun RegistryFriendlyByteBuf.readBoundedSize(maximum: Int, label: String): Int =
    readVarInt().also { require(it in 0..maximum) { "Invalid Factory $label count: $it" } }

private fun id(path: String) = ResourceLocation.fromNamespaceAndPath(MoreBattleContent.MOD_ID, path)

private val MAX_TEAM_SIZE = FactoryBattleFormat.entries.maxOf(FactoryBattleFormat::selectionSize)
private const val MAX_DRAFT_SIZE = 6
private const val MAX_MOVES = 4
private const val MAX_STRING_LENGTH = 160
