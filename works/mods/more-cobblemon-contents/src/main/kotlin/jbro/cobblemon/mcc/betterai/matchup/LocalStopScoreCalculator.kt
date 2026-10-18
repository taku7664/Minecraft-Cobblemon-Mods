package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.betterai.mechanics.LocalFullHealthSurvivalRules
import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAbilityState
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAbilityMechanics
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicMechanicsKernel
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicTurnOrder
import jbro.cobblemon.mcc.betterai.mechanics.LocalStatStageChange
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveCandidateView
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectView
import jbro.cobblemon.mcc.internal.ai.BattleOpponentMoveKnowledge
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.BattleTargetSlot
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * [StopScore]s: each Pokemon against the opposing side's sweeper, set up the way its [SweepScore] assumes.
 *
 * The flat values below price tools whose payoff the one-on-one exchange cannot play out (a forced switch
 * brings in an unknown Pokemon, Encore hands over turns whose use is up to the partner or the next
 * decision). They sit under what an outright win is worth, so a tool never outranks simply beating the sweeper.
 */
internal object LocalStopScoreCalculator {
    fun score(
        context: BattleDecisionContext,
        subject: BattlePokemonStateView,
        sweeper: SweepScore,
        sweeperPokemon: BattlePokemonStateView,
        pairs: MatchupScores,
        cache: LocalProjectedActionCalculationCache,
    ): StopScore? {
        val subjectId = subject.battlePokemonId
        val sweeperId = sweeperPokemon.battlePokemonId
        val position = LocalMatchupPosition.face(context, subject, sweeperPokemon, cache) ?: return null
        val sweeperStages = sweeper.setupMoveId?.let { LocalMatchupScoreCalculator.setupMoves(context, sweeperPokemon)[it] }
            .orEmpty()
        val sweeperCost = sweeper.setupMoveId?.let { LocalMatchupScoreCalculator.setupHpCosts(context, sweeperPokemon)[it] } ?: 0.0
        val boosted = position.copy(state = LocalMatchupScoreCalculator.setupState(position.state,
            sweeperId, sweeperStages, sweeper.setupUses, sweeperCost))
        fun exchange(state: BattleStateView = boosted.state, field: MatchupSpeedField = MatchupSpeedField.CURRENT): PokemonMatchupScore? {
            val faced = boosted.copy(state = state)
            val read = if (field == MatchupSpeedField.CURRENT) faced else LocalMatchupScoreCalculator.withTrickRoomToggled(faced)
            return LocalMatchupScoreCalculator.pairMatchup(read, subjectId, sweeperId, field, cache)
        }
        fun win(state: BattleStateView = boosted.state, field: MatchupSpeedField = MatchupSpeedField.CURRENT): Double =
            exchange(state, field)?.winProbability ?: 0.0
        fun withSweeper(transform: (BattlePokemonStateView) -> BattlePokemonStateView): BattleStateView =
            boosted.state.copyState(pokemon = boosted.state.pokemon.map { if (it.battlePokemonId == sweeperId) transform(it) else it })

        val againstBoosted = exchange() ?: return null
        val boostedSubject = boosted.state.pokemon.first { it.battlePokemonId == subjectId }
        val boostedSweeper = boosted.state.pokemon.first { it.battlePokemonId == sweeperId }
        val oneTimeSurvival = oneTimeSurvival(boosted.state, boostedSubject)
        val first = LocalPublicTurnOrder.speedOrderProbability(boosted.state, boostedSubject, boostedSweeper) ?: 0.5
        val survivesHit = if (oneTimeSurvival != null) 1.0 else againstBoosted.opponentMove?.survivalByUses?.getOrNull(1) ?: 1.0
        val actsBeforeKnockout = first + (1.0 - first) * survivesHit
        val sweeperStatusFree = boostedSweeper.statusId == null

        val tools = mutableListOf(StopTool(StopToolKind.OUTLASTS, againstBoosted.subjectMove?.moveId, againstBoosted.winProbability))
        for ((moveId, details) in moves(context, subject)) {
            val accuracy = (details.accuracy / 100.0).coerceIn(0.0, 1.0).takeIf { it > 0.0 } ?: 1.0
            val effects = details.effects?.effects.orEmpty()
            val status = details.damageCategory == BattleMoveDamageCategory.STATUS
            val action = BattleActionCandidate("$moveId:stop", BattleActionKind.USE_MOVE,
                actorSlot = boostedSubject.activeSlot, moveSlot = 0, moveId = moveId, moveDetails = details,
                targets = listOf(BattleTargetSlot(boostedSweeper.side, boostedSweeper.activeSlot ?: 0)))
            // A move that cannot land on the sweeper (Thunder Wave on a Ground type) stops nothing.
            val nullified = LocalPublicMechanicsKernel.projectMove(action, boosted.copy(candidates = listOf(action)), subject.side).publiclyNullified
            fun inflicts(statusId: String) = !nullified && effects.any { it.certainStatus(statusId) } &&
                !jbro.cobblemon.mcc.betterai.mechanics.LocalPublicStatusImmunity.blocked(boosted.state, boostedSweeper, statusId, boostedSubject)
            // What the move's data declares; nothing is played out (user decision 2026-10-19: no event-branch search).
            // A reset (Haze), Perish Song's count and Taunt never stop a sweeper already boosted (LocalSetupGate.NEVER_STOPS),
            // so the script-only ones among them are not read.
            when {
                effects.any { it.kind == BattleMoveEffectKind.SWITCH_TARGET } ->
                    tools += StopTool(StopToolKind.FORCES_SWITCH, moveId, actsBeforeKnockout * FORCED_SWITCH_VALUE)
                // Encore: every move but one locked, priced as a forced switch is.
                !nullified && effects.any { it.volatile(BattleMoveEffectTarget.SELECTED_TARGET, ENCORE) } ->
                    tools += StopTool(StopToolKind.ENCORE, moveId, actsBeforeKnockout * accuracy * FORCED_SWITCH_VALUE)
                // Trick Room: the exchange with the speed order flipped.
                effects.any { it.kind == BattleMoveEffectKind.FIELD_CONDITION && it.valueId?.let(PublicIds::canonical) == TRICK_ROOM } ->
                    tools += StopTool(StopToolKind.TRICK_ROOM, moveId, actsBeforeKnockout * win(field = MatchupSpeedField.TRICK_ROOM_TOGGLED))
                // Destiny Bond: up before the sweeper's knockout, the sweeper goes down with it.
                effects.any { it.volatile(BattleMoveEffectTarget.USER, DESTINY_BOND) } ->
                    tools += StopTool(StopToolKind.DESTINY_BOND, moveId,
                        actsBeforeKnockout * (againstBoosted.opponentMove?.knockoutChanceWithin(1) ?: 0.0))
                status && sweeperStatusFree && inflicts(BURN) -> tools += StopTool(StopToolKind.BURN, moveId,
                    actsBeforeKnockout * accuracy * win(withSweeper { it.copyState(statusId = BURN) }))
                status && sweeperStatusFree && inflicts(PARALYSIS) -> tools += StopTool(StopToolKind.PARALYSIS, moveId,
                    actsBeforeKnockout * accuracy * win(withSweeper { it.copyState(statusId = PARALYSIS) }))
            }
            val drops = effects.filter { it.certainTargetDrop() }
            if (drops.isNotEmpty()) {
                val stages = drops.fold(mutableMapOf<String, Int>()) { all, effect ->
                    effect.statStages.forEach { (stat, delta) -> all.merge(stat, delta, Int::plus) }; all
                }
                val dropped = if (nullified) boosted.state
                    else LocalStatStageChange.apply(boosted.state, sweeperId, subjectId, stages,
                        ignoreTargetAbility = LocalPublicAbilityMechanics.ignoresTargetAbility(action,
                            boostedSubject, boostedSweeper, boosted.state),
                        abilityIgnoringMove = LocalPublicAbilityMechanics.ignoresTargetAbility(action, boostedSubject, null, boosted.state))
                tools += StopTool(StopToolKind.STAT_DROP, moveId, actsBeforeKnockout * accuracy * win(dropped))
            }
        }
        // Unaware: the sweeper's attack and defence stages stop counting, its Speed still does. **Hardcoded**: the ability
        // is found by its id. A known ability decides (suppressed, Gastro Acid or Neutralizing Gas, it is none); an unknown
        // one counts at its share of the abilities still possible, and only as a likely stopper (STOPPER_PASS), since the
        // gate takes this kind as a stop outright. A sweeper whose attack ignores the target's ability (Mold Breaker) is
        // not stopped by it.
        val unaware = when {
            boostedSubject.knownAbilityId != null ->
                if (LocalPublicAbilityState.effectiveKnownAbility(boosted.state, boostedSubject)?.let(PublicIds::canonical) == UNAWARE) 1.0 else 0.0
            // Unknown but suppressed (Gastro Acid, a Neutralizing Gas): no ability works, Unaware among them.
            !LocalPublicAbilityState.isActive(boosted.state, boostedSubject, UNAWARE) -> 0.0
            else -> jbro.cobblemon.mcc.betterai.state.LocalEntryAbilityProjector.publicPossibleAbilities(boosted.state, boostedSubject)
                .takeIf { it.isNotEmpty() }?.let { possible -> possible.count { PublicIds.canonical(it) == UNAWARE }.toDouble() / possible.size }
                ?: 0.0
        }
        // The attack the sweeper uses in the exchange, read for whether it ignores the target's ability.
        val sweeperAttack = againstBoosted.opponentMove?.moveId?.let { id ->
            LocalMatchupScoreCalculator.moveMatchups(boosted, sweeperId, subjectId, cache).firstOrNull { it.score.moveId == id }?.action
        }
        val breaksIt = sweeperAttack != null &&
            LocalPublicAbilityMechanics.ignoresTargetAbility(sweeperAttack, boostedSweeper, boostedSubject, boosted.state)
        if (unaware > 0.0 && !breaksIt) {
            val ignored = withSweeper { pokemon -> pokemon.copyState(statStages = pokemon.statStages.filterKeys { PublicIds.canonical(it) in SPEED_STAT }) }
            val value = unaware * win(ignored)
            if (unaware >= 1.0 || value >= LocalSetupGate.STOPPER_PASS) tools += StopTool(StopToolKind.IGNORES_BOOSTS, null, value)
        }
        LocalMatchupScoreCalculator.moveMatchups(boosted, subjectId, sweeperId, cache)
            .filter { (it.action.moveDetails?.priority ?: 0) > 0 }
            .maxByOrNull { it.score.knockoutChanceWithin(1) }
            ?.let { tools += StopTool(StopToolKind.PRIORITY_FINISH, it.score.moveId, it.score.knockoutChanceWithin(1)) }

        val bonus = if (oneTimeSurvival != null) StopScore.ONE_TIME_SURVIVAL_BONUS else 0.0
        return StopScore(
            subjectId = subjectId,
            sweeperId = sweeperId,
            actsBeforeKnockout = actsBeforeKnockout,
            tools = tools.sortedByDescending { it.value },
            oneTimeSurvival = oneTimeSurvival,
            score = ((tools.maxOfOrNull { it.value } ?: 0.0) + bonus).coerceIn(0.0, 1.0),
        )
    }

    /** The subject's moves: its catalog, and for an opponent the slots this tier believes in. */
    private fun moves(context: BattleDecisionContext, subject: BattlePokemonStateView): List<Pair<String, BattleMoveCandidateView>> {
        val catalog = context.publicActionCatalog
        val known = catalog.forPokemon(subject.battlePokemonId).map { PublicIds.canonical(it.moveId) to it.details }
        val inferred = if (subject.side != BattleSide.OPPONENT) emptyList() else
            catalog.inferredMovesForPokemon(subject.battlePokemonId)?.slots.orEmpty()
                .filter { it.knowledge != BattleOpponentMoveKnowledge.GUESS }
                .mapNotNull { slot -> slot.moveId?.let { id -> slot.details?.let { PublicIds.canonical(id) to it } } }
        return (known + inferred).distinctBy { it.first }
    }

    private fun oneTimeSurvival(state: BattleStateView, pokemon: BattlePokemonStateView): String? {
        if (LocalFullHealthSurvivalRules.survivesAnySingleHit(state, pokemon)) {
            return pokemon.knownHeldItemId?.let(PublicIds::canonical)?.takeIf { it == FOCUS_SASH } ?: STURDY
        }
        val ability = LocalPublicAbilityState.effectiveKnownAbility(state, pokemon)
        return when {
            ability == DISGUISE && pokemon.formId?.let(PublicIds::canonical)?.contains(BUSTED) != true -> DISGUISE
            ability == MULTISCALE && pokemon.hpFraction >= 1.0 -> MULTISCALE
            else -> null
        }
    }

    private fun BattleMoveEffectView.certain(): Boolean = (probability ?: 1.0) >= 1.0

    private fun BattleMoveEffectView.certainStatus(status: String): Boolean =
        kind == BattleMoveEffectKind.STATUS && target == BattleMoveEffectTarget.SELECTED_TARGET && certain() &&
            valueId?.let(PublicIds::canonical) == status

    private fun BattleMoveEffectView.volatile(target: BattleMoveEffectTarget, id: String): Boolean =
        kind == BattleMoveEffectKind.VOLATILE_STATUS && this.target == target && valueId?.let(PublicIds::canonical) == id

    private fun BattleMoveEffectView.certainTargetDrop(): Boolean =
        kind == BattleMoveEffectKind.STAT_STAGE && target == BattleMoveEffectTarget.SELECTED_TARGET && certain() &&
            statStages.values.any { it < 0 }

    private const val BURN = "brn"
    private const val PARALYSIS = "par"
    private const val FOCUS_SASH = "focussash"
    private const val STURDY = "sturdy"
    private const val DISGUISE = "disguise"
    private const val BUSTED = "busted"
    private const val MULTISCALE = "multiscale"
    private const val FORCED_SWITCH_VALUE = 0.8
    private const val ENCORE = "encore"
    private const val TRICK_ROOM = "trickroom"
    private const val DESTINY_BOND = "destinybond"
    private const val UNAWARE = "unaware"
    private val SPEED_STAT = setOf("speed", "spe")
}
