package jbro.cobblemon.mcc.betterai.policy

import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAbilityState
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAccuracy
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicMoveTargets
import jbro.cobblemon.mcc.betterai.mechanics.LocalStatStageChange
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.internal.ai.*

/** Known own opening mechanics; opponent moves, builds and entry abilities remain unfilled. */
internal object LocalDoubleLeadOpeningMechanics {
    fun afterOwnEntries(state: BattleStateView): BattleStateView {
        val own = state.pokemon.filter {
            it.side == BattleSide.ALLY && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        }
        val commander = own.singleOrNull { PublicIds.canonical(it.speciesId) == "tatsugiri" &&
            LocalPublicAbilityState.effectiveKnownAbility(state, it) == "commander" } ?: return state
        val partner = own.singleOrNull { PublicIds.canonical(it.speciesId) == "dondozo" } ?: return state
        if (partner.canonicalKnownVolatileEffectIds.contains("commanded")) return state
        val boosted = LocalStatStageChange.apply(state, partner.battlePokemonId, null,
            mapOf("attack" to 2, "special_attack" to 2, "speed" to 2, "defence" to 2, "special_defence" to 2))
        return boosted.copyState(pokemon = boosted.pokemon.map { pokemon -> when (pokemon.battlePokemonId) {
            commander.battlePokemonId -> pokemon.copyState(knownVolatileEffectIds = pokemon.knownVolatileEffectIds + "commanding")
            partner.battlePokemonId -> pokemon.copyState(knownVolatileEffectIds = pokemon.knownVolatileEffectIds + "commanded")
            else -> pokemon
        } })
    }

    /** Spread damage times the own HP retained after its known collateral, on the lead's log scale. */
    fun attackMultiplier(action: BattleActionCandidate, context: BattleDecisionContext): Double {
        val details = action.moveDetails ?: return 1.0
        val untargeted = BattleActionCandidate(action.actionId, action.kind, action.actorSlot, action.moveSlot, action.moveId,
            moveDetails = details, tags = action.tags)
        val spread = LocalPublicMoveTargets.spreadMultiplier(untargeted, context, BattleSide.ALLY)
        val ownTargets = context.state.pokemon.filter { pokemon ->
            pokemon.side == BattleSide.ALLY && pokemon.activeSlot != null && !pokemon.fainted && pokemon.hpFraction > 0.0 &&
                "commanding" !in pokemon.canonicalKnownVolatileEffectIds && when (details.targetPattern) {
                    BattleMoveTargetPattern.ALL_ADJACENT -> pokemon.activeSlot != action.actorSlot
                    BattleMoveTargetPattern.ALL_ACTIVE -> true
                    else -> false
                }
        }
        if (ownTargets.isEmpty()) return spread
        val accuracy = LocalPublicAccuracy.probability(action, context, BattleSide.ALLY)
        val retained = ownTargets.fold(1.0) { retained, target ->
            val hit = BattleActionCandidate("${action.actionId}:collateral:${target.activeSlot}", action.kind,
                action.actorSlot, action.moveSlot, action.moveId,
                targets = listOf(BattleTargetSlot(BattleSide.ALLY, requireNotNull(target.activeSlot))),
                moveDetails = details, tags = action.tags + if (spread < 1.0) setOf(LocalPublicMoveTargets.SPREAD_HIT_TAG) else emptySet())
            // Missing public stats cannot prove a numeric HP loss. All modifiers and known immunities
            // are resolved by the same damage calculation used for an ordinary own attack.
            val rolls = PublicBattleTacticalCalculator.conservativeDamageRollFractions(hit, context, BattleSide.ALLY)
                ?: return@fold retained
            val lost = rolls.map { minOf(it, target.hpFraction) }.average() * accuracy
            retained * (1.0 - lost / target.hpFraction).coerceAtLeast(MINIMUM_RETAINED_HP)
        }
        return spread * retained
    }

    /** Keeps a known knockout finite, like the lead matchup's existing immunity floor. */
    private const val MINIMUM_RETAINED_HP = 0.125
}
