package jbro.cobblemon.morebattlecontent.betterai.calculation

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.mechanics.StandardTypeEffectiveness
import jbro.cobblemon.morebattlecontent.betterai.state.RecursiveActionHistory
import jbro.cobblemon.morebattlecontent.betterai.state.RecursiveMoveUseKey

/** Builds complete public-information turns for one or two active slots. */
internal object PublicFutureActionFactory {
    fun actions(
        state: BattleStateView,
        side: BattleSide,
        catalog: BattlePublicActionCatalogView,
        history: RecursiveActionHistory = RecursiveActionHistory(),
        candidateLimitPerSlot: Int = Int.MAX_VALUE,
        unknownMovePokemonIds: Set<java.util.UUID> = emptySet(),
    ): List<BattleActionCandidate> {
        require(candidateLimitPerSlot > 0)
        val active = state.pokemon.filter {
            it.side == side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        }.sortedBy { it.activeSlot }
        if (active.isEmpty()) return emptyList()
        val bySlot = active.map { pokemon ->
            limitPrimitiveActions(
                state,
                side,
                pokemon,
                primitiveActions(state, side, pokemon, catalog, history, unknownMovePokemonIds),
                candidateLimitPerSlot,
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
    ): List<BattleActionCandidate> {
        if (actions.size <= limit) return actions
        val ranked = actions.sortedWith(
            compareByDescending<BattleActionCandidate> { primitivePriority(state, side, actor, it) }
                .thenBy(BattleActionCandidate::actionId),
        )
        val selected = linkedMapOf<String, BattleActionCandidate>()
        fun reserve(predicate: (BattleActionCandidate) -> Boolean) {
            if (selected.size >= limit) return
            ranked.firstOrNull(predicate)?.let { selected.putIfAbsent(it.actionId, it) }
        }
        reserve { "unknown_public_response" in it.tags }
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
                details.power * details.accuracy / 100.0 * stab * matchup + details.priority * 5.0
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
        val moves = catalog.forPokemon(active.battlePokemonId).flatMapIndexed { index, option ->
            val used = history.moveUses[RecursiveMoveUseKey(active.battlePokemonId, option.moveId)] ?: 0
            val legal = option.details.currentPp - used > 0 &&
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
                    moveDetails = option.details,
                    tags = setOf("public_lookahead"),
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
            opposingActive.any { trappedByKnownAbility(active, it) }
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

    private fun trappedByKnownAbility(active: BattlePokemonStateView, opponent: BattlePokemonStateView): Boolean {
        if ("ghost" in active.knownTypeIds.map(::canonicalId)) return false
        return when (canonicalId(opponent.knownAbilityId)) {
            "shadowtag" -> canonicalId(active.knownAbilityId) != "shadowtag"
            "arenatrap" -> "flying" !in active.knownTypeIds.map(::canonicalId) &&
                canonicalId(active.knownAbilityId) != "levitate" &&
                canonicalId(active.knownHeldItemId) != "airballoon"
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
