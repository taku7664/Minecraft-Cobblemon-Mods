package jbro.cobblemon.morebattlecontent.internal.bp

import java.util.UUID

internal class BattlePointStore(
    initialAccounts: Collection<BattlePointAccount> = emptyList(),
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : BattlePointAtomicApplier {
    private val accounts = LinkedHashMap<UUID, MutableAccount>()

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

    @Synchronized
    override fun applyAtomically(
        request: BattlePointRequest,
        commit: () -> Boolean,
    ): BattlePointApplyResult {
        val account = accounts[request.playerId]
        val existing = account?.transactionsById?.get(request.transactionId)
        if (existing != null) {
            return if (existing.matches(request)) {
                BattlePointApplyResult(BattlePointApplyStatus.ALREADY_APPLIED, existing.balanceAfter, existing)
            } else {
                BattlePointApplyResult(BattlePointApplyStatus.TRANSACTION_CONFLICT, account.balance)
            }
        }

        val balanceBefore = account?.balance ?: 0L
        val balanceAfter = calculateBalance(balanceBefore, request.operation)
            ?: return BattlePointApplyResult(
                if (request.operation is BattlePointOperation.ContentReward || request.operation is BattlePointOperation.AdminAdd) {
                    BattlePointApplyStatus.BALANCE_OVERFLOW
                } else {
                    BattlePointApplyStatus.INSUFFICIENT_FUNDS
                },
                balanceBefore,
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
        if (!commit()) {
            return BattlePointApplyResult(BattlePointApplyStatus.COMMIT_REJECTED, balanceBefore)
        }
        val target = account ?: MutableAccount().also { accounts[request.playerId] = it }
        target.balance = balanceAfter
        target.transactions += transaction
        target.transactionsById[transaction.transactionId] = transaction
        return BattlePointApplyResult(BattlePointApplyStatus.APPLIED, balanceAfter, transaction)
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
}
