package jbro.cobblemon.mcc.internal.bp.recovery

import jbro.cobblemon.mcc.internal.bp.*
import jbro.cobblemon.mcc.internal.bp.shop.*
import java.util.UUID

/** Serial execution and a durable boundary before every economic side effect. */
internal class CompensationService(
    private val journal: CompensationJournal,
    private val available: () -> Boolean,
    private val receipt: (UUID, UUID) -> BattlePointTransaction?,
    private val apply: (BattlePointRequest) -> BattlePointApplyResult,
    private val historyHead: (UUID) -> UUID?,
    private val operatorChanged: (CompensationEntry) -> Boolean,
    private val delivery: BattlePointShopDelivery,
    private val now: () -> Long = System::currentTimeMillis,
) {
    enum class Status { APPLIED, ALREADY_APPLIED, WAITING, OPERATOR, REJECTED }
    data class Result(val status: Status, val note: String, val amount: Long = 0, val bpResult: BattlePointApplyResult? = null)

    @Synchronized
    fun reward(request: BattlePointRequest): Result = original(request, emptyList())

    @Synchronized
    fun purchase(request: BattlePointRequest, grants: List<BattlePointShopGrant>): Result = original(request, grants)

    private fun original(request: BattlePointRequest, grants: List<BattlePointShopGrant>): Result {
        var entry = journal.read(request.playerId, request.transactionId)
        val fresh = entry == null
        if (entry != null && !entry.matches(request, grants)) return Result(Status.OPERATOR, "원거래 번호의 내용이 달라요")
        if (entry == null) {
            if (!available()) {
                // A reward is already authorized even while the BP store is unavailable. A purchase has not been charged.
                if (request.operation !is BattlePointOperation.ContentReward) return Result(Status.REJECTED, "BP 저장소를 읽지 못했어요")
            } else receipt(request.playerId, request.transactionId)?.let { existing ->
                return if (existing.matches(request)) Result(Status.ALREADY_APPLIED, "기존 거래는 다시 지급하지 않아요", bpResult =
                    BattlePointApplyResult(BattlePointApplyStatus.ALREADY_APPLIED, existing.balanceAfter, existing))
                else Result(Status.OPERATOR, "기존 거래의 내용이 달라요")
            }
            // Preserve the old shop behavior: an initially full/offline inventory never incurs a charge.
            if (grants.isNotEmpty() && delivery.prepare(request.playerId, grants) == null) {
                return Result(Status.REJECTED, "상품을 받을 공간이 없어 결제하지 않았어요")
            }
            entry = CompensationEntry(request.playerId, request.transactionId, request.operation.kind,
                request.operation.requestedValue, request.sourceId.value, request.reason, grants.toList(), now(),
                baseline = if (available()) historyHead(request.playerId) else null, baselineKnown = available()).validate()
            journal.write(entry)
        }
        return resume(entry, recovering = !fresh)
    }

    @Synchronized
    fun recover(player: UUID, transaction: UUID): Result {
        val entry = journal.read(player, transaction) ?: return Result(Status.OPERATOR, "원래 지급 건의 기록이 없어요")
        return resume(entry, recovering = true)
    }

    @Synchronized
    fun entries(player: UUID) = journal.list(player)

    @Synchronized
    fun hold(player: UUID, transaction: UUID): Boolean {
        val entry = journal.read(player, transaction) ?: return false
        journal.write(entry.copy(phase = CompensationPhase.OPERATOR, note = "운영자가 원거래의 자동 복구를 보류했어요"))
        return true
    }

    private fun operator(entry: CompensationEntry, note: String): Result {
        journal.write(entry.copy(phase = CompensationPhase.OPERATOR, note = note))
        return Result(Status.OPERATOR, note)
    }

    private fun resume(original: CompensationEntry, recovering: Boolean): Result {
        var entry = original
        if (entry.phase == CompensationPhase.OPERATOR) return Result(Status.OPERATOR, entry.note)
        if (!available()) return Result(Status.WAITING, "BP 저장소를 읽지 못해 지급하지 않았어요")
        val existing = receipt(entry.player, entry.transaction)
        if (existing != null && !existing.matches(entry.request())) return operator(entry, "원거래와 BP 내역이 서로 달라요")
        if (entry.phase == CompensationPhase.DELIVERING) return operator(entry, "상품 전달을 시작했지만 완료 여부가 불명확해요")
        if (entry.phase == CompensationPhase.COMPLETE) {
            return if (existing == null) operator(entry, "완료 기록과 BP 저장 내역이 일치하지 않아요")
            else Result(Status.ALREADY_APPLIED, "이미 처리된 원거래예요", bpResult =
                BattlePointApplyResult(BattlePointApplyStatus.ALREADY_APPLIED, existing.balanceAfter, existing))
        }
        if (entry.phase == CompensationPhase.APPLYING_BP) {
            if (existing == null) return operator(entry, "BP 처리 중 중단되어 결과를 확인할 수 없어요")
            entry = entry.copy(phase = if (entry.grants.isEmpty()) CompensationPhase.COMPLETE else CompensationPhase.CHARGED)
            journal.write(entry)
            if (entry.phase == CompensationPhase.COMPLETE) return Result(Status.ALREADY_APPLIED, "BP 지급 내역을 확인했어요", bpResult =
                BattlePointApplyResult(BattlePointApplyStatus.ALREADY_APPLIED, existing.balanceAfter, existing))
        }
        if (recovering && operatorChanged(entry)) return operator(entry, "이후 운영자 BP 조정이 있어 별도 보상 여부를 확인해야 해요")
        if (entry.phase == CompensationPhase.READY) {
            if (existing != null) return operator(entry, "대기 기록과 이미 존재하는 거래를 함께 확인해야 해요")
            if (recovering && entry.grants.isNotEmpty()) return operator(entry, "BP 차감이 확인되지 않아 추가 결제하지 않아요")
            journal.write(entry.copy(phase = CompensationPhase.APPLYING_BP))
            val applied = try { apply(entry.request()) } catch (_: RuntimeException) {
                // Never reset to READY after invoking a mutator whose result was not obtained.
                return operator(entry, "BP 실행 결과가 불명확해요")
            }
            if (applied.status != BattlePointApplyStatus.APPLIED && applied.status != BattlePointApplyStatus.ALREADY_APPLIED) {
                if (applied.status == BattlePointApplyStatus.UNAVAILABLE || applied.status == BattlePointApplyStatus.BALANCE_OVERFLOW ||
                    applied.status == BattlePointApplyStatus.INSUFFICIENT_FUNDS) {
                    journal.write(entry.copy(phase = CompensationPhase.READY, note = "BP 변경이 거절됐어요"))
                    return Result(Status.WAITING, "BP 변경이 거절되어 지급하지 않았어요", bpResult = applied)
                }
                return operator(entry, "원거래 BP 처리 상태를 확인해야 해요: ${applied.status}")
            }
            entry = entry.copy(phase = if (entry.grants.isEmpty()) CompensationPhase.COMPLETE else CompensationPhase.CHARGED)
            journal.write(entry)
            if (entry.grants.isEmpty()) return Result(Status.APPLIED, "기록된 원래 BP를 지급했어요", entry.amount, applied)
        }
        // Only a persisted, matching charge with no previous delivery attempt may reach this point.
        if (receipt(entry.player, entry.transaction)?.matches(entry.request()) != true) return operator(entry, "원래 결제 내역을 확인하지 못했어요")
        val plan = try { delivery.prepare(entry.player, entry.grants) } catch (_: RuntimeException) {
            return Result(Status.WAITING, "상품 전달 준비에 실패해 전달을 시작하지 않았어요")
        } ?: return Result(Status.WAITING, "접속 상태나 인벤토리 공간을 확인해 주세요. 전달은 시작하지 않았어요")
        journal.write(entry.copy(phase = CompensationPhase.DELIVERING))
        val committed = try { plan.commit() } catch (_: RuntimeException) { false }
        if (!committed) {
            runCatching { plan.rollback() }
            return operator(entry, "상품 전달이 실패했거나 일부 전달됐을 수 있어요")
        }
        journal.write(entry.copy(phase = CompensationPhase.COMPLETE))
        return Result(Status.APPLIED, "원래 결제한 상품을 전달했어요", entry.amount)
    }
}
