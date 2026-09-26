package jbro.cobblemon.morebattlecontent.api.rewards

import java.util.UUID
import net.minecraft.server.MinecraftServer
import jbro.cobblemon.morebattlecontent.internal.bp.*

/** Durable transaction identities must be reused verbatim after an uncertain result. */
object BattlePointRewards {
    enum class Status { APPLIED, ALREADY_APPLIED, CONFLICT, UNAVAILABLE, REJECTED }
    data class Result(val status: Status, val balance: Long) {
        val accepted: Boolean get() = status == Status.APPLIED || status == Status.ALREADY_APPLIED
    }

    fun balance(server: MinecraftServer, playerId: UUID): Long = BattlePointService.balance(server, playerId)

    fun award(server: MinecraftServer, playerId: UUID, transactionId: UUID, amount: Long, sourceId: String, reason: String): Result {
        check(server.isSameThread) { "Battle Point mutations require the server thread" }
        val result = BattlePointService.apply(server, BattlePointRequest(
            transactionId, playerId, BattlePointOperation.ContentReward(amount), BattlePointSourceId(sourceId), reason,
        ))
        return Result(when (result.status) {
            BattlePointApplyStatus.APPLIED -> Status.APPLIED
            BattlePointApplyStatus.ALREADY_APPLIED -> Status.ALREADY_APPLIED
            BattlePointApplyStatus.TRANSACTION_CONFLICT -> Status.CONFLICT
            BattlePointApplyStatus.UNAVAILABLE -> Status.UNAVAILABLE
            else -> Status.REJECTED
        }, result.balance)
    }
}
