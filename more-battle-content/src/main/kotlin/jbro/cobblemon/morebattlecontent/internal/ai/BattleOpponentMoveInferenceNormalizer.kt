package jbro.cobblemon.morebattlecontent.internal.ai

import java.util.Locale
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*

/**
 * Converts the learnset, public reveals and the tier's strictly limited hidden evidence into four
 * stable slots. The full hidden set never leaves this boundary.
 */
internal object BattleOpponentMoveInferenceNormalizer {
    fun normalize(
        state: BattleStateView,
        catalog: BattlePublicActionCatalogView,
        tier: BattleTrainerTier,
        actualMoveIds: Map<UUID, Set<String>>,
        previous: Map<UUID, BattleOpponentMoveInferenceView> = emptyMap(),
        ignoredRevealPokemonIds: Set<UUID> = emptySet(),
        moveDetails: (String) -> BattleMoveCandidateView?,
    ): List<BattleOpponentMoveInferenceView> {
        val policy = BattleOpponentMoveInferencePolicies.forTier(tier)
        return state.pokemon.asSequence()
            .filter { it.side == BattleSide.OPPONENT && !it.fainted }
            .sortedBy { it.battlePokemonId.toString() }
            .map { pokemon ->
                val prior = previous[pokemon.battlePokemonId]
                if (prior == null) {
                    initialInference(
                        pokemon,
                        catalog,
                        policy,
                        actualMoveIds[pokemon.battlePokemonId].orEmpty(),
                        moveDetails,
                        includePublicReveals = pokemon.battlePokemonId !in ignoredRevealPokemonIds,
                    )
                } else if (pokemon.battlePokemonId in ignoredRevealPokemonIds) {
                    prior
                } else {
                    updateReveals(pokemon, catalog, prior, moveDetails)
                }
            }
            .toList()
    }

    private fun initialInference(
        pokemon: BattlePokemonStateView,
        catalog: BattlePublicActionCatalogView,
        policy: BattleOpponentMoveInferencePolicy,
        actualMoveIds: Set<String>,
        moveDetails: (String) -> BattleMoveCandidateView?,
        includePublicReveals: Boolean,
    ): BattleOpponentMoveInferenceView {
        val slots = mutableListOf<BattleOpponentMoveSlotView>()
        val revealed = if (includePublicReveals) revealedMoves(pokemon, catalog, moveDetails) else emptyList()
        revealed.forEach { (moveId, details) ->
            addConcrete(slots, moveId, details, group(pokemon, details), BattleOpponentMoveKnowledge.CONFIRMED,
                BattleOpponentMoveSource.PUBLIC_REVEAL)
        }

        val actual = if (policy.readsHiddenSet) {
            actualMoveIds.mapNotNull { id -> moveDetails(id)?.let { id to it } }
                .filterNot { (id, _) -> slots.containsMove(id) }
        } else {
            emptyList()
        }
        selectHiddenStab(actual, pokemon, policy.confirmedHiddenStabSlots).forEach { (id, details) ->
            addConcrete(slots, id, details, BattleOpponentMoveGroup.STAB_ATTACK,
                BattleOpponentMoveKnowledge.CONFIRMED, BattleOpponentMoveSource.DIFFICULTY_SET_READ)
        }
        selectHiddenSetup(actual, pokemon, policy.confirmedHiddenSetupSlots)
            .filterNot { (id, _) -> slots.containsMove(id) }
            .forEach { (id, details) ->
                addConcrete(slots, id, details, BattleOpponentMoveGroup.PURE_SETUP,
                    BattleOpponentMoveKnowledge.CONFIRMED, BattleOpponentMoveSource.DIFFICULTY_SET_READ)
            }

        val learnset = catalog.candidatePools.singleOrNull { pool ->
            pool.battlePokemonId == pokemon.battlePokemonId && pool.speciesId == pokemon.speciesId &&
                pool.formId == pokemon.formId
        }?.moveDetails.orEmpty()
            .filterKeys { id -> !slots.containsMove(id) }

        if (policy.preserveActualAttackStatusCounts) {
            fillPreservingActualShape(slots, pokemon, actual, learnset, policy)
        } else {
            fillFixedPolicy(slots, pokemon, learnset, policy)
        }
        fillGuesses(slots, BattleOpponentMoveGroup.OTHER, MAX_MOVE_SLOTS - slots.size)
        return BattleOpponentMoveInferenceView(pokemon.battlePokemonId, slots)
    }

    private fun fillFixedPolicy(
        slots: MutableList<BattleOpponentMoveSlotView>,
        pokemon: BattlePokemonStateView,
        learnset: Map<String, BattleMoveCandidateView>,
        policy: BattleOpponentMoveInferencePolicy,
    ) {
        addExpected(slots, ranked(learnset, pokemon, BattleOpponentMoveGroup.STAB_ATTACK),
            BattleOpponentMoveGroup.STAB_ATTACK, policy.expectedStabSlots)
        addExpected(slots, ranked(learnset, pokemon, BattleOpponentMoveGroup.COVERAGE_ATTACK),
            BattleOpponentMoveGroup.COVERAGE_ATTACK, policy.expectedCoverageSlots)
        fillGuesses(slots, BattleOpponentMoveGroup.STATUS_OTHER, policy.guessedStatusSlots)
    }

    private fun fillPreservingActualShape(
        slots: MutableList<BattleOpponentMoveSlotView>,
        pokemon: BattlePokemonStateView,
        actual: List<Pair<String, BattleMoveCandidateView>>,
        learnset: Map<String, BattleMoveCandidateView>,
        policy: BattleOpponentMoveInferencePolicy,
    ) {
        val actualAttackCount = actual.count { (_, details) -> details.damageCategory != BattleMoveDamageCategory.STATUS }
        val actualStatusCount = actual.count { (_, details) -> details.damageCategory == BattleMoveDamageCategory.STATUS }
        val currentAttackCount = slots.count { it.group.isAttack() }
        val currentStatusCount = slots.count { !it.group.isAttack() }
        val statusGuesses = (actualStatusCount - currentStatusCount).coerceAtLeast(0)
        fillGuesses(slots, BattleOpponentMoveGroup.STATUS_OTHER, statusGuesses)

        val attacksNeeded = (actualAttackCount - currentAttackCount).coerceAtLeast(0)
        val coverage = ranked(learnset, pokemon, BattleOpponentMoveGroup.COVERAGE_ATTACK)
        val stab = ranked(learnset, pokemon, BattleOpponentMoveGroup.STAB_ATTACK)
        val expectedBeforeCoverage = slots.count { it.knowledge == BattleOpponentMoveKnowledge.EXPECTED }
        addExpected(slots, coverage, BattleOpponentMoveGroup.COVERAGE_ATTACK,
            minOf(attacksNeeded, policy.expectedCoverageSlots))
        val coverageAdded = slots.count { it.knowledge == BattleOpponentMoveKnowledge.EXPECTED } -
            expectedBeforeCoverage
        val remainingAttacks = (attacksNeeded - coverageAdded)
            .coerceAtLeast(0)
        addExpected(slots, stab, BattleOpponentMoveGroup.STAB_ATTACK, remainingAttacks)
    }

    private fun updateReveals(
        pokemon: BattlePokemonStateView,
        catalog: BattlePublicActionCatalogView,
        prior: BattleOpponentMoveInferenceView,
        moveDetails: (String) -> BattleMoveCandidateView?,
    ): BattleOpponentMoveInferenceView {
        val slots = prior.slots.map { previous ->
            val refreshed = previous.moveId?.let { id ->
                catalog.candidatePools.singleOrNull { it.battlePokemonId == pokemon.battlePokemonId }
                    ?.moveDetails?.get(id) ?: moveDetails(id)
            }
            if (refreshed == null) previous else previous.copy(details = refreshed)
        }.toMutableList()
        revealedMoves(pokemon, catalog, moveDetails).forEach { (moveId, details) ->
            val moveGroup = group(pokemon, details)
            val exact = slots.indexOfFirst { sameMove(it.moveId, moveId) }
            val replacement = when {
                exact >= 0 -> exact
                else -> slots.indexOfFirst { it.knowledge == BattleOpponentMoveKnowledge.GUESS && it.group == moveGroup }
                    .takeIf { it >= 0 }
                    ?: slots.indexOfFirst { it.knowledge == BattleOpponentMoveKnowledge.EXPECTED && it.group == moveGroup }
                        .takeIf { it >= 0 }
                    ?: slots.indexOfFirst { it.knowledge == BattleOpponentMoveKnowledge.EXPECTED }
                        .takeIf { it >= 0 }
                    ?: slots.indexOfFirst { it.knowledge == BattleOpponentMoveKnowledge.GUESS }
                        .takeIf { it >= 0 }
                    ?: if (slots.size < MAX_MOVE_SLOTS) slots.size else null
            }
            if (replacement != null) {
                val slotNumber = slots.getOrNull(replacement)?.slot ?: replacement
                val confirmed = concrete(slotNumber, moveId, details, moveGroup,
                    BattleOpponentMoveKnowledge.CONFIRMED, BattleOpponentMoveSource.PUBLIC_REVEAL)
                if (replacement == slots.size) slots += confirmed else slots[replacement] = confirmed
            }
        }
        return BattleOpponentMoveInferenceView(pokemon.battlePokemonId, slots)
    }

    private fun revealedMoves(
        pokemon: BattlePokemonStateView,
        catalog: BattlePublicActionCatalogView,
        moveDetails: (String) -> BattleMoveCandidateView?,
    ): List<Pair<String, BattleMoveCandidateView>> = pokemon.knownMoveIds.asSequence()
        .mapNotNull { id ->
            (catalog.forPokemon(pokemon.battlePokemonId).singleOrNull { sameMove(it.moveId, id) }?.details
                ?: moveDetails(id))?.let { id to it }
        }
        .sortedBy { canonical(it.first) }
        .take(MAX_MOVE_SLOTS)
        .toList()

    private fun selectHiddenStab(
        actual: List<Pair<String, BattleMoveCandidateView>>,
        pokemon: BattlePokemonStateView,
        limit: Int,
    ): List<Pair<String, BattleMoveCandidateView>> = actual.asSequence()
        .filter { (_, details) -> group(pokemon, details) == BattleOpponentMoveGroup.STAB_ATTACK }
        .sortedWith(moveComparator())
        .distinctBy { (_, details) -> canonical(details.typeId) }
        .take(limit)
        .toList()

    private fun selectHiddenSetup(
        actual: List<Pair<String, BattleMoveCandidateView>>,
        pokemon: BattlePokemonStateView,
        limit: Int,
    ): List<Pair<String, BattleMoveCandidateView>> = actual.asSequence()
        .filter { (_, details) -> group(pokemon, details) == BattleOpponentMoveGroup.PURE_SETUP }
        .sortedBy { canonical(it.first) }
        .take(limit)
        .toList()

    private fun ranked(
        moves: Map<String, BattleMoveCandidateView>,
        pokemon: BattlePokemonStateView,
        wanted: BattleOpponentMoveGroup,
    ): List<Pair<String, BattleMoveCandidateView>> = moves.entries.asSequence()
        .filter { (_, details) -> group(pokemon, details) == wanted }
        .map { it.key to it.value }
        .sortedWith(moveComparator())
        .toList()

    private fun moveComparator() = compareByDescending<Pair<String, BattleMoveCandidateView>> {
        expectedStrength(it.second)
    }.thenBy { canonical(it.first) }

    /** Deliberately one function: candidate ranking can be rebalanced without changing slot rules. */
    internal fun expectedStrength(details: BattleMoveCandidateView): Double =
        details.power * (details.accuracy / 100.0) + details.priority.coerceAtLeast(0) * PRIORITY_WEIGHT

    internal fun group(
        pokemon: BattlePokemonStateView,
        details: BattleMoveCandidateView,
    ): BattleOpponentMoveGroup = when {
        details.damageCategory != BattleMoveDamageCategory.STATUS -> {
            if (pokemon.knownTypeIds.any { canonical(it) == canonical(details.typeId) }) {
                BattleOpponentMoveGroup.STAB_ATTACK
            } else {
                BattleOpponentMoveGroup.COVERAGE_ATTACK
            }
        }
        isPureSetup(details) -> BattleOpponentMoveGroup.PURE_SETUP
        details.damageCategory == BattleMoveDamageCategory.STATUS -> BattleOpponentMoveGroup.STATUS_OTHER
        else -> BattleOpponentMoveGroup.OTHER
    }

    private fun isPureSetup(details: BattleMoveCandidateView): Boolean {
        val effects = details.effects?.effects.orEmpty()
        return effects.isNotEmpty() && effects.all { effect ->
            effect.kind == BattleMoveEffectKind.STAT_STAGE && effect.target == BattleMoveEffectTarget.USER &&
                effect.statStages.isNotEmpty()
        } && effects.any { effect -> effect.statStages.values.any { it > 0 } }
    }

    private fun addExpected(
        slots: MutableList<BattleOpponentMoveSlotView>,
        candidates: List<Pair<String, BattleMoveCandidateView>>,
        group: BattleOpponentMoveGroup,
        limit: Int,
    ) {
        candidates.asSequence().filterNot { (id, _) -> slots.containsMove(id) }.take(limit).forEach { (id, details) ->
            addConcrete(slots, id, details, group, BattleOpponentMoveKnowledge.EXPECTED,
                BattleOpponentMoveSource.LEARNSET_EXPECTATION)
        }
    }

    private fun fillGuesses(
        slots: MutableList<BattleOpponentMoveSlotView>,
        group: BattleOpponentMoveGroup,
        count: Int,
    ) {
        repeat(minOf(count, MAX_MOVE_SLOTS - slots.size)) {
            slots += BattleOpponentMoveSlotView(
                slot = slots.size,
                moveId = null,
                group = group,
                knowledge = BattleOpponentMoveKnowledge.GUESS,
                source = BattleOpponentMoveSource.GROUP_GUESS,
            )
        }
    }

    private fun addConcrete(
        slots: MutableList<BattleOpponentMoveSlotView>,
        moveId: String,
        details: BattleMoveCandidateView,
        forcedGroup: BattleOpponentMoveGroup?,
        knowledge: BattleOpponentMoveKnowledge,
        source: BattleOpponentMoveSource,
    ) {
        if (slots.size >= MAX_MOVE_SLOTS || slots.containsMove(moveId)) return
        val inferredGroup = forcedGroup ?: if (details.damageCategory == BattleMoveDamageCategory.STATUS) {
            if (isPureSetup(details)) BattleOpponentMoveGroup.PURE_SETUP else BattleOpponentMoveGroup.STATUS_OTHER
        } else {
            BattleOpponentMoveGroup.COVERAGE_ATTACK
        }
        slots += concrete(slots.size, moveId, details, inferredGroup, knowledge, source)
    }

    private fun concrete(
        slot: Int,
        moveId: String,
        details: BattleMoveCandidateView,
        group: BattleOpponentMoveGroup,
        knowledge: BattleOpponentMoveKnowledge,
        source: BattleOpponentMoveSource,
    ) = BattleOpponentMoveSlotView(slot, moveId, group, knowledge, source, details)

    private fun List<BattleOpponentMoveSlotView>.containsMove(moveId: String): Boolean =
        any { sameMove(it.moveId, moveId) }

    private fun BattleOpponentMoveGroup.isAttack(): Boolean =
        this == BattleOpponentMoveGroup.STAB_ATTACK || this == BattleOpponentMoveGroup.COVERAGE_ATTACK

    private fun sameMove(left: String?, right: String): Boolean = left != null && canonical(left) == canonical(right)
    private fun canonical(value: String): String =
        value.substringAfter(':').lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    private const val MAX_MOVE_SLOTS = 4
    private const val PRIORITY_WEIGHT = 12.0
}

/** Per-battle persistence; deterministic slots survive until new public evidence changes them. */
internal class BattleOpponentMoveInferenceLedger(
    private val moveDetails: (String) -> BattleMoveCandidateView?,
) {
    private var byPokemon: Map<UUID, BattleOpponentMoveInferenceView> = emptyMap()
    private var identities: Map<UUID, PokemonIdentity> = emptyMap()

    @Synchronized
    fun update(
        state: BattleStateView,
        catalog: BattlePublicActionCatalogView,
        tier: BattleTrainerTier,
        actualMoveIds: Map<UUID, Set<String>>,
        ignoredRevealPokemonIds: Set<UUID> = emptySet(),
    ): List<BattleOpponentMoveInferenceView> {
        val currentIdentities = state.pokemon.asSequence()
            .filter { it.side == BattleSide.OPPONENT && !it.fainted }
            .associate { pokemon -> pokemon.battlePokemonId to PokemonIdentity.from(pokemon) }
        val reusable = byPokemon.filterKeys { pokemonId ->
            identities[pokemonId] == currentIdentities[pokemonId]
        }
        val updated = BattleOpponentMoveInferenceNormalizer.normalize(
            state, catalog, tier, actualMoveIds, reusable, ignoredRevealPokemonIds, moveDetails,
        )
        byPokemon = updated.associateBy { it.battlePokemonId }
        identities = currentIdentities.filterKeys(byPokemon::containsKey)
        return updated
    }

    private data class PokemonIdentity(
        val speciesId: String,
        val formId: String?,
    ) {
        companion object {
            fun from(pokemon: BattlePokemonStateView) = PokemonIdentity(
                canonicalIdentityPart(pokemon.speciesId),
                pokemon.formId?.let(::canonicalIdentityPart),
            )

            private fun canonicalIdentityPart(value: String): String =
                value.substringAfter(':').lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
        }
    }
}
