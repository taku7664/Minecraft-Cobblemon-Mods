package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * Reflect, Light Screen and Aurora Veil, in the root's units (HP percent): the hits they halve while they last.
 *
 * The root gave every screen the generic status value (20), so a screen lost to any attack that did a fifth of a bar,
 * whatever it would have kept off: a Mega Froslass with Aurora Veil never set it. A screen now earns half the
 * opponent's best expected hit of the category it covers (Aurora Veil both), over its turns after this one (5, 8 with
 * Light Clay) at [FADE] for the switches and knockouts that cut it short, less the generic value it already has.
 *
 * Aurora Veil fails without snow: it earns nothing and loses the generic value unless snow is up or comes this turn
 * (a Snow Warning Mega Evolution: Mega Froslass, Mega Abomasnow). A screen already up is left to the root's own
 * refresh penalty.
 */
internal object LocalScreenCredit {
    fun adjustments(
        candidates: List<BattleActionCandidate>,
        context: BattleDecisionContext,
        scores: MatchupScores,
    ): Map<String, Double> {
        val state = context.state
        val up = state.field.sideConditions[BattleSide.ALLY].orEmpty().mapTo(hashSetOf()) { PublicIds.canonical(it.effectId) }
        val snow = state.field.weather?.effectId?.let(PublicIds::canonical) in SNOW
        val foes = state.pokemon.filter { it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
        if (foes.isEmpty()) return emptyMap()
        val out = linkedMapOf<String, Double>()
        for (candidate in candidates) {
            val parts = if (candidate.kind == BattleActionKind.COMPOSITE) candidate.componentActions else listOf(candidate)
            var change = 0.0
            for (part in parts) {
                if (part.kind != BattleActionKind.USE_MOVE) continue
                val screen = screenOf(part) ?: continue
                if (screen in up) continue
                val user = state.pokemon.firstOrNull {
                    it.side == BattleSide.ALLY && it.activeSlot == part.actorSlot && !it.fainted && it.hpFraction > 0.0
                } ?: continue
                if (screen == AURORA_VEIL && !snow && !snowOnMega(part, user)) {
                    change -= GENERIC_STATUS_VALUE + FAILED_VALUE
                    continue
                }
                val categories = when (screen) {
                    REFLECT -> setOf(BattleMoveDamageCategory.PHYSICAL)
                    LIGHT_SCREEN -> setOf(BattleMoveDamageCategory.SPECIAL)
                    else -> setOf(BattleMoveDamageCategory.PHYSICAL, BattleMoveDamageCategory.SPECIAL)
                }
                val hit = foes.maxOf { foe -> bestHit(foe, user, categories, context, scores) }
                val clay = context.exactOwnTeam?.builds?.firstOrNull { it.battlePokemonId == user.battlePokemonId }
                    ?.heldItemId?.let(PublicIds::canonical) == LIGHT_CLAY
                val turns = (if (clay) CLAY_TURNS else TURNS) - 1
                change += hit * HALVED * turns * FADE * 100.0 - GENERIC_STATUS_VALUE
            }
            if (change != 0.0) out[candidate.actionId] = change
        }
        return out
    }

    private fun screenOf(move: BattleActionCandidate): String? =
        move.moveDetails?.effects?.effects.orEmpty().firstNotNullOfOrNull { effect ->
            effect.valueId?.let(PublicIds::canonical)?.takeIf {
                effect.kind == BattleMoveEffectKind.SIDE_CONDITION && effect.target == BattleMoveEffectTarget.USER_SIDE && it in SCREENS
            }
        }

    /** A Mega Evolution this turn whose Mega has Snow Warning: the snow is up before the move. */
    private fun snowOnMega(move: BattleActionCandidate, user: BattlePokemonStateView): Boolean =
        move.mechanic?.mechanicId?.let(PublicIds::canonical) in MEGA &&
            PublicIds.canonical(user.speciesId.substringAfter(':')) in SNOW_WARNING_MEGAS

    /** The foe's best expected hit on [user] among its moves of [categories], as a share of [user]'s maximum HP. */
    private fun bestHit(
        foe: BattlePokemonStateView,
        user: BattlePokemonStateView,
        categories: Set<BattleMoveDamageCategory>,
        context: BattleDecisionContext,
        scores: MatchupScores,
    ): Double {
        val catalog = context.publicActionCatalog
        val category = (catalog.forPokemon(foe.battlePokemonId).map { it.moveId to it.details.damageCategory } +
            catalog.inferredMovesForPokemon(foe.battlePokemonId)?.slots.orEmpty()
                .mapNotNull { slot -> slot.moveId?.let { id -> slot.details?.let { id to it.damageCategory } } })
            .associate { (id, kind) -> PublicIds.canonical(id) to kind }
        return scores.moves(foe.battlePokemonId, user.battlePokemonId)
            .filter { category[PublicIds.canonical(it.moveId)] in categories }
            .maxOfOrNull { (it.minimumDamageFraction + it.maximumDamageFraction) / 2.0 * it.accuracy } ?: 0.0
    }

    private const val REFLECT = "reflect"
    private const val LIGHT_SCREEN = "lightscreen"
    private const val AURORA_VEIL = "auroraveil"
    private val SCREENS = setOf(REFLECT, LIGHT_SCREEN, AURORA_VEIL)
    private val SNOW = setOf("snow", "snowscape", "hail")
    private val MEGA = setOf("mega", "megaevolution")
    private val SNOW_WARNING_MEGAS = setOf("froslass", "abomasnow")
    private const val LIGHT_CLAY = "lightclay"
    private const val TURNS = 5
    private const val CLAY_TURNS = 8
    /** A screen halves the hits it covers (singles). */
    private const val HALVED = 0.5
    /** Share of the screen's turns that still protect a Pokemon, switches and knockouts aside. */
    const val FADE = 0.7
    /** What the root already gives any status move (LocalNonDamagingMoveEvaluator's generic value). */
    private const val GENERIC_STATUS_VALUE = 20.0
    /** A move that fails ranks under one that does nothing. */
    private const val FAILED_VALUE = 20.0
}
