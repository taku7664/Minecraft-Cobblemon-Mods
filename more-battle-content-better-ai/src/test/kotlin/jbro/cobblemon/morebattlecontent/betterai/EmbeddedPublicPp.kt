package jbro.cobblemon.morebattlecontent.betterai

import java.util.Locale

/** Public expenditure ledger for native comparison input. No referee or private opponent state. */
internal class EmbeddedPublicPp {
    private val spent = mutableMapOf<Pair<String, String>, Int>()
    private val copied = mutableMapOf<String, MutableMap<Pair<String, String>, Int>>()
    private val lastMoves = mutableMapOf<String, String>()
    private val active = mutableMapOf<String, String>()

    /** Replayed singles protocol only; metadata callbacks must use public move rules. */
    fun observe(line: String, pressureLoss: Int = 0, maximumPp: (String) -> Int? = { null }) {
        val parts = line.split('|')
        val kind = parts.getOrNull(1) ?: return
        if (kind == "turn") { lastMoves.clear(); return }
        val actor = parts.getOrNull(2)?.takeIf { it.matches(Regex("p[12][a-z]?: .+")) } ?: return
        val side = actor.take(2)
        val identity = EmbeddedTeamInput.identity(actor)
        when (kind) {
            "switch", "drag" -> {
                active.put(side, actor)?.let(::endTransform)
                endTransform(actor)
            }
            // Illusion disclosure is not departure and must not reset copied PP.
            "replace" -> active[side] = actor
            "faint" -> {
                endTransform(actor)
                if (active[side]?.let(EmbeddedTeamInput::identity) == identity) active.remove(side)
            }
            "-transform" -> beginTransform(actor)
            "move" -> observeMove(line, pressureLoss)
            "-activate" -> {
                val move = parts.getOrNull(4)?.let(::canonical)?.takeIf(String::isNotEmpty) ?: return
                when (parts.getOrNull(3)) {
                    "move: Spite" -> parts.getOrNull(5)?.toIntOrNull()?.takeIf { it in 1..4 }
                        ?.let { lose(actor, move, it) }
                    "item: Leppa Berry" -> maximumPp(move)?.let { restore(actor, move, 10, it) }
                }
            }
        }
    }

    /** Pressure must be derived by the caller from publicly known targets/abilities at this event. */
    fun observeMove(line: String, pressureLoss: Int = 0) {
        require(pressureLoss >= 0)
        val parts = line.split('|')
        if (parts.getOrNull(1) != "move") return
        val actor = parts.getOrNull(2)?.takeIf { it.matches(Regex("p[12][a-z]?: .+")) } ?: return
        val move = parts.getOrNull(3)?.let(::canonical)?.takeIf(String::isNotEmpty) ?: return
        if (move in setOf("struggle", "recharge")) return
        val key = EmbeddedTeamInput.identity(actor)
        val source = parts.drop(5).singleOrNull { it.startsWith("[from] ") }?.removePrefix("[from] ")?.trim()
        val caller = source?.takeIf { it.startsWith("move:") }?.let(::canonical)
            ?.takeIf { it == lastMoves[key] }
        if (source != "lockedmove") {
            if (caller == null) lose(actor, move, 1)
            if (pressureLoss > 0) lose(actor, caller ?: move, pressureLoss)
        }
        lastMoves[key] = move
    }

    fun clearLastMove(ident: String) { lastMoves.remove(EmbeddedTeamInput.identity(ident)) }

    /** A successful public Transform creates a fresh copied pool, not a reset of original PP. */
    fun beginTransform(ident: String) {
        copied[EmbeddedTeamInput.identity(ident)] = mutableMapOf()
        clearLastMove(ident)
    }

    fun endTransform(ident: String) {
        copied.remove(EmbeddedTeamInput.identity(ident))
        clearLastMove(ident)
    }

    fun isTransformed(ident: String) = EmbeddedTeamInput.identity(ident) in copied

    private fun expenditure(ident: String) = copied[EmbeddedTeamInput.identity(ident)] ?: spent
    private fun capacity(ident: String, maximumPp: Int) =
        if (!isTransformed(ident) || maximumPp <= 1) maximumPp else 5

    fun remaining(ident: String, move: String, maximumPp: Int): Int {
        require(maximumPp >= 0)
        return (capacity(ident, maximumPp) - (expenditure(ident)[key(ident, move)] ?: 0)).coerceAtLeast(0)
    }

    fun lose(ident: String, move: String, amount: Int) {
        require(amount > 0)
        val key = key(ident, move)
        val losses = expenditure(ident)
        losses[key] = ((losses[key] ?: 0).toLong() + amount).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun restore(ident: String, move: String, amount: Int, maximumPp: Int) {
        require(amount > 0 && maximumPp >= 0)
        val key = key(ident, move)
        val losses = expenditure(ident)
        losses[key] = ((losses[key] ?: 0).coerceAtMost(capacity(ident, maximumPp)) - amount).coerceAtLeast(0)
    }

    private fun key(ident: String, move: String): Pair<String, String> {
        val canonicalMove = canonical(move)
        require(canonicalMove.isNotEmpty())
        return EmbeddedTeamInput.identity(ident) to canonicalMove
    }

    private fun canonical(value: String) = value.substringAfter(": ").lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
}
