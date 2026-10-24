package jbro.cobblemon.mcc.api.support

import jbro.cobblemon.mcc.internal.bp.BattlePointTransactionKind
import jbro.cobblemon.mcc.internal.bp.recovery.CompensationRuntime
import jbro.cobblemon.mcc.internal.bp.recovery.CompensationService
import net.minecraft.server.MinecraftServer
import java.util.UUID

/** The caller supplies only the owner and original identity/time window, never an amount or a grant. */
object MccCompensation {
    enum class Kind { REWARD, SHOP }
    data class Result(val handled: Boolean, val changed: Boolean, val message: String)

    /** Call before manual grants outside the BP ledger, such as /give, so the original cannot later auto-resume. */
    fun hold(server: MinecraftServer, playerId: UUID, transaction: UUID): Boolean {
        check(server.isSameThread)
        return CompensationRuntime.service(server).hold(playerId, transaction)
    }

    fun snapshot(server: MinecraftServer, playerId: UUID): com.google.gson.JsonObject {
        check(server.isSameThread)
        return try {
            val all = CompensationRuntime.service(server).entries(playerId).sortedByDescending { it.createdAt }
            com.google.gson.JsonObject().apply {
                addProperty("truncated", all.size > 20)
                add("entries", com.google.gson.JsonArray().apply { all.take(20).forEach { entry ->
                    add(com.google.gson.JsonObject().apply {
                        addProperty("transaction_id", entry.transaction.toString())
                        addProperty("kind", entry.kind.name)
                        addProperty("amount", entry.amount)
                        addProperty("created_at_epoch_ms", entry.createdAt)
                        addProperty("phase", entry.phase.name)
                        addProperty("note", entry.note)
                    })
                } })
            }
        } catch (_: Exception) { com.google.gson.JsonObject().apply { addProperty("unavailable", true) } }
    }

    internal fun candidates(entries: List<jbro.cobblemon.mcc.internal.bp.recovery.CompensationEntry>, playerId: UUID,
        kind: Kind, from: Long, to: Long, transaction: UUID?): List<jbro.cobblemon.mcc.internal.bp.recovery.CompensationEntry> {
        val expected = if (kind == Kind.REWARD) BattlePointTransactionKind.CONTENT_REWARD else BattlePointTransactionKind.SHOP_PURCHASE
        return entries.filter { it.player == playerId && it.kind == expected &&
            (if (transaction != null) it.transaction == transaction else it.createdAt in from..to) }
    }

    fun resolve(server: MinecraftServer, playerId: UUID, kind: Kind, from: Long, to: Long, transaction: UUID? = null): Result {
        check(server.isSameThread)
        return try {
            val service = CompensationRuntime.service(server)
            val candidates = candidates(service.entries(playerId), playerId, kind, from, to, transaction)
            if (candidates.size != 1) return Result(false, false, if (candidates.isEmpty())
                "원래 지급 건을 확인하지 못했어요. 과거 기록이나 다른 경로의 보상은 운영자가 확인해야 해요."
                else "관련 거래가 여러 건이라 임의로 복구하지 않았어요. 원래 거래 번호를 알려 주세요.")
            val original = candidates.single()
            val result = service.recover(playerId, original.transaction)
            val handled = result.status == CompensationService.Status.APPLIED ||
                (kind == Kind.REWARD && result.status == CompensationService.Status.ALREADY_APPLIED)
            Result(handled, result.status == CompensationService.Status.APPLIED,
                (if (kind == Kind.SHOP && result.status == CompensationService.Status.ALREADY_APPLIED)
                    "이 구매는 전달 완료 기록이 있어 다시 지급하지 않았어요. 현재 미수령 여부는 관리자 확인이 필요해요."
                else result.note) + " (거래 ${original.transaction})")
        } catch (_: Exception) {
            Result(false, false, "원거래 기록을 읽거나 저장하지 못해 자동 복구를 중단했어요. 지급 여부는 관리자 확인이 필요해요.")
        }
    }
}
