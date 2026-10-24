package jbro.cobblemon.policy.support

import jbro.cobblemon.mcc.api.support.MccCompensation
import net.minecraft.server.MinecraftServer
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The AI supplies neither amounts nor commands; the core validates the original persisted entitlement. */
internal object InquiryCompensation {
    data class Outcome(val handled: Boolean, val changed: Boolean, val message: String)
    private val UUID_TEXT = Regex("(?i)(?<![a-z0-9])[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?![a-z0-9])")

    fun identities(text: String): List<UUID> = UUID_TEXT.findAll(text).map { UUID.fromString(it.value) }.distinct().toList()

    fun attempt(server: MinecraftServer, inquiry: Inquiry, category: InquiryVerdict.Category, before: Long, after: Long): Outcome {
        val ids = identities(inquiry.reason)
        if (ids.size > 1) return Outcome(false, false, "거래 번호가 여러 개여서 자동 복구하지 않았어요. 한 거래씩 확인이 필요해요.")
        val future = CompletableFuture<Outcome>()
        val mayStart = AtomicBoolean(true)
        server.execute {
            if (!mayStart.compareAndSet(true, false)) return@execute
            try {
                val kind = if (category == InquiryVerdict.Category.REWARD) MccCompensation.Kind.REWARD else MccCompensation.Kind.SHOP
                val result = MccCompensation.resolve(server, inquiry.playerId, kind,
                    inquiry.at - before * 60_000, inquiry.at + after * 60_000, ids.singleOrNull())
                future.complete(Outcome(result.handled, result.changed, result.message))
            } catch (_: Exception) {
                future.complete(Outcome(false, false, "원거래 처리 결과를 확인하지 못해 관리자 확인이 필요해요."))
            } catch (failure: LinkageError) {
                jbro.cobblemon.policy.JbroPolicy.LOGGER.warn("MCC compensation API is unavailable; both server mods need compatible versions", failure)
                future.complete(Outcome(false, false, "서버의 복구 기능을 사용할 수 없어 관리자 확인이 필요해요."))
            }
        }
        return try { future.get(10, TimeUnit.SECONDS) } catch (_: Exception) {
            // A queued mutation cannot start after we report a timeout. An already running one keeps its journal guard.
            val cancelledBeforeStart = mayStart.compareAndSet(true, false)
            Outcome(false, false, if (cancelledBeforeStart) "서버 확인이 지연되어 자동 복구를 시작하지 않았어요."
                else "서버의 복구 처리 결과를 아직 확인하지 못했어요. 추가 지급 전 관리자 확인이 필요해요.")
        }
    }
}
