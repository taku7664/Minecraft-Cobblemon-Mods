package jbro.cobblemon.morebattlecontent.betterai.simulation

import java.util.Locale
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate

/**
 * Which battle mechanics the live battle actually permits, as seen through the product candidates.
 *
 * The native sandbox runs the server's patched Showdown rules. Mega Showdown's patch lets any side
 * that has not yet Dynamaxed request Dynamax in generation 9, while the live battle is restricted on
 * the Cobblemon/MBC side (a managed battle offers only its selected mechanics). The sandbox cannot see
 * that restriction, so without this filter it invents mechanic branches the live battle forbids: the
 * ally root gains actions no product candidate matches, and the opponent gains fictional replies.
 *
 * Managed battles apply the same mechanic rules to both sides, so the set observed on the AI's own
 * candidates also bounds the opponent. It only grows during a battle: a mechanic stops being offered
 * once it is spent, but it stays permitted for the opponent.
 */
internal object NativeMechanicAllowance {
    fun canonical(mechanicId: String): String = when (
        val id = mechanicId.substringAfter(':').lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
    ) {
        "mega", "megaevolution" -> "mega"
        "dynamax", "dmax" -> "dynamax"
        "tera", "terastallize", "terastallization" -> "tera"
        else -> id
    }

    fun offered(candidates: List<BattleActionCandidate>): Set<String> =
        candidates.asSequence()
            .flatMap { candidate -> sequenceOf(candidate) + candidate.componentActions.asSequence() }
            .mapNotNull { it.mechanic?.mechanicId }
            .map(::canonical)
            .toSortedSet()

    /** Null keeps an unknown allowance unrestricted rather than silently forbidding every mechanic. */
    fun merge(previous: Set<String>?, candidates: List<BattleActionCandidate>): Set<String> =
        (previous.orEmpty() + offered(candidates)).toSortedSet()
}
