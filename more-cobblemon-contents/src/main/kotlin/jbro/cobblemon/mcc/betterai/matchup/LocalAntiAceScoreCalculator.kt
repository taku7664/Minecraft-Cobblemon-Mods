package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.betterai.evaluation.LocalStatStageMarginalEvaluator
import jbro.cobblemon.mcc.betterai.mechanics.LocalFullHealthSurvivalRules
import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAbilityState
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicTurnOrder
import jbro.cobblemon.mcc.betterai.mechanics.copyState
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
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * [AntiAceScore]s: each Pokemon against the opposing side's ace, set up the way its [AceScore] assumes.
 *
 * The flat values below price tools whose payoff the one-on-one exchange cannot play out (a forced switch
 * brings in an unknown Pokemon, Encore hands over turns whose use is up to the partner or the next
 * decision). They sit under what an outright win is worth, so a tool never outranks simply beating the ace.
 */
internal object LocalAntiAceScoreCalculator {
    fun score(
        context: BattleDecisionContext,
        subject: BattlePokemonStateView,
        ace: AceScore,
        acePokemon: BattlePokemonStateView,
        pairs: MatchupScores,
        cache: LocalProjectedActionCalculationCache,
    ): AntiAceScore? {
        val subjectId = subject.battlePokemonId
        val aceId = acePokemon.battlePokemonId
        val position = LocalMatchupPosition.face(context, subject, acePokemon, cache) ?: return null
        val aceStages = ace.setupMoveId?.let { LocalMatchupScoreCalculator.setupMoves(context, acePokemon)[it] }
            ?.mapValues { it.value * ace.setupUses }.orEmpty()
        val boosted = position.copy(state = LocalStatStageMarginalEvaluator.applyStages(position.state, setOf(aceId), aceStages))
        fun exchange(state: BattleStateView = boosted.state, field: MatchupSpeedField = MatchupSpeedField.CURRENT): PokemonMatchupScore? {
            val faced = boosted.copy(state = state)
            val read = if (field == MatchupSpeedField.CURRENT) faced else LocalMatchupScoreCalculator.withTrickRoomToggled(faced)
            return LocalMatchupScoreCalculator.pairMatchup(read, subjectId, aceId, field, cache)
        }
        fun win(state: BattleStateView = boosted.state, field: MatchupSpeedField = MatchupSpeedField.CURRENT): Double =
            exchange(state, field)?.winProbability ?: 0.0
        fun withAce(transform: (BattlePokemonStateView) -> BattlePokemonStateView): BattleStateView =
            boosted.state.copyState(pokemon = boosted.state.pokemon.map { if (it.battlePokemonId == aceId) transform(it) else it })

        val againstBoosted = exchange() ?: return null
        val boostedSubject = boosted.state.pokemon.first { it.battlePokemonId == subjectId }
        val boostedAce = boosted.state.pokemon.first { it.battlePokemonId == aceId }
        val oneTimeSurvival = oneTimeSurvival(boosted.state, boostedSubject)
        val first = LocalPublicTurnOrder.speedOrderProbability(boosted.state, boostedSubject, boostedAce) ?: 0.5
        val firstBeforeSetup = position.state.pokemon.first { it.battlePokemonId == subjectId }.let { unboostedSubject ->
            LocalPublicTurnOrder.speedOrderProbability(position.state, unboostedSubject,
                position.state.pokemon.first { it.battlePokemonId == aceId }) ?: 0.5
        }
        val survivesHit = if (oneTimeSurvival != null) 1.0 else againstBoosted.opponentMove?.survivalByUses?.getOrNull(1) ?: 1.0
        val actsBeforeKnockout = first + (1.0 - first) * survivesHit
        val unboostedWin = pairs.pokemon(subjectId, aceId)?.winProbability ?: 0.0
        val aceStatusFree = boostedAce.statusId == null

        val tools = mutableListOf(AntiAceTool(AntiAceToolKind.OUTLASTS, againstBoosted.subjectMove?.moveId, againstBoosted.winProbability))
        if (unaware(boosted.state, boostedSubject)) {
            // Unaware reads the ace's attack and defence stages as zero; Speed stages still apply.
            val speedOnly = withAce { it.copyState(statStages = it.statStages.filterKeys { key -> PublicIds.canonical(key) in SPEED_KEYS }) }
            tools += AntiAceTool(AntiAceToolKind.IGNORES_BOOSTS, null, win(speedOnly))
        }
        for ((moveId, details) in moves(context, subject)) {
            val accuracy = (details.accuracy / 100.0).coerceIn(0.0, 1.0).takeIf { it > 0.0 } ?: 1.0
            val effects = details.effects?.effects.orEmpty()
            val status = details.damageCategory == BattleMoveDamageCategory.STATUS
            when {
                moveId in RESETS -> tools += AntiAceTool(AntiAceToolKind.RESETS_BOOSTS, moveId, actsBeforeKnockout * unboostedWin)
                moveId in FORCED_SWITCHES || effects.any { it.kind == BattleMoveEffectKind.SWITCH_TARGET } ->
                    tools += AntiAceTool(AntiAceToolKind.FORCES_SWITCH, moveId, actsBeforeKnockout * FORCED_SWITCH_VALUE)
                moveId == ENCORE -> tools += AntiAceTool(AntiAceToolKind.ENCORE, moveId, actsBeforeKnockout * ENCORE_VALUE)
                moveId == TAUNT -> tools += AntiAceTool(AntiAceToolKind.TAUNT, moveId, firstBeforeSetup * TAUNT_VALUE)
                // Trick Room moves last, so the boosted ace swings first.
                moveId == TRICK_ROOM -> tools += AntiAceTool(AntiAceToolKind.TRICK_ROOM, moveId,
                    survivesHit * win(field = MatchupSpeedField.TRICK_ROOM_TOGGLED))
                moveId == PERISH_SONG -> tools += AntiAceTool(AntiAceToolKind.PERISH_SONG, moveId, actsBeforeKnockout * PERISH_SONG_VALUE)
                // Destiny Bond has to be up before the ace attacks.
                moveId == DESTINY_BOND -> tools += AntiAceTool(AntiAceToolKind.DESTINY_BOND, moveId, first * DESTINY_BOND_VALUE)
                status && aceStatusFree && effects.any { it.certainStatus(BURN) } -> tools += AntiAceTool(AntiAceToolKind.BURN, moveId,
                    actsBeforeKnockout * accuracy * win(withAce { it.copyState(statusId = BURN) }))
                status && aceStatusFree && effects.any { it.certainStatus(PARALYSIS) } -> tools += AntiAceTool(AntiAceToolKind.PARALYSIS, moveId,
                    actsBeforeKnockout * accuracy * win(withAce { it.copyState(statusId = PARALYSIS) }))
            }
            val drops = effects.filter { it.certainTargetDrop() }
            if (drops.isNotEmpty()) {
                val stages = drops.fold(mutableMapOf<String, Int>()) { all, effect ->
                    effect.statStages.forEach { (stat, delta) -> all.merge(stat, delta, Int::plus) }; all
                }
                val dropped = LocalStatStageMarginalEvaluator.applyStages(boosted.state, setOf(aceId), stages)
                tools += AntiAceTool(AntiAceToolKind.STAT_DROP, moveId, actsBeforeKnockout * accuracy * win(dropped))
            }
        }
        LocalMatchupScoreCalculator.moveMatchups(boosted, subjectId, aceId, cache)
            .filter { (it.action.moveDetails?.priority ?: 0) > 0 }
            .maxByOrNull { it.score.knockoutChanceWithin(1) }
            ?.let { tools += AntiAceTool(AntiAceToolKind.PRIORITY_FINISH, it.score.moveId, it.score.knockoutChanceWithin(1)) }

        val bonus = if (oneTimeSurvival != null) AntiAceScore.ONE_TIME_SURVIVAL_BONUS else 0.0
        return AntiAceScore(
            subjectId = subjectId,
            aceId = aceId,
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

    private fun unaware(state: BattleStateView, pokemon: BattlePokemonStateView): Boolean =
        LocalPublicAbilityState.effectiveKnownAbility(state, pokemon) == UNAWARE

    private fun BattleMoveEffectView.certain(): Boolean = (probability ?: 1.0) >= 1.0

    private fun BattleMoveEffectView.certainStatus(status: String): Boolean =
        kind == BattleMoveEffectKind.STATUS && target == BattleMoveEffectTarget.SELECTED_TARGET && certain() &&
            valueId?.let(PublicIds::canonical) == status

    private fun BattleMoveEffectView.certainTargetDrop(): Boolean =
        kind == BattleMoveEffectKind.STAT_STAGE && target == BattleMoveEffectTarget.SELECTED_TARGET && certain() &&
            statStages.values.any { it < 0 }

    private val RESETS = setOf("haze", "clearsmog", "topsyturvy", "spectralthief")
    private val FORCED_SWITCHES = setOf("roar", "whirlwind", "dragontail", "circlethrow")
    private val SPEED_KEYS = setOf("speed", "spe")
    private const val ENCORE = "encore"
    private const val TAUNT = "taunt"
    private const val TRICK_ROOM = "trickroom"
    private const val PERISH_SONG = "perishsong"
    private const val DESTINY_BOND = "destinybond"
    private const val BURN = "brn"
    private const val PARALYSIS = "par"
    private const val FOCUS_SASH = "focussash"
    private const val STURDY = "sturdy"
    private const val DISGUISE = "disguise"
    private const val BUSTED = "busted"
    private const val MULTISCALE = "multiscale"
    private const val UNAWARE = "unaware"
    private const val FORCED_SWITCH_VALUE = 0.8
    private const val ENCORE_VALUE = 0.7
    private const val TAUNT_VALUE = 0.6
    private const val DESTINY_BOND_VALUE = 0.6
    private const val PERISH_SONG_VALUE = 0.5
}
