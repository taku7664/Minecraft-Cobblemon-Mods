package jbro.cobblemon.morebattlecontent.internal.bp

import java.util.UUID

internal class BattlePointStore(
    initialAccounts: Collection<BattlePointAccount> = emptyList(),
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : BattlePointAtomicApplier {
    private val accounts = LinkedHashMap<UUID, MutableAccount>()
    private val pendingTransactionsByPlayer = HashMap<UUID, PendingTransaction>()

    init {
        initialAccounts.forEach { account ->
            require(accounts.put(account.playerId, account.toMutableValidated()) == null) {
                "Duplicate Battle Point account: ${account.playerId}"
            }
        }
    }

    @Synchronized
    fun balance(playerId: UUID): Long = accounts[playerId]?.balance ?: 0L

    @Synchronized
    fun history(playerId: UUID, limit: Int): List<BattlePointTransaction> {
        require(limit in 1..MAX_BATTLE_POINT_HISTORY_QUERY) {
            "Battle Point history limit must be between 1 and $MAX_BATTLE_POINT_HISTORY_QUERY"
        }
        return accounts[playerId]?.transactions?.asReversed()?.take(limit)?.toList() ?: emptyList()
    }

    @Synchronized
    fun apply(request: BattlePointRequest): BattlePointApplyResult = applyAtomically(request) { true }

    override fun applyAtomically(
        request: BattlePointRequest,
        commit: () -> Boolean,
    ): BattlePointApplyResult {
        val preparation = synchronized(this) { prepareTransaction(request) }
        if (preparation is TransactionPreparation.Rejected) return preparation.result
        val pending = (preparation as TransactionPreparation.Prepared).pending

        var finalized = false
        try {
            val committed = commit()
            return synchronized(this) {
                check(pendingTransactionsByPlayer[request.playerId] === pending) {
                    "Battle Point transaction reservation was lost for ${request.playerId}"
                }
                pendingTransactionsByPlayer.remove(request.playerId)
                finalized = true
                if (!committed) {
                    BattlePointApplyResult(BattlePointApplyStatus.COMMIT_REJECTED, pending.transaction.balanceBefore)
                } else {
                    record(pending.transaction)
                    BattlePointApplyResult(
                        BattlePointApplyStatus.APPLIED,
                        pending.transaction.balanceAfter,
                        pending.transaction,
                    )
                }
            }
        } finally {
            if (!finalized) {
                synchronized(this) {
                    pendingTransactionsByPlayer.remove(request.playerId, pending)
                }
            }
        }
    }

    private fun prepareTransaction(request: BattlePointRequest): TransactionPreparation {
        val account = accounts[request.playerId]
        val existing = account?.transactionsById?.get(request.transactionId)
        if (existing != null) {
            val result = if (existing.matches(request)) {
                BattlePointApplyResult(BattlePointApplyStatus.ALREADY_APPLIED, existing.balanceAfter, existing)
            } else {
                BattlePointApplyResult(BattlePointApplyStatus.TRANSACTION_CONFLICT, account.balance)
            }
            return TransactionPreparation.Rejected(result)
        }

        pendingTransactionsByPlayer[request.playerId]?.let { pending ->
            val status = if (pending.transaction.transactionId == request.transactionId &&
                !pending.transaction.matches(request)
            ) {
                BattlePointApplyStatus.TRANSACTION_CONFLICT
            } else {
                BattlePointApplyStatus.IN_PROGRESS
            }
            return TransactionPreparation.Rejected(
                BattlePointApplyResult(status, account?.balance ?: 0L),
            )
        }

        val balanceBefore = account?.balance ?: 0L
        val balanceAfter = calculateBalance(balanceBefore, request.operation)
            ?: return TransactionPreparation.Rejected(
                BattlePointApplyResult(
                    if (request.operation is BattlePointOperation.ContentReward || request.operation is BattlePointOperation.AdminAdd) {
                        BattlePointApplyStatus.BALANCE_OVERFLOW
                    } else {
                        BattlePointApplyStatus.INSUFFICIENT_FUNDS
                    },
                    balanceBefore,
                ),
            )
        val recordedAt = currentTimeMillis()
        require(recordedAt >= 0) { "Battle Point transaction time must be non-negative" }
        val transaction = BattlePointTransaction(
            transactionId = request.transactionId,
            playerId = request.playerId,
            kind = request.operation.kind,
            requestedValue = request.operation.requestedValue,
            balanceBefore = balanceBefore,
            balanceAfter = balanceAfter,
            sourceId = request.sourceId,
            reason = request.reason,
            recordedAtEpochMillis = recordedAt,
        )
        val pending = PendingTransaction(transaction)
        pendingTransactionsByPlayer[request.playerId] = pending
        return TransactionPreparation.Prepared(pending)
    }

    private fun record(transaction: BattlePointTransaction) {
        val target = accounts[transaction.playerId] ?: MutableAccount().also { accounts[transaction.playerId] = it }
        check(target.balance == transaction.balanceBefore) {
            "Battle Point balance changed while transaction was reserved for ${transaction.playerId}"
        }
        target.balance = transaction.balanceAfter
        target.transactions += transaction
        target.transactionsById[transaction.transactionId] = transaction
    }

    @Synchronized
    fun allAccounts(): List<BattlePointAccount> = accounts.entries
        .sortedBy { it.key.toString() }
        .map { (playerId, account) -> BattlePointAccount(playerId, account.balance, account.transactions) }

    private fun calculateBalance(balance: Long, operation: BattlePointOperation): Long? = when (operation) {
        is BattlePointOperation.ContentReward -> addOrNull(balance, operation.requestedValue)
        is BattlePointOperation.AdminAdd -> addOrNull(balance, operation.requestedValue)
        is BattlePointOperation.AdminRemove -> subtractOrNull(balance, operation.requestedValue)
        is BattlePointOperation.ShopPurchase -> subtractOrNull(balance, operation.requestedValue)
        is BattlePointOperation.AdminSet -> operation.requestedValue
    }

    private fun addOrNull(balance: Long, amount: Long): Long? =
        try {
            Math.addExact(balance, amount)
        } catch (_: ArithmeticException) {
            null
        }

    private fun subtractOrNull(balance: Long, amount: Long): Long? =
        if (balance >= amount) balance - amount else null

    private fun BattlePointAccount.toMutableValidated(): MutableAccount {
        var expectedBalance = 0L
        val transactionsById = LinkedHashMap<UUID, BattlePointTransaction>()
        transactions.forEach { transaction ->
            require(transaction.playerId == playerId) { "Battle Point transaction belongs to another player" }
            require(transaction.balanceBefore == expectedBalance) { "Broken Battle Point balance chain for $playerId" }
            require(transactionsById.put(transaction.transactionId, transaction) == null) {
                "Duplicate Battle Point transaction ID for $playerId: ${transaction.transactionId}"
            }
            expectedBalance = transaction.balanceAfter
        }
        require(expectedBalance == balance) { "Battle Point account balance does not match its transaction history" }
        return MutableAccount(balance, transactions.toMutableList(), transactionsById)
    }

    private class MutableAccount(
        var balance: Long = 0,
        val transactions: MutableList<BattlePointTransaction> = mutableListOf(),
        val transactionsById: MutableMap<UUID, BattlePointTransaction> = LinkedHashMap(),
    )

    private class PendingTransaction(val transaction: BattlePointTransaction)

    private sealed interface TransactionPreparation {
        data class Prepared(val pending: PendingTransaction) : TransactionPreparation
        data class Rejected(val result: BattlePointApplyResult) : TransactionPreparation
    }
}
