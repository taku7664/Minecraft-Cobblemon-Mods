package jbro.cobblemon.morebattlecontent.betterai

import java.util.Locale

/** Public expenditure ledger for native comparison input. No referee or private opponent state. */
internal class EmbeddedPublicPp {
    private val spent = mutableMapOf<Pair<String, String>, Int>()
    private val lastMoves = mutableMapOf<String, String>()

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

    fun remaining(ident: String, move: String, maximumPp: Int): Int {
        require(maximumPp >= 0)
        return (maximumPp - (spent[key(ident, move)] ?: 0)).coerceAtLeast(0)
    }

    fun lose(ident: String, move: String, amount: Int) {
        require(amount > 0)
        val key = key(ident, move)
        spent[key] = ((spent[key] ?: 0).toLong() + amount).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun restore(ident: String, move: String, amount: Int, maximumPp: Int) {
        require(amount > 0 && maximumPp >= 0)
        val key = key(ident, move)
        spent[key] = ((spent[key] ?: 0).coerceAtMost(maximumPp) - amount).coerceAtLeast(0)
    }

    private fun key(ident: String, move: String): Pair<String, String> {
        val canonicalMove = canonical(move)
        require(canonicalMove.isNotEmpty())
        return EmbeddedTeamInput.identity(ident) to canonicalMove
    }

    private fun canonical(value: String) = value.substringAfter(": ").lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
}
