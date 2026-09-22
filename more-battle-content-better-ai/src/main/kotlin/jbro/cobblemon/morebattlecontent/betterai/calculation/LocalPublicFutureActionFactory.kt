package jbro.cobblemon.morebattlecontent.betterai.calculation

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalHypothesisPriorityReservation
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicMoveDamageInputs
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicAbilityState
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicFieldMechanics
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicTurnOrder
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicAccuracy
import jbro.cobblemon.morebattlecontent.betterai.mechanics.StandardTypeEffectiveness
import jbro.cobblemon.morebattlecontent.betterai.state.RecursiveActionHistory
import jbro.cobblemon.morebattlecontent.betterai.state.RecursiveMoveUseKey
import jbro.cobblemon.morebattlecontent.betterai.state.LocalOpponentMoveHypotheses

/** Builds complete public-information turns for one or two active slots. */
internal object PublicFutureActionFactory {
    fun actions(
        state: BattleStateView,
        side: BattleSide,
        catalog: BattlePublicActionCatalogView,
        history: RecursiveActionHistory = RecursiveActionHistory(),
        candidateLimitPerSlot: Int = Int.MAX_VALUE,
        unknownMovePokemonIds: Set<java.util.UUID> = emptySet(),
        includeMoveHypotheses: Boolean = false,
        hypotheticalMoveLimitPerSlot: Int = Int.MAX_VALUE,
        hypotheticalPriorityReservation: LocalHypothesisPriorityReservation = LocalHypothesisPriorityReservation.NONE,
    ): List<BattleActionCandidate> {
        require(candidateLimitPerSlot > 0)
        require(hypotheticalMoveLimitPerSlot > 0)
        val active = state.pokemon.filter {
            it.side == side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        }.sortedBy { it.activeSlot }
        if (active.isEmpty()) return emptyList()
        val bySlot = active.map { pokemon ->
            limitPrimitiveActions(
                state,
                side,
                pokemon,
                primitiveActions(state, side, pokemon, catalog, history, unknownMovePokemonIds, includeMoveHypotheses),
                candidateLimitPerSlot,
                hypotheticalMoveLimitPerSlot,
                hypotheticalPriorityReservation,
            )
        }
        if (bySlot.any(List<BattleActionCandidate>::isEmpty)) return emptyList()
        if (state.format == BattleFormat.SINGLE || bySlot.size == 1) return bySlot.single()
        return combine(bySlot)
    }

    fun primitiveActionsForPokemon(
        state: BattleStateView,
        side: BattleSide,
        pokemonId: java.util.UUID,
        catalog: BattlePublicActionCatalogView,
        history: RecursiveActionHistory = RecursiveActionHistory(),
    ): List<BattleActionCandidate> {
        val active = state.pokemon.singleOrNull {
            it.battlePokemonId == pokemonId && it.side == side && it.activeSlot != null &&
                !it.fainted && it.hpFraction > 0.0
        } ?: return emptyList()
        return primitiveActions(state, side, active, catalog, history, emptySet())
    }

    private fun limitPrimitiveActions(
        state: BattleStateView,
        side: BattleSide,
        actor: BattlePokemonStateView,
        actions: List<BattleActionCandidate>,
        limit: Int,
        hypotheticalMoveLimit: Int,
        hypotheticalPriorityReservation: LocalHypothesisPriorityReservation,
    ): List<BattleActionCandidate> {
        val ordered = actions.sortedWith(
            compareByDescending<BattleActionCandidate> { primitivePriority(state, side, actor, it) }
                .thenBy(BattleActionCandidate::actionId),
        )
        // Generated candidates have passed known PP/entry constraints, not all success conditions.
        // Group only by declared requirements: missing metadata is not proof of unconditional success.
        val priorityCandidates = ordered.asSequence().filter {
            "hypothetical_public_move" in it.tags && it.kind == BattleActionKind.USE_MOVE &&
                it.moveDetails?.let { details -> LocalPublicTurnOrder.effectivePriority(state, side, it) > 0 &&
                    details.damageCategory != BattleMoveDamageCategory.STATUS } == true
        }
        val priorityResponses = when (hypotheticalPriorityReservation) {
            LocalHypothesisPriorityReservation.NONE -> emptySequence()
            LocalHypothesisPriorityReservation.SINGLE -> priorityCandidates.take(1)
            LocalHypothesisPriorityReservation.CONDITION_GROUPS -> priorityCandidates.distinctBy {
                it.moveDetails?.effects?.requirements.isNullOrEmpty()
            }
        }.take(hypotheticalMoveLimit).toList()
        val selectedHypotheses = linkedSetOf<String>()
        priorityResponses.forEach { action -> action.moveId?.let { selectedHypotheses.add(canonicalId(it)) } }
        val ranked = ordered.filter { action ->
            // A search-cost cap, not a claim that omitted moves are impossible. Keep every target
            // variant of a selected move; known moves, switches and unknown responses do not count.
            if ("hypothetical_public_move" !in action.tags) true
            else {
                val move = canonicalId(requireNotNull(action.moveId))
                move in selectedHypotheses ||
                    (selectedHypotheses.size < hypotheticalMoveLimit && selectedHypotheses.add(move))
            }
        }
        // Ordering is applied even when nothing is trimmed, which it previously was not.
        //
        // The search does not always finish. It stops on a node or time budget, and inside a node it
        // abandons the remaining actions where it stands - so a budget that runs out decides which
        // moves were considered at all. Measured, Boss exhausts its budget on 24 of 40 decisions and
        // Advanced on 10, and until now the surviving prefix was whatever order the move slots
        // happened to be in.
        //
        // Sorting costs nothing when the budget holds: the caller takes a maximum over all of them and
        // order cannot change a maximum. It only matters when the search is cut short, and then it is
        // the difference between examining the plausible moves and examining the first ones.
        if (ranked.size <= limit) return ranked
        val selected = linkedMapOf<String, BattleActionCandidate>()
        fun reserve(predicate: (BattleActionCandidate) -> Boolean) {
            if (selected.size >= limit) return
            ranked.firstOrNull(predicate)?.let { selected.putIfAbsent(it.actionId, it) }
        }
        reserve { "unknown_public_response" in it.tags }
        priorityResponses.forEach { priority -> reserve { it.actionId == priority.actionId } }
        reserve { it.kind == BattleActionKind.USE_MOVE }
        reserve { it.kind == BattleActionKind.SWITCH }
        ranked.forEach { action ->
            if (selected.size < limit) selected.putIfAbsent(action.actionId, action)
        }
        return selected.values.toList()
    }

    private fun primitivePriority(
        state: BattleStateView,
        side: BattleSide,
        actor: BattlePokemonStateView,
        action: BattleActionCandidate,
    ): Double = when (action.kind) {
        BattleActionKind.USE_MOVE -> {
            val details = action.moveDetails ?: return 0.0
            if (details.damageCategory == BattleMoveDamageCategory.STATUS) {
                45.0 + details.effects?.effects.orEmpty().sumOf { effect ->
                    when (effect.kind) {
                        BattleMoveEffectKind.HEAL_FRACTION -> if (actor.hpFraction < 0.5) 35.0 else 5.0
                        BattleMoveEffectKind.PROTECT_USER -> 15.0
                        BattleMoveEffectKind.STATUS,
                        BattleMoveEffectKind.VOLATILE_STATUS,
                        BattleMoveEffectKind.SIDE_CONDITION,
                        BattleMoveEffectKind.FIELD_CONDITION,
                        BattleMoveEffectKind.WEATHER,
                        BattleMoveEffectKind.TERRAIN,
                        -> 20.0 * (effect.probability ?: 1.0)
                        BattleMoveEffectKind.STAT_STAGE -> 12.0 * effect.statStages.values.sumOf { kotlin.math.abs(it) }
                        else -> 0.0
                    }
                }
            } else if (LocalPublicMoveDamageInputs.isUnresolvedDynamicDamage(action)) {
                LocalPublicTurnOrder.effectivePriority(state, side, action) * 5.0
            } else {
                val stab = if (actor.knownTypeIds.any { canonicalId(it) == canonicalId(details.typeId) }) 1.5 else 1.0
                val explicitTargets = action.targets.mapNotNull { target ->
                    state.pokemon.firstOrNull {
                        it.side == target.side && it.activeSlot == target.slot && !it.fainted && it.hpFraction > 0.0
                    }
                }
                val targets = explicitTargets.ifEmpty {
                    state.pokemon.filter {
                        it.side != side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
                    }
                }
                val matchup = targets.maxOfOrNull { target ->
                    if (target.knownTypeIds.isEmpty()) 1.0
                    else StandardTypeEffectiveness.multiplier(details.typeId, target.knownTypeIds)
                } ?: 1.0
                LocalPublicAccuracy.weightedPower(
                    details,
                    LocalPublicAccuracy.probability(action, state, side),
                ) * stab * matchup +
                    LocalPublicTurnOrder.effectivePriority(state, side, action) * 5.0
            }
        }
        BattleActionKind.SWITCH -> action.switchPokemonId?.let { id ->
            state.pokemon.firstOrNull { it.battlePokemonId == id }?.hpFraction?.times(35.0)
        } ?: 0.0
        BattleActionKind.WAIT -> if ("unknown_public_response" in action.tags) 10_000.0 else -100.0
        BattleActionKind.COMPOSITE,
        BattleActionKind.FORFEIT,
        -> -1_000.0
    }

    private fun primitiveActions(
        state: BattleStateView,
        side: BattleSide,
        active: BattlePokemonStateView,
        catalog: BattlePublicActionCatalogView,
        history: RecursiveActionHistory,
        unknownMovePokemonIds: Set<java.util.UUID>,
        includeMoveHypotheses: Boolean = false,
    ): List<BattleActionCandidate> {
        val actorSlot = requireNotNull(active.activeSlot)
        if (active.actionConstraints.mustRecharge || active.battlePokemonId in history.rechargingPokemonIds) {
            return listOf(wait(side, active, "forced_recharge"))
        }
        val chargingMoveId = history.chargingMoveByPokemon[active.battlePokemonId]
        val encoreMoveId = active.actionConstraints.encoreMoveId ?: history.encoreByPokemon[active.battlePokemonId]
            ?.takeIf { it.remainingTurns > 0 }
            ?.moveId
        val taunted = active.actionConstraints.taunted ||
            (history.tauntTurnsByPokemon[active.battlePokemonId] ?: 0) > 0
        val currentCatalog = catalog.afterSwitch(history.restoredOriginalPokemonIds)
        val knownOptions = currentCatalog.forPokemon(active.battlePokemonId).map {
            FutureMoveOption(it.moveId, it.details, false)
        }
        val hypotheses = if (includeMoveHypotheses) LocalOpponentMoveHypotheses.options(active, currentCatalog, history)
            .filterKeys { move -> knownOptions.none { canonicalId(it.moveId) == canonicalId(move) } }
            .map { (move, details) -> FutureMoveOption(move, details, true) } else emptyList()
        val moves = (knownOptions + hypotheses).flatMapIndexed { index, option ->
            val used = history.moveUses[RecursiveMoveUseKey(active.battlePokemonId, option.moveId)] ?: 0
            val remainingPp = (option.details.currentPp - used).coerceAtLeast(0)
            val legal = (remainingPp > 0 || chargingMoveId == option.moveId) &&
                (canonicalId(option.moveId) !in FIRST_ENTRY_ONLY_MOVES ||
                    active.battlePokemonId !in history.actedSinceEntryPokemonIds) &&
                (!taunted || option.details.damageCategory != BattleMoveDamageCategory.STATUS) &&
                (chargingMoveId == null || option.moveId == chargingMoveId) &&
                (encoreMoveId == null || option.moveId == encoreMoveId)
            if (!legal) return@flatMapIndexed emptyList()
            moveTargetVariants(state, side, actorSlot, option.details.targetPattern).map { targets ->
                BattleActionCandidate(
                    actionId = buildString {
                        append("lookahead:").append(side.name.lowercase()).append(':')
                        append(active.battlePokemonId).append(":move:").append(option.moveId)
                        targets.singleOrNull()?.let { append(":target:").append(it.side.name.lowercase()).append(':').append(it.slot) }
                    },
                    kind = BattleActionKind.USE_MOVE,
                    actorSlot = actorSlot,
                    moveSlot = index,
                    moveId = option.moveId,
                    targets = targets,
                    moveDetails = option.details.copy(currentPp = remainingPp),
                    tags = if (option.hypothetical) setOf("public_lookahead", "hypothetical_public_move")
                        else setOf("public_lookahead"),
                )
            }
        }
        val unknown = if (chargingMoveId == null && active.battlePokemonId in unknownMovePokemonIds) {
            listOf(wait(side, active, "unknown_public_response"))
        } else {
            emptyList()
        }
        val opposingActive = state.pokemon.filter {
            it.side != side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        }
        val trapped = active.actionConstraints.trapped ||
            (history.trappedByPokemon[active.battlePokemonId]?.remainingTurns ?: 0) > 0 ||
            opposingActive.any { trappedByKnownAbility(state, active, it) }
        val switches = if (trapped || chargingMoveId != null) emptyList() else state.pokemon.filter {
            it.side == side && it.activeSlot == null && !it.fainted && it.hpFraction > 0.0
        }.map { bench ->
            BattleActionCandidate(
                actionId = "lookahead:${side.name.lowercase()}:slot:$actorSlot:switch:${bench.battlePokemonId}",
                kind = BattleActionKind.SWITCH,
                actorSlot = actorSlot,
                switchPokemonId = bench.battlePokemonId,
                tags = setOf("public_lookahead"),
            )
        }
        return moves + unknown + switches
    }

    private data class FutureMoveOption(val moveId: String, val details: BattleMoveCandidateView, val hypothetical: Boolean)

    private fun combine(bySlot: List<List<BattleActionCandidate>>): List<BattleActionCandidate> =
        bySlot.fold(listOf(emptyList<BattleActionCandidate>())) { combinations, slotActions ->
            combinations.flatMap { combination -> slotActions.map { combination + it } }
        }.filter { components ->
            val switchIds = components.mapNotNull(BattleActionCandidate::switchPokemonId)
            switchIds.distinct().size == switchIds.size
        }.map { components ->
            val ids = components.map(BattleActionCandidate::actionId)
            BattleActionCandidate(
                actionId = "lookahead:turn:${ids.joinToString("|")}",
                kind = BattleActionKind.COMPOSITE,
                componentActionIds = ids,
                componentActions = components,
                tags = setOf("public_lookahead", "double_complete_turn"),
            )
        }

    private fun moveTargetVariants(
        state: BattleStateView,
        side: BattleSide,
        actorSlot: Int,
        pattern: BattleMoveTargetPattern,
    ): List<List<BattleTargetSlot>> = when (pattern) {
        BattleMoveTargetPattern.SELECTED_OPPONENT -> state.pokemon.filter {
            it.side != side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        }.sortedBy { it.activeSlot }.map { target ->
            listOf(BattleTargetSlot(opposite(side), requireNotNull(target.activeSlot)))
        }
        BattleMoveTargetPattern.RANDOM_OPPONENT -> listOf(emptyList())
        BattleMoveTargetPattern.SELECTED_ALLY -> state.pokemon.filter {
            it.side == side && it.activeSlot != null && it.activeSlot != actorSlot &&
                !it.fainted && it.hpFraction > 0.0
        }.sortedBy { it.activeSlot }.map { target ->
            listOf(BattleTargetSlot(side, requireNotNull(target.activeSlot)))
        }
        BattleMoveTargetPattern.SELECTED_ALLY_OR_SELF -> state.pokemon.filter {
            it.side == side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        }.sortedBy { it.activeSlot }.map { target ->
            listOf(BattleTargetSlot(side, requireNotNull(target.activeSlot)))
        }
        BattleMoveTargetPattern.SELECTED -> state.pokemon.filter {
            it.activeSlot != null && it.battlePokemonId != state.pokemon.firstOrNull { pokemon ->
                pokemon.side == side && pokemon.activeSlot == actorSlot
            }?.battlePokemonId && !it.fainted && it.hpFraction > 0.0
        }.sortedWith(compareBy<BattlePokemonStateView> { it.side }.thenBy { it.activeSlot }).map { target ->
            listOf(BattleTargetSlot(target.side, requireNotNull(target.activeSlot)))
        }
        BattleMoveTargetPattern.SELF -> listOf(listOf(BattleTargetSlot(side, actorSlot)))
        else -> listOf(emptyList())
    }

    private fun wait(side: BattleSide, active: BattlePokemonStateView, reason: String) = BattleActionCandidate(
        actionId = "lookahead:${side.name.lowercase()}:${active.battlePokemonId}:$reason",
        kind = BattleActionKind.WAIT,
        tags = setOf("public_lookahead", reason),
    )

    private fun trappedByKnownAbility(
        state: BattleStateView,
        active: BattlePokemonStateView,
        opponent: BattlePokemonStateView,
    ): Boolean {
        if ("ghost" in active.knownTypeIds.map(::canonicalId)) return false
        return when (LocalPublicAbilityState.effectiveKnownAbility(state, opponent)) {
            "shadowtag" -> LocalPublicAbilityState.effectiveKnownAbility(state, active) != "shadowtag"
            "arenatrap" -> LocalPublicTurnOrder.grounded(state, active)
            "magnetpull" -> "steel" in active.knownTypeIds.map(::canonicalId)
            else -> false
        }
    }

    private fun opposite(side: BattleSide) = if (side == BattleSide.ALLY) BattleSide.OPPONENT else BattleSide.ALLY

    private fun canonicalId(value: String?): String = value.orEmpty()
        .substringAfter(':')
        .lowercase()
        .filter(Char::isLetterOrDigit)

    private val FIRST_ENTRY_ONLY_MOVES = setOf("fakeout", "firstimpression", "matblock")
}
