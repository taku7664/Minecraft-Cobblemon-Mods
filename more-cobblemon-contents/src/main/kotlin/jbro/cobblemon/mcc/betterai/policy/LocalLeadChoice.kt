package jbro.cobblemon.mcc.betterai.policy

import jbro.cobblemon.mcc.internal.ai.PublicIds
import java.util.SplittableRandom
import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.BattleCombatStatRangesView
import jbro.cobblemon.mcc.internal.ai.BattleIntegerRange
import jbro.cobblemon.mcc.internal.ai.BattleLeadChoiceContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleOpponentTeamPreviewPokemonView
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import jbro.cobblemon.mcc.betterai.mechanics.StandardTypeEffectiveness
import jbro.cobblemon.mcc.betterai.mechanics.LocalKnownStatMechanics
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicMechanicsKernel
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicMoveDamageInputs
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicTurnOrder
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicFieldMechanics
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveCandidateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleTargetSlot
import kotlin.math.exp
import kotlin.math.ln

/**
 * Picks the lead from the opponent's team preview, the way a player looks at six Pokemon and sends
 * out the one that plays best against them.
 *
 * Each own Pokemon is scored against every previewed opponent, since the AI does not know which of
 * them the player brings or leads with:
 * - Standard: type matchup only, the best attacking type against the opponent's types versus the
 *   opponent's types against its own.
 * - Advanced and Boss: also move power, the attacking and defending stats, and a sure speed edge.
 *
 * Doubles draws one ordered pair, covering two distinct public preview slots together and applying
 * known own entry effects. The draw is sharper at higher tiers. An Introductory trainer keeps team order.
 */
internal object LocalLeadChoice {
    data class Choice(val leads: List<UUID>, val scores: Map<UUID, Double>)

    fun choose(context: BattleLeadChoiceContext): Choice? {
        val tier = context.trainerProfile.difficulty.tier
        if (tier == BattleTrainerTier.INTRODUCTORY) return null
        val foes = context.opponentTeamPreview.pokemon.filter { it.knownTypeIds.isNotEmpty() }
        val candidates = context.ownTeam.filter { !it.fainted && it.hpFraction > 0.0 }
        if (foes.isEmpty() || candidates.size < context.leadCount) return null
        val detailed = tier != BattleTrainerTier.STANDARD
        val scores = candidates.associate { own ->
            own.battlePokemonId to foes.map { foe -> matchup(own, foe, context, detailed) }.average()
        }
        val random = SplittableRandom(context.seed)
        if (context.leadCount == 2) {
            return Choice(draw(LocalDoubleLeadPairEvaluation.scores(context, candidates, foes, detailed),
                temperature(tier), random), scores)
        }
        val remaining = scores.toMutableMap()
        val leads = mutableListOf<UUID>()
        repeat(context.leadCount) {
            val pick = draw(remaining, temperature(tier), random)
            leads += pick
            remaining.remove(pick)
        }
        return Choice(leads, scores)
    }

    /** Log-scale edge of [own] over [foe]: 1.0 is one doubling of damage in its favour. */
    internal fun matchup(
        own: BattlePokemonStateView,
        foe: BattleOpponentTeamPreviewPokemonView,
        context: BattleLeadChoiceContext,
        detailed: Boolean,
        opening: LocalLeadOpeningMatchup? = null,
    ): Double {
        val actor = opening?.context?.state?.pokemon?.singleOrNull { it.battlePokemonId == own.battlePokemonId } ?: own
        val offence = context.ownMoves.forPokemon(own.battlePokemonId)
            .filter { it.details.damageCategory != BattleMoveDamageCategory.STATUS && it.details.power > 0.0 }
            .mapIndexed { slot, move -> slot to move }.maxOfOrNull { (slot, move) ->
                var details = move.details
                var action = opening?.let { current -> BattleActionCandidate("lead:$slot", BattleActionKind.USE_MOVE,
                    actorSlot = requireNotNull(actor.activeSlot), moveSlot = slot, moveId = move.moveId,
                    moveDetails = details, targets = listOf(BattleTargetSlot(BattleSide.OPPONENT,
                        requireNotNull(current.foe.activeSlot)))) }
                if (action != null && opening != null) {
                    details = details.copy(typeId = LocalPublicMoveDamageInputs.resolvedTypeId(action, actor, opening.context.state)
                        ?: details.typeId)
                    action = BattleActionCandidate(action.actionId, action.kind, action.actorSlot, action.moveSlot, action.moveId,
                        targets = action.targets, moveDetails = details)
                }
                val stab = if (actor.knownTypeIds.any { canonical(it) == canonical(details.typeId) }) STAB else 1.0
                var value = StandardTypeEffectiveness.multiplier(details.typeId, foe.knownTypeIds) * stab
                if (action != null && opening != null) value *=
                    LocalPublicMechanicsKernel.projectMove(action, opening.context).knownDamageMultiplier *
                        LocalDoubleLeadOpeningMechanics.attackMultiplier(action, opening.context)
                if (detailed) {
                    val physical = details.damageCategory == BattleMoveDamageCategory.PHYSICAL
                    val inputs = if (action != null && opening != null)
                        LocalPublicMoveDamageInputs.resolve(action, actor, opening.foe, opening.context.state) else null
                    val power = if (inputs != null && action != null && opening != null)
                        LocalKnownStatMechanics.effectivePower(inputs.powers, actor, opening.context.state, action)
                            .let(::midpoint) else details.power
                    var attack = actor.combatStats?.let { if (physical) it.attack else it.specialAttack }
                    if (opening != null && attack != null) attack = LocalKnownStatMechanics.attack(
                        staged(attack, actor, if (physical) "attack" else "specialattack"), details.damageCategory, actor, opening.context.state)
                    val defence = if (opening == null) foe.combatStats?.let { if (physical) it.defence else it.specialDefence }
                        else opening.foe.combatStats?.let { staged(if (physical) it.defence else it.specialDefence,
                            opening.foe, if (physical) "defence" else "specialdefence") }
                    value *= power / REFERENCE_POWER * statRatio(attack, defence)
                }
                value
            } ?: MINIMUM_EDGE
        val defence = if (opening == null) foe.knownTypeIds.maxOf { type ->
            StandardTypeEffectiveness.multiplier(type, actor.knownTypeIds) * STAB
        }.let { value -> if (!detailed) value else value * statRatio(
            foe.combatStats?.let(::strongerAttack), own.combatStats?.let(::weakerDefence)) }
        else foe.knownTypeIds.maxOf { type ->
            // These are public type-pressure probes, not observed moves. The preview does not
            // reveal a category, so retain both and match each one's attacking and defending stats.
            listOf(BattleMoveDamageCategory.PHYSICAL, BattleMoveDamageCategory.SPECIAL).maxOf { category ->
                val physical = category == BattleMoveDamageCategory.PHYSICAL
                val probe = BattleActionCandidate("lead:public-type-pressure", BattleActionKind.USE_MOVE,
                    actorSlot = requireNotNull(opening.foe.activeSlot), moveSlot = 0,
                    targets = listOf(BattleTargetSlot(BattleSide.ALLY, requireNotNull(actor.activeSlot))),
                    moveDetails = BattleMoveCandidateView(type, category, REFERENCE_POWER, 100.0, 0, 1))
                var pressure = StandardTypeEffectiveness.multiplier(type, actor.knownTypeIds) * STAB *
                    LocalPublicMechanicsKernel.projectMove(probe, opening.context, BattleSide.OPPONENT).knownDamageMultiplier
                if (detailed) pressure *= statRatio(
                    opening.foe.combatStats?.let { stats -> staged(if (physical) stats.attack else stats.specialAttack,
                        opening.foe, if (physical) "attack" else "specialattack") },
                    actor.combatStats?.let { stats -> LocalKnownStatMechanics.defence(
                        staged(if (physical) stats.defence else stats.specialDefence, actor,
                            if (physical) "defence" else "specialdefence"),
                        if (physical) LocalPublicMoveDamageInputs.CombatStat.DEFENCE else LocalPublicMoveDamageInputs.CombatStat.SPECIAL_DEFENCE,
                        actor, opening.context.state) })
                pressure
            }
        }
        var edge = log2(offence.coerceAtLeast(MINIMUM_EDGE)) - log2(defence.coerceAtLeast(MINIMUM_EDGE))
        if (detailed) edge += if (opening == null) speedEdge(own.combatStats?.speed, foe.combatStats?.speed) else {
            fun speed(pokemon: BattlePokemonStateView) = LocalPublicTurnOrder.effectiveSpeed(opening.context.state, pokemon)
                ?.let { BattleIntegerRange(it.first, it.second) }
            val edge = speedEdge(speed(actor), speed(opening.foe))
            if (LocalPublicFieldMechanics.trickRoomActive(opening.context.state)) -edge else edge
        }
        return edge
    }

    private fun <T> draw(scores: Map<T, Double>, temperature: Double, random: SplittableRandom): T {
        val best = scores.values.max()
        val weights = scores.mapValues { (_, score) -> exp((score - best) / temperature) }
        var target = random.nextDouble(weights.values.sum())
        for ((id, weight) in weights) {
            target -= weight
            if (target < 0.0) return id
        }
        return weights.keys.last()
    }

    private fun temperature(tier: BattleTrainerTier): Double = when (tier) {
        BattleTrainerTier.INTRODUCTORY, BattleTrainerTier.STANDARD -> 0.6
        BattleTrainerTier.ADVANCED -> 0.4
        BattleTrainerTier.BOSS -> 0.3
    }

    private fun speedEdge(own: BattleIntegerRange?, foe: BattleIntegerRange?): Double = when {
        own == null || foe == null -> 0.0
        own.minimum > foe.maximum -> SPEED_EDGE
        own.maximum < foe.minimum -> -SPEED_EDGE
        else -> 0.0
    }

    private fun statRatio(attack: BattleIntegerRange?, defence: BattleIntegerRange?): Double {
        if (attack == null || defence == null) return 1.0
        val defending = midpoint(defence)
        return if (defending <= 0.0) 1.0 else (midpoint(attack) / defending).coerceIn(0.25, 4.0)
    }

    private fun strongerAttack(stats: BattleCombatStatRangesView): BattleIntegerRange =
        if (midpoint(stats.attack) >= midpoint(stats.specialAttack)) stats.attack else stats.specialAttack

    private fun weakerDefence(stats: BattleCombatStatRangesView): BattleIntegerRange =
        if (midpoint(stats.defence) <= midpoint(stats.specialDefence)) stats.defence else stats.specialDefence

    private fun midpoint(range: BattleIntegerRange): Double = (range.minimum + range.maximum) / 2.0

    private fun staged(range: BattleIntegerRange, pokemon: BattlePokemonStateView, stat: String): BattleIntegerRange {
        val aliases = when (stat) {
            "attack" -> setOf("attack", "atk")
            "specialattack" -> setOf("specialattack", "spa")
            "defence" -> setOf("defence", "defense", "def")
            else -> setOf("specialdefence", "specialdefense", "spd")
        }
        val stage = pokemon.statStages.entries.firstOrNull { canonical(it.key) in aliases }?.value ?: 0
        val multiplier = if (stage >= 0) (2 + stage) / 2.0 else 2.0 / (2 - stage)
        return BattleIntegerRange((range.minimum * multiplier).toInt().coerceAtLeast(1),
            (range.maximum * multiplier).toInt().coerceAtLeast(1))
    }

    private fun log2(value: Double): Double = ln(value) / ln(2.0)

    private fun canonical(value: String): String =
        PublicIds.canonical(value)

    private const val STAB = 1.5
    private const val REFERENCE_POWER = 80.0
    private const val SPEED_EDGE = 0.3
    /** Floor so an immunity or a Pokemon without attacks reads as a large but finite deficit. */
    private const val MINIMUM_EDGE = 0.125
}
