package jbro.cobblemon.mcc.internal.ai

import java.util.Locale
import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.*

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
        rebuildSlotPreferences: Map<UUID, BattleOpponentMoveInferenceView> = emptyMap(),
        ignoredRevealPokemonIds: Set<UUID> = emptySet(),
        moveDetails: (String) -> BattleMoveCandidateView?,
        /** The board's reading of an attack's own stat changes ([BattleOwnStageValue]); null keeps the set-level values. */
        ownStages: BattleOwnStageValue? = null,
    ): List<BattleOpponentMoveInferenceView> {
        val policy = BattleOpponentMoveInferencePolicies.forTier(tier)
        val outer = boardStages.get()
        boardStages.set(ownStages)
        try {
            return normalizeOnBoard(state, catalog, policy, actualMoveIds, previous, rebuildSlotPreferences,
                ignoredRevealPokemonIds, moveDetails)
        } finally {
            boardStages.set(outer)
        }
    }

    /** The board reading for the inference running on this thread (one normalize call at a time per thread). */
    private val boardStages = ThreadLocal<BattleOwnStageValue?>()

    private fun normalizeOnBoard(
        state: BattleStateView,
        catalog: BattlePublicActionCatalogView,
        policy: BattleOpponentMoveInferencePolicy,
        actualMoveIds: Map<UUID, Set<String>>,
        previous: Map<UUID, BattleOpponentMoveInferenceView>,
        rebuildSlotPreferences: Map<UUID, BattleOpponentMoveInferenceView>,
        ignoredRevealPokemonIds: Set<UUID>,
        moveDetails: (String) -> BattleMoveCandidateView?,
    ): List<BattleOpponentMoveInferenceView> {
        return state.pokemon.asSequence()
            .filter { it.side == BattleSide.OPPONENT && !it.fainted }
            .sortedBy { it.battlePokemonId.toString() }
            .map { pokemon ->
                val prior = previous[pokemon.battlePokemonId]
                if (prior == null) {
                    initialInference(
                        pokemon,
                        catalog,
                        state.format,
                        policy,
                        actualMoveIds[pokemon.battlePokemonId].orEmpty(),
                        moveDetails,
                        includePublicReveals = pokemon.battlePokemonId !in ignoredRevealPokemonIds,
                        slotPreference = rebuildSlotPreferences[pokemon.battlePokemonId],
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
        format: BattleFormat,
        policy: BattleOpponentMoveInferencePolicy,
        actualMoveIds: Set<String>,
        moveDetails: (String) -> BattleMoveCandidateView?,
        includePublicReveals: Boolean,
        slotPreference: BattleOpponentMoveInferenceView?,
    ): BattleOpponentMoveInferenceView {
        val slots = mutableListOf<BattleOpponentMoveSlotView>()
        val revealed = if (includePublicReveals) revealedMoves(pokemon, catalog, moveDetails) else emptyList()
        revealed.forEach { (moveId, details) ->
            addConcrete(slots, moveId, details, group(pokemon, details, moveId), BattleOpponentMoveKnowledge.CONFIRMED,
                BattleOpponentMoveSource.PUBLIC_REVEAL)
        }

        val resolvedActual = if (policy.readsHiddenSet) {
            actualMoveIds.mapNotNull { id -> moveDetails(id)?.let { id to it } }
        } else {
            emptyList()
        }
        val unrevealedActual = resolvedActual.filterNot { (id, _) -> slots.containsMove(id) }
        selectHiddenStab(unrevealedActual, pokemon, format, policy.confirmedHiddenStabSlots)
            .forEach { (id, details) ->
            addConcrete(slots, id, details, BattleOpponentMoveGroup.STAB_ATTACK,
                BattleOpponentMoveKnowledge.CONFIRMED, BattleOpponentMoveSource.DIFFICULTY_SET_READ)
        }
        selectHiddenSetup(unrevealedActual, pokemon, policy.confirmedHiddenSetupSlots)
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
            fillPreservingActualShape(slots, pokemon, format, resolvedActual, learnset, policy)
        } else {
            fillFixedPolicy(slots, pokemon, format, learnset, policy)
        }
        fillGuesses(slots, BattleOpponentMoveGroup.OTHER, MAX_MOVE_SLOTS - slots.size)
        return BattleOpponentMoveInferenceView(
            pokemon.battlePokemonId,
            preservePublicRevealSlots(slots, slotPreference, revealed.mapTo(linkedSetOf()) { it.first }),
        )
    }

    /**
     * A type or form transition invalidates inferred groups, but it does not erase which logical
     * slot a publicly revealed move already occupied. Rebuild every non-public slot from the new
     * public identity and then put surviving public reveals back in their stable slots.
     */
    private fun preservePublicRevealSlots(
        fresh: List<BattleOpponentMoveSlotView>,
        preference: BattleOpponentMoveInferenceView?,
        revealedMoveIds: Set<String>,
    ): List<BattleOpponentMoveSlotView> {
        preference ?: return fresh
        val placed = arrayOfNulls<BattleOpponentMoveSlotView>(MAX_MOVE_SLOTS)
        val placedMoveIds = linkedSetOf<String>()
        preference.slots.asSequence()
            .filter { it.knowledge == BattleOpponentMoveKnowledge.CONFIRMED }
            .filter { it.source == BattleOpponentMoveSource.PUBLIC_REVEAL }
            .filter { preferred -> revealedMoveIds.any { sameMove(preferred.moveId, it) } }
            .forEach { preferred ->
                val refreshed = fresh.singleOrNull { candidate ->
                    preferred.moveId?.let { sameMove(candidate.moveId, it) } == true
                } ?: return@forEach
                placed[preferred.slot] = refreshed.copy(slot = preferred.slot)
                refreshed.moveId?.let(placedMoveIds::add)
            }
        val remaining = fresh.filterNot { candidate ->
            candidate.moveId?.let { move -> placedMoveIds.any { sameMove(it, move) } } == true
        }.iterator()
        placed.indices.forEach { slot ->
            if (placed[slot] == null && remaining.hasNext()) {
                placed[slot] = remaining.next().copy(slot = slot)
            }
        }
        check(placed.all { it != null } && !remaining.hasNext()) {
            "Rebuilt opponent move slots must remain a complete four-slot inference"
        }
        return placed.map { requireNotNull(it) }
    }

    private fun fillFixedPolicy(
        slots: MutableList<BattleOpponentMoveSlotView>,
        pokemon: BattlePokemonStateView,
        format: BattleFormat,
        learnset: Map<String, BattleMoveCandidateView>,
        policy: BattleOpponentMoveInferencePolicy,
    ) {
        addMissingStab(slots, pokemon, format, learnset)
        addExpected(slots, ranked(learnset, pokemon, format, BattleOpponentMoveGroup.COVERAGE_ATTACK),
            BattleOpponentMoveGroup.COVERAGE_ATTACK, policy.expectedCoverageSlots)
        fillGuesses(slots, BattleOpponentMoveGroup.STATUS_OTHER, policy.guessedStatusSlots)
    }

    private fun fillPreservingActualShape(
        slots: MutableList<BattleOpponentMoveSlotView>,
        pokemon: BattlePokemonStateView,
        format: BattleFormat,
        actual: List<Pair<String, BattleMoveCandidateView>>,
        learnset: Map<String, BattleMoveCandidateView>,
        policy: BattleOpponentMoveInferencePolicy,
    ) {
        // Every STAB type the actual set carries has its STAB attack before anything else (user rule 2026-10-18).
        val actualStabTypes = actual.filter { (id, details) -> details.damageCategory != BattleMoveDamageCategory.STATUS &&
            canonical(BattleMoveSetExpectation.typeId(id, details, pokemon)) in
                (pokemon.knownBaseStabTypeIds + listOfNotNull(pokemon.knownTeraTypeId)).map(::canonical) }
            .mapTo(hashSetOf()) { (id, details) -> canonical(BattleMoveSetExpectation.typeId(id, details, pokemon)) }
        addMissingStab(slots, pokemon, format, learnset, actualStabTypes)
        val actualAttackCount = actual.count { (_, details) -> details.damageCategory != BattleMoveDamageCategory.STATUS }
        val actualStatusCount = actual.count { (_, details) -> details.damageCategory == BattleMoveDamageCategory.STATUS }
        val currentAttackCount = slots.count { it.group.isAttack() }
        val currentStatusCount = slots.count { !it.group.isAttack() }
        val statusGuesses = (actualStatusCount - currentStatusCount).coerceAtLeast(0)
        if (policy.readsHiddenStatusCategories) {
            fillCategorizedStatusGuesses(slots, actual, statusGuesses)
        } else {
            fillGuesses(slots, BattleOpponentMoveGroup.STATUS_OTHER, statusGuesses)
        }

        val attacksNeeded = (actualAttackCount - currentAttackCount).coerceAtLeast(0)
        val coverage = ranked(learnset, pokemon, format, BattleOpponentMoveGroup.COVERAGE_ATTACK)
        val stab = ranked(learnset, pokemon, format, BattleOpponentMoveGroup.STAB_ATTACK)
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
            val moveGroup = group(pokemon, details, moveId)
            val category = BattleStatusMoveCategories.classify(moveId, details)
            val exact = slots.indexOfFirst { sameMove(it.moveId, moveId) }
            val replacement = when {
                exact >= 0 -> exact
                else -> slots.indexOfFirst {
                    it.knowledge == BattleOpponentMoveKnowledge.GUESS && it.group == moveGroup &&
                        category != null && it.statusCategory == category
                }.takeIf { it >= 0 }
                    ?: slots.indexOfFirst {
                        it.knowledge == BattleOpponentMoveKnowledge.GUESS && it.group == moveGroup && it.statusCategory == null
                    }.takeIf { it >= 0 }
                    ?: slots.indexOfFirst { it.knowledge == BattleOpponentMoveKnowledge.GUESS && it.group == moveGroup }
                    .takeIf { it >= 0 }
                    ?: slots.indices.firstOrNull { slots[it].knowledge == BattleOpponentMoveKnowledge.EXPECTED &&
                        slots[it].group == moveGroup && !soleStab(slots, it, moveId, details, pokemon) }
                    ?: slots.indices.firstOrNull { slots[it].knowledge == BattleOpponentMoveKnowledge.EXPECTED &&
                        !soleStab(slots, it, moveId, details, pokemon) }
                    ?: slots.indexOfFirst { it.knowledge == BattleOpponentMoveKnowledge.GUESS }
                        .takeIf { it >= 0 }
                    ?: slots.indexOfFirst { it.knowledge == BattleOpponentMoveKnowledge.EXPECTED }
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

    /**
     * A STAB attack for every type [pokemon] attacks with that no slot carries yet, the strongest of each type first:
     * always inferred, ahead of sub-weapons and guessed status slots. With one STAB slot, an ADVANCED Lucario read its
     * Flash Cannon and filled the rest with Extreme Speed and Trailblaze, so a Mega Tyranitar (4x weak to Fighting)
     * read as beating it (2026-10-18, user rule: "추론은 무조건 자속기는 다 넣어야해").
     */
    private fun addMissingStab(
        slots: MutableList<BattleOpponentMoveSlotView>,
        pokemon: BattlePokemonStateView,
        format: BattleFormat,
        learnset: Map<String, BattleMoveCandidateView>,
        /**
         * For a tier that reads the hidden set, the STAB types the actual set carries: an ADVANCED or BOSS trainer that
         * knows the set has one STAB type assumes no other (user rule 2026-10-18). Null: every type.
         */
        actualStabTypes: Set<String>? = null,
    ) {
        fun type(id: String?, details: BattleMoveCandidateView) = canonical(BattleMoveSetExpectation.typeId(id, details, pokemon))
        val covered = slots.filter { it.group.isAttack() }.mapNotNullTo(hashSetOf()) { slot -> slot.details?.let { type(slot.moveId, it) } }
        val types = (pokemon.knownBaseStabTypeIds + listOfNotNull(pokemon.knownTeraTypeId)).map(::canonical).distinct()
            .filter { actualStabTypes == null || it in actualStabTypes }
        learnset.entries.asSequence()
            .map { it.key to it.value }
            .filter { (id, details) -> details.damageCategory != BattleMoveDamageCategory.STATUS &&
                BattleMoveSetExpectation.factor(id, details) > 0.0 }
            .sortedWith(moveComparator(pokemon, format))
            .filter { (id, details) -> type(id, details) in types && type(id, details) !in covered && !slots.containsMove(id) }
            .distinctBy { (id, details) -> type(id, details) }
            .forEach { (id, details) ->
                addConcrete(slots, id, details, BattleOpponentMoveGroup.STAB_ATTACK, BattleOpponentMoveKnowledge.EXPECTED,
                    BattleOpponentMoveSource.LEARNSET_EXPECTATION)
            }
    }

    /** An expected STAB slot that is the only one of its type: a reveal of another type does not take it. */
    private fun soleStab(
        slots: List<BattleOpponentMoveSlotView>,
        index: Int,
        revealedId: String,
        revealed: BattleMoveCandidateView,
        pokemon: BattlePokemonStateView,
    ): Boolean {
        // Types as the user attacks with them: a Terastallized user's Tera Blast is its Tera type.
        fun type(slot: BattleOpponentMoveSlotView) = slot.details?.let { canonical(BattleMoveSetExpectation.typeId(slot.moveId, it, pokemon)) }
        val slot = slots[index]
        val type = type(slot) ?: return false
        if (slot.group != BattleOpponentMoveGroup.STAB_ATTACK ||
            canonical(BattleMoveSetExpectation.typeId(revealedId, revealed, pokemon)) == type) return false
        return slots.indices.none { it != index && slots[it].group.isAttack() && type(slots[it]) == type }
    }

    private fun selectHiddenStab(
        actual: List<Pair<String, BattleMoveCandidateView>>,
        pokemon: BattlePokemonStateView,
        format: BattleFormat,
        limit: Int,
    ): List<Pair<String, BattleMoveCandidateView>> = actual.asSequence()
        .filter { (id, details) -> group(pokemon, details, id) == BattleOpponentMoveGroup.STAB_ATTACK }
        .sortedWith(moveComparator(pokemon, format))
        .distinctBy { (id, details) -> canonical(BattleMoveSetExpectation.typeId(id, details, pokemon)) }
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
        format: BattleFormat,
        wanted: BattleOpponentMoveGroup,
    ): List<Pair<String, BattleMoveCandidateView>> = moves.entries.asSequence()
        .filter { (id, details) -> group(pokemon, details, id) == wanted }
        // An attack a set cannot count on (Focus Punch, Steel Roller) is never assumed (BattleMoveSetExpectation).
        .filter { (id, details) -> details.damageCategory == BattleMoveDamageCategory.STATUS || BattleMoveSetExpectation.factor(id, details) > 0.0 }
        .map { it.key to it.value }
        .sortedWith(moveComparator(pokemon, format))
        .toList()

    private fun moveComparator(
        pokemon: BattlePokemonStateView,
        format: BattleFormat,
    ) = compareByDescending<Pair<String, BattleMoveCandidateView>> {
        BattleOpponentMoveExpectationScorer.score(pokemon, it.second, format, it.first, boardStages.get())
    }.thenBy { canonical(it.first) }

    internal fun group(
        pokemon: BattlePokemonStateView,
        details: BattleMoveCandidateView,
        /** Its id, so a publicly Terastallized user's Tera Blast reads as its Tera type. */
        moveId: String? = null,
    ): BattleOpponentMoveGroup = when {
        details.damageCategory != BattleMoveDamageCategory.STATUS -> {
            val offensiveTypes = pokemon.knownBaseStabTypeIds + listOfNotNull(pokemon.knownTeraTypeId)
            val typeId = BattleMoveSetExpectation.typeId(moveId, details, pokemon)
            if (offensiveTypes.any { canonical(it) == canonical(typeId) }) {
                BattleOpponentMoveGroup.STAB_ATTACK
            } else {
                BattleOpponentMoveGroup.COVERAGE_ATTACK
            }
        }
        isPureSetup(details) -> BattleOpponentMoveGroup.PURE_SETUP
        details.damageCategory == BattleMoveDamageCategory.STATUS -> BattleOpponentMoveGroup.STATUS_OTHER
        else -> BattleOpponentMoveGroup.OTHER
    }

    private fun isPureSetup(details: BattleMoveCandidateView): Boolean =
        BattleStatusMoveCategories.isPureSelfSetup(details)

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

    /**
     * One guess per hidden status move that no concrete slot already represents, labelled with that
     * move's group and category only. The move's name stays behind this boundary.
     */
    private fun fillCategorizedStatusGuesses(
        slots: MutableList<BattleOpponentMoveSlotView>,
        actual: List<Pair<String, BattleMoveCandidateView>>,
        count: Int,
    ) {
        val before = slots.size
        actual.asSequence()
            .filter { (id, details) -> details.damageCategory == BattleMoveDamageCategory.STATUS && !slots.containsMove(id) }
            .sortedBy { canonical(it.first) }
            .take(minOf(count, MAX_MOVE_SLOTS - slots.size))
            .forEach { (id, details) ->
                val category = BattleStatusMoveCategories.classify(id, details)
                slots += BattleOpponentMoveSlotView(
                    slot = slots.size,
                    moveId = null,
                    group = if (category == null) BattleOpponentMoveGroup.PURE_SETUP else BattleOpponentMoveGroup.STATUS_OTHER,
                    knowledge = BattleOpponentMoveKnowledge.GUESS,
                    source = BattleOpponentMoveSource.GROUP_GUESS,
                    statusCategory = category,
                )
            }
        // Unresolvable hidden moves still occupy their slots as uncategorized guesses.
        fillGuesses(slots, BattleOpponentMoveGroup.STATUS_OTHER, count - (slots.size - before))
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
    ) = BattleOpponentMoveSlotView(
        slot, moveId, group, knowledge, source, details,
        statusCategory = if (group == BattleOpponentMoveGroup.STATUS_OTHER) {
            BattleStatusMoveCategories.classify(moveId, details)
        } else {
            null
        },
    )

    private fun List<BattleOpponentMoveSlotView>.containsMove(moveId: String): Boolean =
        any { sameMove(it.moveId, moveId) }

    private fun BattleOpponentMoveGroup.isAttack(): Boolean =
        this == BattleOpponentMoveGroup.STAB_ATTACK || this == BattleOpponentMoveGroup.COVERAGE_ATTACK

    private fun sameMove(left: String?, right: String): Boolean = left != null && canonical(left) == canonical(right)
    private fun canonical(value: String): String =
        PublicIds.canonical(value)

    private const val MAX_MOVE_SLOTS = 4
}

/** Per-battle persistence; deterministic slots survive until new public evidence changes them. */
class BattleOpponentMoveInferenceLedger(
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
        ownStages: BattleOwnStageValue? = null,
    ): List<BattleOpponentMoveInferenceView> {
        val currentIdentities = state.pokemon.asSequence()
            .filter { it.side == BattleSide.OPPONENT && !it.fainted }
            .associate { pokemon -> pokemon.battlePokemonId to PokemonIdentity.from(pokemon) }
        val reusable = byPokemon.filterKeys { pokemonId ->
            pokemonId in currentIdentities && (
                pokemonId in ignoredRevealPokemonIds || identities[pokemonId] == currentIdentities[pokemonId]
            )
        }
        val rebuildSlotPreferences = byPokemon.filterKeys { pokemonId ->
            pokemonId !in ignoredRevealPokemonIds &&
                pokemonId in currentIdentities && identities[pokemonId] != currentIdentities[pokemonId]
        }
        val updated = BattleOpponentMoveInferenceNormalizer.normalize(
            state = state,
            catalog = catalog,
            tier = tier,
            actualMoveIds = actualMoveIds,
            previous = reusable,
            rebuildSlotPreferences = rebuildSlotPreferences,
            ignoredRevealPokemonIds = ignoredRevealPokemonIds,
            moveDetails = moveDetails,
            ownStages = ownStages,
        )
        byPokemon = updated.associateBy { it.battlePokemonId }
        identities = currentIdentities.filterKeys(byPokemon::containsKey)
        return updated
    }

    private data class PokemonIdentity(
        val speciesId: String,
        val formId: String?,
        val typeIds: List<String>,
        val baseStabTypeIds: List<String>,
        val teraTypeId: String?,
    ) {
        companion object {
            fun from(pokemon: BattlePokemonStateView) = PokemonIdentity(
                canonicalIdentityPart(pokemon.speciesId),
                pokemon.formId?.let(::canonicalIdentityPart),
                pokemon.knownTypeIds.map(::canonicalIdentityPart).sorted(),
                pokemon.knownBaseStabTypeIds.map(::canonicalIdentityPart).sorted(),
                pokemon.knownTeraTypeId?.let(::canonicalIdentityPart),
            )

            private fun canonicalIdentityPart(value: String): String =
                PublicIds.canonical(value)
        }
    }
}
