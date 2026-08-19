package jbro.cobblemon.morebattlecontent.internal.terminal

import java.util.UUID

internal data class TerminalInteractionSnapshot(
    val expectedTerminalId: UUID,
    val blockEntityTerminalId: UUID?,
    val terminalDimensionId: String,
    val terminalX: Int,
    val terminalY: Int,
    val terminalZ: Int,
    val playerDimensionId: String,
    val playerX: Double,
    val playerY: Double,
    val playerZ: Double,
    val terminalBlockPresent: Boolean,
    val permitted: Boolean,
) {
    init {
        require(terminalDimensionId.isNotBlank()) { "Terminal dimension ID cannot be blank" }
        require(playerDimensionId.isNotBlank()) { "Player dimension ID cannot be blank" }
    }
}

internal enum class TerminalInteractionRejection {
    DIFFERENT_DIMENSION,
    INVALID_POSITION,
    TOO_FAR,
    TERMINAL_MISSING,
    TERMINAL_ID_MISMATCH,
    NOT_PERMITTED,
}

internal sealed interface TerminalInteractionResult {
    data class Verified(
        val terminalId: UUID,
        val entryContextId: UUID,
        val dimensionId: String,
        val x: Int,
        val y: Int,
        val z: Int,
    ) : TerminalInteractionResult

    data class Rejected(val reason: TerminalInteractionRejection) : TerminalInteractionResult
}

internal class TerminalInteractionService(
    private val entryContextIdFactory: () -> UUID = UUID::randomUUID,
) {
    fun verify(snapshot: TerminalInteractionSnapshot): TerminalInteractionResult {
        if (snapshot.playerDimensionId != snapshot.terminalDimensionId) {
            return TerminalInteractionResult.Rejected(TerminalInteractionRejection.DIFFERENT_DIMENSION)
        }
        if (!snapshot.terminalBlockPresent || snapshot.blockEntityTerminalId == null) {
            return TerminalInteractionResult.Rejected(TerminalInteractionRejection.TERMINAL_MISSING)
        }
        if (snapshot.blockEntityTerminalId != snapshot.expectedTerminalId) {
            return TerminalInteractionResult.Rejected(TerminalInteractionRejection.TERMINAL_ID_MISMATCH)
        }
        if (!snapshot.permitted) {
            return TerminalInteractionResult.Rejected(TerminalInteractionRejection.NOT_PERMITTED)
        }
        if (!snapshot.playerX.isFinite() || !snapshot.playerY.isFinite() || !snapshot.playerZ.isFinite()) {
            return TerminalInteractionResult.Rejected(TerminalInteractionRejection.INVALID_POSITION)
        }
        val dx = snapshot.playerX - (snapshot.terminalX + 0.5)
        val dy = snapshot.playerY - (snapshot.terminalY + 0.5)
        val dz = snapshot.playerZ - (snapshot.terminalZ + 0.5)
        if (dx * dx + dy * dy + dz * dz > MAX_TERMINAL_USE_DISTANCE_SQUARED) {
            return TerminalInteractionResult.Rejected(TerminalInteractionRejection.TOO_FAR)
        }
        return TerminalInteractionResult.Verified(
            terminalId = snapshot.expectedTerminalId,
            entryContextId = entryContextIdFactory(),
            dimensionId = snapshot.terminalDimensionId,
            x = snapshot.terminalX,
            y = snapshot.terminalY,
            z = snapshot.terminalZ,
        )
    }

    private companion object {
        const val MAX_TERMINAL_USE_DISTANCE = 8.0
        const val MAX_TERMINAL_USE_DISTANCE_SQUARED = MAX_TERMINAL_USE_DISTANCE * MAX_TERMINAL_USE_DISTANCE
    }
}
