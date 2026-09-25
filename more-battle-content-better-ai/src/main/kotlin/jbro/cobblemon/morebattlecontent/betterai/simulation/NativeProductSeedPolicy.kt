package jbro.cobblemon.morebattlecontent.betterai.simulation

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/** Stable common-random-number seeds shared by planning, search and session replay. */
internal object NativeProductSeedPolicy {
    fun derive(battleId: UUID, hypothesisId: String, randomSampleIndex: Int): List<Int> {
        require(hypothesisId.isNotBlank())
        require(randomSampleIndex >= 0)
        val digest = MessageDigest.getInstance("SHA-256").digest(
            "$POLICY_ID|$battleId|$hypothesisId|$randomSampleIndex".toByteArray(StandardCharsets.UTF_8),
        )
        val values = (0 until SEED_WORDS).map { index ->
            val offset = index * 2
            ((digest[offset].toInt() and 0xff) shl 8) or (digest[offset + 1].toInt() and 0xff)
        }
        return if (values.all { it == 0 }) listOf(1, 0, 0, 0) else values
    }

    const val POLICY_ID = "native-product-seed-v1"
    private const val SEED_WORDS = 4
}
