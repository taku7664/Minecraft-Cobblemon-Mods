package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonActionConstraintView

/** Singles public-message lifecycle; neither requests nor hidden engine state enter this tracker. */
internal class EmbeddedPublicActionConstraints {
    private val states = mutableMapOf<String, BattlePokemonActionConstraintView>()
    private val lastMoves = mutableMapOf<String, String>()
    private val active = mutableMapOf<String, String>()

    fun forPokemon(ident: String): BattlePokemonActionConstraintView =
        states[EmbeddedTeamInput.identity(ident)] ?: BattlePokemonActionConstraintView.empty()

    fun observe(line: String) {
        val parts = line.split('|')
        val kind = parts.getOrNull(1) ?: return
        val actor = parts.getOrNull(2)?.takeIf { it.matches(Regex("p[12][a-z]?: .+")) } ?: return
        val key = EmbeddedTeamInput.identity(actor)
        if (kind in setOf("switch", "drag", "replace")) {
            active.put(actor.take(2), key)?.let { states.remove(it); lastMoves.remove(it) }
            states.remove(key); lastMoves.remove(key)
            return
        }
        val effect = parts.getOrNull(3)?.substringAfter(": ")?.lowercase()?.filter(Char::isLetterOrDigit)
        if (kind == "move") {
            if (!effect.isNullOrEmpty()) lastMoves[key] = effect
            return
        }
        val previous = forPokemon(actor)
        val updated = when (kind) {
            "-start" -> when (effect) {
                "taunt" -> previous.copy(taunted = true)
                "encore" -> previous.copy(encoreMoveId = lastMoves[key] ?: return)
                else -> return
            }
            "-end" -> when {
                effect == "taunt" -> previous.copy(taunted = false)
                effect == "encore" -> previous.copy(encoreMoveId = null)
                effect in PARTIAL_TRAPS || parts.any { it == "[partiallytrapped]" } -> previous.copy(trapped = false)
                else -> return
            }
            "-mustrecharge" -> previous.copy(mustRecharge = true)
            "cant" -> when (effect) {
                "recharge" -> previous.copy(mustRecharge = false)
                "trapped" -> previous.copy(trapped = true)
                else -> return
            }
            "-activate" -> if (effect == "trapped" || effect in PARTIAL_TRAPS && parts.any { it.startsWith("[of] ") }) {
                previous.copy(trapped = true)
            } else return
            else -> return
        }
        states[key] = updated
    }

    private companion object {
        val PARTIAL_TRAPS = setOf("bind", "clamp", "firespin", "infestation", "magmastorm",
            "sandtomb", "snaptrap", "thundercage", "whirlpool", "wrap")
    }
}
