package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import java.util.UUID

/** Explicit spectator-message arguments only; adjacent attacks are not evidence of causality. */
internal object EmbeddedPublicMoveOutcomes {
    fun read(line: String, turn: Int, sequence: Long, resolve: (String) -> UUID?): BattleObservedEventView? {
        val p = line.split('|')
        fun id(index: Int): String? = p.getOrNull(index)?.trim()?.takeUnless { it.startsWith('[') }
            ?.substringAfter(": ")?.lowercase()?.filter(Char::isLetterOrDigit)?.takeIf { it.isNotEmpty() }
        fun pokemon(index: Int) = p.getOrNull(index)?.let(resolve)
        var actor: UUID? = null
        var target: UUID? = null
        var move: String? = null
        var effect: String? = null
        var count: Int? = null
        val kind = when (p.getOrNull(1)) {
            "move" -> {
                if ("[miss]" !in p.drop(5)) return null
                actor = pokemon(2); target = pokemon(4); move = id(3)
                BattleMoveOutcomeKind.MISSED
            }
            "-miss" -> { actor = pokemon(2); target = pokemon(3); BattleMoveOutcomeKind.MISSED }
            "-fail" -> { target = pokemon(2); move = id(3); BattleMoveOutcomeKind.FAILED }
            "-block" -> {
                target = pokemon(2); effect = id(3); move = id(4); actor = pokemon(5)
                BattleMoveOutcomeKind.BLOCKED
            }
            "-notarget" -> { actor = pokemon(2); BattleMoveOutcomeKind.NO_TARGET }
            "cant" -> { actor = pokemon(2); effect = id(3); move = id(4); BattleMoveOutcomeKind.CANNOT_ACT }
            "-crit" -> { target = pokemon(2); BattleMoveOutcomeKind.CRITICAL_HIT }
            "-supereffective" -> { target = pokemon(2); BattleMoveOutcomeKind.SUPER_EFFECTIVE }
            "-resisted" -> { target = pokemon(2); BattleMoveOutcomeKind.RESISTED }
            "-immune" -> { target = pokemon(2); BattleMoveOutcomeKind.IMMUNE }
            "-hitcount" -> {
                count = p.getOrNull(3)?.toIntOrNull()?.takeIf { it > 0 } ?: return null
                target = pokemon(2); BattleMoveOutcomeKind.HIT_COUNT
            }
            "-activate" -> {
                if (id(3) != "substitute" || "[damage]" !in p.drop(4)) return null
                target = pokemon(2); effect = "substitute"; BattleMoveOutcomeKind.SUBSTITUTE_DAMAGED
            }
            "-singleturn" -> {
                if (id(3) != "protect") return null
                target = pokemon(2); effect = "protect"; BattleMoveOutcomeKind.PROTECTION_STARTED
            }
            else -> return null
        }
        return BattleObservedEventView(sequence, turn, BattleObservedEventKind.MOVE_OUTCOME,
            actorPokemonId = actor, targetPokemonIds = listOfNotNull(target),
            moveOutcome = BattleMoveOutcomeView(kind, move, effect, count))
    }

    fun duplicatesMiss(previous: BattleObservedEventView?, next: BattleObservedEventView): Boolean {
        val before = previous?.moveOutcome ?: return false
        val after = next.moveOutcome ?: return false
        return before.kind == BattleMoveOutcomeKind.MISSED && after.kind == BattleMoveOutcomeKind.MISSED &&
            previous.turn == next.turn && previous.actorPokemonId == next.actorPokemonId &&
            previous.targetPokemonIds == next.targetPokemonIds && before.publicEffectId == after.publicEffectId &&
            before.hitCount == after.hitCount && (before.moveId == after.moveId || after.moveId == null)
    }
}
