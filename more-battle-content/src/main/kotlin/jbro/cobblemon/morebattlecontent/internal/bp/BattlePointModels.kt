package jbro.cobblemon.morebattlecontent.internal.bp

import java.util.UUID

@JvmInline
internal value class BattlePointSourceId(val value: String) {
    init {
        require(SOURCE_ID.matches(value)) { "Invalid Battle Point source ID: $value" }
    }
}

internal enum class BattlePointTransactionKind {
    CONTENT_REWARD,
    ADMIN_ADD,
    ADMIN_REMOVE,
    ADMIN_SET,
    SHOP_PURCHASE,
}

internal sealed interface BattlePointOperation {
    val requestedValue: Long
    val kind: BattlePointTransactionKind

    data class ContentReward(override val requestedValue: Long) : BattlePointOperation {
        override val kind = BattlePointTransactionKind.CONTENT_REWARD

        init {
            require(requestedValue > 0) { "Battle Point reward must be positive" }
        }
    }

    data class AdminAdd(override val requestedValue: Long) : BattlePointOperation {
        override val kind = BattlePointTransactionKind.ADMIN_ADD

        init {
            require(requestedValue > 0) { "Added Battle Points must be positive" }
        }
    }

    data class AdminRemove(override val requestedValue: Long) : BattlePointOperation {
        override val kind = BattlePointTransactionKind.ADMIN_REMOVE

        init {
            require(requestedValue > 0) { "Removed Battle Points must be positive" }
        }
    }

    data class AdminSet(override val requestedValue: Long) : BattlePointOperation {
        override val kind = BattlePointTransactionKind.ADMIN_SET

        init {
            require(requestedValue >= 0) { "Battle Point balance must be non-negative" }
        }
    }

    data class ShopPurchase(override val requestedValue: Long) : BattlePointOperation {
        override val kind = BattlePointTransactionKind.SHOP_PURCHASE

        init {
            require(requestedValue > 0) { "Battle Point purchase cost must be positive" }
        }
    }
}

internal data class BattlePointRequest(
    val transactionId: UUID,
    val playerId: UUID,
    val operation: BattlePointOperation,
    val sourceId: BattlePointSourceId,
    val reason: String,
) {
    init {
        require(reason.isNotBlank() && reason.length <= MAX_REASON_LENGTH) {
            "Battle Point reason must contain 1 to $MAX_REASON_LENGTH characters"
        }
    }
}

internal data class BattlePointTransaction(
    val transactionId: UUID,
    val playerId: UUID,
    val kind: BattlePointTransactionKind,
    val requestedValue: Long,
    val balanceBefore: Long,
    val balanceAfter: Long,
    val sourceId: BattlePointSourceId,
    val reason: String,
    val recordedAtEpochMillis: Long,
) {
    init {
        require(requestedValue >= 0) { "Requested Battle Point value must be non-negative" }
        require(balanceBefore >= 0 && balanceAfter >= 0) { "Battle Point balances must be non-negative" }
        require(reason.isNotBlank() && reason.length <= MAX_REASON_LENGTH) {
            "Battle Point reason must contain 1 to $MAX_REASON_LENGTH characters"
        }
        require(recordedAtEpochMillis >= 0) { "Battle Point transaction time must be non-negative" }
        require(matchesBalanceChange()) { "Battle Point transaction does not match its balance change" }
    }

    fun matches(request: BattlePointRequest): Boolean =
        transactionId == request.transactionId &&
            playerId == request.playerId &&
            kind == request.operation.kind &&
            requestedValue == request.operation.requestedValue &&
            sourceId == request.sourceId &&
            reason == request.reason

    private fun matchesBalanceChange(): Boolean = when (kind) {
        BattlePointTransactionKind.CONTENT_REWARD,
        BattlePointTransactionKind.ADMIN_ADD,
        -> requestedValue > 0 && runCatching { Math.addExact(balanceBefore, requestedValue) }.getOrNull() == balanceAfter

        BattlePointTransactionKind.ADMIN_REMOVE,
        BattlePointTransactionKind.SHOP_PURCHASE,
        -> requestedValue > 0 && balanceBefore >= requestedValue && balanceBefore - requestedValue == balanceAfter

        BattlePointTransactionKind.ADMIN_SET -> balanceAfter == requestedValue
    }
}

internal class BattlePointAccount(
    val playerId: UUID,
    val balance: Long,
    transactions: Collection<BattlePointTransaction> = emptyList(),
) {
    val transactions: List<BattlePointTransaction> = transactions.toList()

    init {
        require(balance >= 0) { "Battle Point balance must be non-negative" }
    }

    override fun equals(other: Any?): Boolean = this === other || other is BattlePointAccount &&
        playerId == other.playerId && balance == other.balance && transactions == other.transactions

    override fun hashCode(): Int = 31 * (31 * playerId.hashCode() + balance.hashCode()) + transactions.hashCode()

    override fun toString(): String =
        "BattlePointAccount(playerId=$playerId, balance=$balance, transactions=$transactions)"
}

internal enum class BattlePointApplyStatus {
    APPLIED,
    ALREADY_APPLIED,
    TRANSACTION_CONFLICT,
    INSUFFICIENT_FUNDS,
    BALANCE_OVERFLOW,
    COMMIT_REJECTED,
    UNAVAILABLE,
}

internal data class BattlePointApplyResult(
    val status: BattlePointApplyStatus,
    val balance: Long,
    val transaction: BattlePointTransaction? = null,
)

internal fun interface BattlePointAtomicApplier {
    fun applyAtomically(request: BattlePointRequest, commit: () -> Boolean): BattlePointApplyResult
}

internal const val MAX_BATTLE_POINT_HISTORY_QUERY = 100
private const val MAX_REASON_LENGTH = 256
private val SOURCE_ID = Regex("[a-z0-9][a-z0-9_.-]{0,63}:[a-z0-9][a-z0-9_./-]{0,127}")
