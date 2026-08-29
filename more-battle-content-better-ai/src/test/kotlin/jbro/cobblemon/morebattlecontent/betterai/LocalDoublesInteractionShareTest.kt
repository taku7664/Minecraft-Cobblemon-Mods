package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import org.junit.jupiter.api.Test

/**
 * How many doubles combinations actually need to be scored as a combination.
 *
 * The joint action exists for one reason: two slots acting in the same turn can interact, and a
 * scorer that looks at them separately cannot see it. That is the defect pokeemerald-expansion
 * #10135 describes - two slots independently deciding to finish the same target and wasting a turn -
 * and it is the reason PokeRogue's cheaper per-slot model has the blind spot it has.
 *
 * But interaction is a property of the pair, not of doubles. Two attacks aimed at different targets,
 * neither of them spread, neither of them a redirect, do not interact at all: the pair is worth
 * exactly the sum of its halves. If those pairs are the majority, then the expensive part of the
 * search is being spent on combinations that a decomposition would price identically, and the joint
 * enumeration only has to survive for the minority that genuinely couples.
 *
 * This measures that share. It asserts nothing about what to do with it - the number is what decides
 * whether a decomposition is worth building at all.
 */
class LocalDoublesInteractionShareTest {
    @Test
    fun `report how many joint actions actually couple`() {
        val positions = LocalSelfPlayMeasurement
            .definitions(4, 20260829, BattleFormat.DOUBLE)
            .flatMap { definition ->
                val recorded = mutableListOf<BattleDecisionContext>()
                LocalTacticalScenarioBattle.run(definition, maximumTurns = 12, recordedContexts = recorded)
                recorded
            }
            .filter { it.state.format == BattleFormat.DOUBLE && it.candidates.size > 1 }
            .take(40)

        var joints = 0
        var coupled = 0
        val reasons = linkedMapOf("same target" to 0, "spread" to 0, "redirect" to 0, "switch" to 0)
        positions.forEach { context ->
            context.candidates.forEach { candidate ->
                val parts = candidate.componentActions.ifEmpty { listOf(candidate) }
                if (parts.size < 2) return@forEach
                joints++
                val why = couplingReasons(parts)
                if (why.isNotEmpty()) {
                    coupled++
                    why.forEach { reasons[it] = reasons.getValue(it) + 1 }
                }
            }
        }
        val share = if (joints == 0) 0.0 else coupled.toDouble() / joints * 100
        println("positions=${positions.size} joints=$joints coupled=$coupled (${String.format("%.1f", share)}%)")
        reasons.forEach { (reason, count) -> println("  $reason: $count") }
    }

    /**
     * Why this pair cannot be priced as the sum of its halves.
     *
     * Deliberately generous - anything that might couple counts as coupling, because the number is
     * being used to decide whether a decomposition is safe, and a decomposition that misses a real
     * interaction is the whole failure being avoided.
     */
    private fun couplingReasons(parts: List<BattleActionCandidate>): List<String> {
        val reasons = mutableListOf<String>()
        val targets = parts.flatMap { it.targets }
        if (targets.size != targets.distinct().size) reasons += "same target"
        if (parts.any { it.moveDetails?.targetPattern in SPREAD }) reasons += "spread"
        if (parts.any { canonical(it.moveId) in REDIRECTING }) reasons += "redirect"
        // A switch changes who the partner's move is even aimed at, so the pair is coupled by it.
        if (parts.any { it.kind == BattleActionKind.SWITCH }) reasons += "switch"
        return reasons
    }

    private fun canonical(value: String?): String? =
        value?.substringAfter(':')?.lowercase()?.filter(Char::isLetterOrDigit)

    private companion object {
        val SPREAD = setOf(
            BattleMoveTargetPattern.ALL_OPPONENTS,
            BattleMoveTargetPattern.ALL_ADJACENT,
            BattleMoveTargetPattern.ALL_ACTIVE,
            BattleMoveTargetPattern.ALL_ALLIES,
        )
        val REDIRECTING = setOf("followme", "ragepowder", "spotlight")
    }
}
