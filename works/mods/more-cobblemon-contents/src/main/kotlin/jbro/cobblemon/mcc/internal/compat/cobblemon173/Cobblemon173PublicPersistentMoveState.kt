package jbro.cobblemon.mcc.internal.compat.cobblemon173

import jbro.cobblemon.mcc.betterai.mechanics.LocalReactiveAbilityState
import jbro.cobblemon.mcc.internal.ai.PublicIds
import java.util.UUID

/** Preserves public persistent effects and the existing snapshot duration estimates. */
internal object Cobblemon173PublicPersistentMoveState {
    private const val PUBLIC_RAMPAGE = "rampagepublic:"
    private const val PERISH = "perishsong:"
    private const val HEAL_BLOCK_TURNS = "healblockturns:"
    private const val LASER_FOCUS_TURNS = "laserfocusturns:"
    private const val DISABLE_MOVE = "disablemove:"
    private const val DISABLE_TURNS = "disableturns:"
    private const val LEECH_SEED_SOURCE = "leechseedsource:"
    private const val BINDING_SOURCE = "partiallytrappedsource:"
    private val RAMPAGE_MOVES = setOf("outrage", "thrash", "petaldance", "ragingfury")
    private val BINDING_MOVES = setOf("bind", "clamp", "firespin", "infestation", "magmastorm", "sandtomb",
        "snaptrap", "thundercage", "whirlpool", "wrap")

    fun afterMove(effects: Set<String>, moveId: String, lockedContinuation: Boolean): Set<String> {
        val id = PublicIds.canonical(moveId)
        val previous = effects.firstOrNull { it.startsWith("$PUBLIC_RAMPAGE$id:") }
            ?.substringAfterLast(':')?.toIntOrNull() ?: 0
        val cleared = effects.filterNot { it.startsWith(PUBLIC_RAMPAGE) || it == "destinybond" }.toSet()
        if (id !in RAMPAGE_MOVES) return cleared
        val uses = if (lockedContinuation && previous > 0) previous + 1 else 1
        return cleared + "$PUBLIC_RAMPAGE$id:$uses"
    }

    fun afterVolatile(effects: Set<String>, effectId: String, active: Boolean,
        sourceMoveId: String?, sourceAbilityId: String?, sourcePokemonId: UUID? = null): Set<String> {
        val family = when {
            effectId.startsWith(PERISH) -> "perishsong"
            effectId.startsWith(DISABLE_MOVE) -> "disable"
            else -> PublicIds.canonical(effectId)
        }
        var cleared = effects.filterNot {
            family == "perishsong" && it.startsWith(PERISH) ||
                family == "healblock" && it.startsWith(HEAL_BLOCK_TURNS) ||
                family == "laserfocus" && it.startsWith(LASER_FOCUS_TURNS) ||
                family == "disable" && (it.startsWith(DISABLE_MOVE) || it.startsWith(DISABLE_TURNS)) ||
                family == "leechseed" && it.startsWith(LEECH_SEED_SOURCE) ||
                family == "partiallytrapped" && it.startsWith(BINDING_SOURCE) ||
                family == "slowstart" && it.startsWith(LocalReactiveAbilityState.SLOW_START_TURNS) ||
                family == "confusion" && active && it.startsWith(PUBLIC_RAMPAGE)
        }.toSet() - family
        if (!active) return cleared
        cleared += family
        return when (family) {
            "perishsong" -> effectId.takeIf { it.startsWith(PERISH) }?.substringAfter(':')?.toIntOrNull()
                ?.takeIf { it in 0..3 }?.let { cleared + "$PERISH$it" } ?: cleared
            "healblock" -> cleared + (HEAL_BLOCK_TURNS + when {
                PublicIds.canonical(sourceMoveId.orEmpty()) == "psychicnoise" -> 2
                PublicIds.canonical(sourceAbilityId.orEmpty()) == "persistent" -> 7
                else -> 5
            })
            "laserfocus" -> cleared + (LASER_FOCUS_TURNS + "2")
            // The public event identifies the move exactly, but not native Disable's remaining duration.
            // Keep the pre-existing four-turn snapshot estimate rather than infer hidden queue state.
            "disable" -> cleared + (DISABLE_TURNS + "4") +
                setOfNotNull(effectId.takeIf { it.startsWith(DISABLE_MOVE) })
            "leechseed" -> if (sourcePokemonId != null && PublicIds.canonical(sourceMoveId.orEmpty()) == "leechseed")
                cleared + (LEECH_SEED_SOURCE + sourcePokemonId) else cleared
            "partiallytrapped" -> if (sourcePokemonId != null && PublicIds.canonical(sourceMoveId.orEmpty()) in BINDING_MOVES)
                cleared + (BINDING_SOURCE + sourcePokemonId) else cleared
            "slowstart" -> cleared + (LocalReactiveAbilityState.SLOW_START_TURNS + "5")
            else -> cleared
        }
    }

    fun afterFailedMove(effects: Set<String>): Set<String> = effects.filterNot { it.startsWith(PUBLIC_RAMPAGE) }.toSet()

    fun afterCannotAct(effects: Set<String>): Set<String> = afterFailedMove(effects) - "destinybond"

    fun afterSleep(effects: Set<String>, statusId: String?): Set<String> = if (PublicIds.canonical(statusId.orEmpty()) in setOf("slp", "sleep"))
        afterFailedMove(effects) else effects

    fun advance(effects: Set<String>, elapsed: Int): Set<String> {
        if (elapsed <= 0) return effects
        val timers = listOf(HEAL_BLOCK_TURNS to "healblock", LASER_FOCUS_TURNS to "laserfocus",
            DISABLE_TURNS to "disable", LocalReactiveAbilityState.SLOW_START_TURNS to "slowstart")
        val expired = timers.filter { (prefix, _) -> effects.any {
            it.startsWith(prefix) && (it.substringAfter(prefix).toIntOrNull() ?: 0) <= elapsed
        } }.map { it.second }.toSet()
        return effects.mapNotNull { marker ->
            when {
                marker == LocalReactiveAbilityState.ENTERED_THIS_TURN || marker == "endure" || marker in expired ||
                    "disable" in expired && marker.startsWith(DISABLE_MOVE) -> null
                else -> timers.firstOrNull { marker.startsWith(it.first) }?.let { (prefix, _) ->
                    ((marker.substringAfter(prefix).toIntOrNull() ?: 0) - elapsed).takeIf { it > 0 }?.let { prefix + it }
                } ?: marker.takeUnless { timers.any { timer -> marker.startsWith(timer.first) } }
            }
        }.toSet()
    }
}
