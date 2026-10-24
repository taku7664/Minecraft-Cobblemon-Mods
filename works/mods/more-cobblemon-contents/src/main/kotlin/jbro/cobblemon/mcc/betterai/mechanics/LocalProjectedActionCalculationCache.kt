package jbro.cobblemon.mcc.betterai.mechanics

import java.util.UUID
import jbro.cobblemon.mcc.betterai.calculation.LocalDecisionCalculationScope.Identity
import jbro.cobblemon.mcc.betterai.evaluation.LocalActiveTuning
import jbro.cobblemon.mcc.betterai.matchup.LocalMatchupScoreCalculator.ScoredMove
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.BattleTargetSlot
import jbro.cobblemon.mcc.internal.ai.BattlePublicActionCatalogView
import jbro.cobblemon.mcc.internal.ai.BattlePublicMoveOptionView
import jbro.cobblemon.mcc.internal.ai.BattleMoveCandidateView
import jbro.cobblemon.mcc.internal.ai.BattleDamageFractionRange
import jbro.cobblemon.mcc.internal.ai.BattleFractionRange
import jbro.cobblemon.mcc.internal.ai.BattlePublicMoveCandidatePoolView
import jbro.cobblemon.mcc.internal.ai.BattleOpponentMoveInferenceView

/**
 * Reuses an equal projected state's public tactical calculation within one search.
 *
 * Keyed structurally rather than by object identity. Projection allocates a fresh state every time, so
 * identity meant two positions that were the same in every respect the calculation depends on shared
 * nothing, and the most expensive step in the search - a full public tactical calculation per damaging
 * move per side per leaf - ran again for each of them.
 */
internal class LocalProjectedActionCalculationCache(
    /** Shared with the search's own memo so a state is fingerprinted once per decision, not once per use. */
    val fingerprints: LocalBattleStateFingerprint = LocalBattleStateFingerprint(),
) : AutoCloseable {
    private val byState = HashMap<LocalBattleStateFingerprint.Key, MutableMap<ActionKey, BattleDecisionContext>>()

    /** Whether the matchup exchanges scored with this cache read recovery moves (LocalDecisionTuning.matchupRecovery). */
    internal var matchupRecovery: Boolean = false

    /**
     * What a leaf reads from one cached calculation: the same answer every time that calculation comes
     * back, so it is worked out once. Keyed by the cached result itself.
     */
    internal class LeafAttack(
        val nullified: Boolean,
        val damageMultiplier: Double,
        val accuracy: Double,
        val targetHpFraction: Double?,
        val damageRange: BattleDamageFractionRange?,
        val knockoutRange: BattleFractionRange?,
    )

    private val leafAttacks = java.util.IdentityHashMap<BattleDecisionContext, LeafAttack>()

    /** A cached reading that may be absent (the pair could not be scored), so a miss and a null differ. */
    internal class Cached(val value: Pair<Double, Double>?)

    /** The leaf's matchup pairs (LocalLeafMatchups), keyed by what the exchange reads. */
    internal val leafMatchups = HashMap<Any, Cached>()

    internal fun leafAttack(calculated: BattleDecisionContext, compute: () -> LeafAttack): LeafAttack =
        leafAttacks.getOrPut(calculated, compute)

    var calculationsPerformed: Int = 0
        private set

    /**
     * Calculations an identity-keyed cache would have performed instead.
     *
     * Counted by remembering which (state object, action) pairs have been asked for. The gap between
     * this and [calculationsPerformed] is exactly what structural keying saves, which is worth having
     * as a number rather than as a wall-clock impression. Counted only while [countIdentityKeying] is on:
     * the count keeps every state asked about and costs two hash operations per call.
     */
    var calculationsUnderIdentityKeying: Int = 0
        private set

    private val byStateIdentity = java.util.IdentityHashMap<BattleStateView, MutableSet<ActionKey>>()
    private val catalogKeys = java.util.IdentityHashMap<BattlePublicActionCatalogView, CatalogKey>()
    private val slotActionsByInputs = HashMap<SlotActionsKey, List<BattleActionCandidate>>()
    private val matchupMoves = HashMap<MatchupMoveKey, List<ScoredMove>>()
    private val firstHitMoves = HashMap<MatchupMoveKey, List<ScoredMove>>()
    internal data class HpReading(val calculated: BattleDecisionContext, val rawRolls: List<Double>, val accuracy: Double,
        val sourceHp: Double)
    private data class HpKey(val state: LocalBattleStateFingerprint.Key, val action: Any, val target: UUID, val catalog: CatalogKey)
    private val hpReadings = HashMap<HpKey, HpReading>()

    /** Only living HP values are erased from this key. Eligibility checks guard HP-dependent damage; every other input stays exact. */
    private fun hpKey(position: BattleDecisionContext, action: BattleActionCandidate, target: UUID): HpKey {
        val state = fingerprints.keyWithoutHp(position.state)
        return HpKey(state, actionInputKey(action), target, catalogKey(position.publicActionCatalog))
    }
    internal fun hpReading(position: BattleDecisionContext, action: BattleActionCandidate, target: UUID): HpReading? =
        hpReadings[hpKey(position, action, target)]

    internal fun rememberHpReading(position: BattleDecisionContext, action: BattleActionCandidate, target: UUID, reading: HpReading) {
        if (hpReadings.size >= MAXIMUM_MATCHUP_ENTRIES) hpReadings.clear()
        hpReadings[hpKey(position, action, target)] = reading
    }
    private class MatchupEntry(val result: Pair<BattleStateView, BattlePublicActionCatalogView>?)
    private val matchupEntries = HashMap<MatchupEntryKey, MatchupEntry>()

    var matchupEntriesBuilt: Int = 0
        private set
    var matchupEntryHits: Int = 0
        private set

    /** Entry hazards/abilities are projected once for each identical virtual entry. */
    internal fun matchupEntry(source: BattleDecisionContext, pokemonId: UUID, slot: Int?,
        build: () -> BattleDecisionContext?): BattleDecisionContext? {
        val key = MatchupEntryKey(fingerprints.keyOf(source.state), pokemonId, slot,
            catalogKey(source.publicActionCatalog),
            Identity(
                LocalActiveTuning.current()))
        val cached = matchupEntries[key]
        val entry = if (cached != null) { matchupEntryHits++; cached } else {
            matchupEntriesBuilt++
            MatchupEntry(build()?.let { it.state to it.publicActionCatalog }).also {
                if (matchupEntries.size >= MAXIMUM_MATCHUP_ENTRIES) matchupEntries.clear()
                matchupEntries[key] = it
            }
        }
        return entry.result?.let { (state, catalog) -> source.copy(state = state, publicActionCatalog = catalog) }
    }

    private data class MatchupEntryKey(
        val state: LocalBattleStateFingerprint.Key, val pokemon: UUID, val slot: Int?, val catalog: CatalogKey,
        val tuning: Identity,
    )

    var matchupMoveListsBuilt: Int = 0
        private set
    var matchupMoveListHits: Int = 0
        private set

    /** Reuse the complete move reading, not just the public damage calculation inside it. */
    internal fun matchupMoves(
        position: BattleDecisionContext, userId: UUID, targetId: UUID,
        build: () -> List<ScoredMove>,
    ): List<ScoredMove> {
        val tuning = LocalActiveTuning.current()
        // Evolving projections also depend on the remaining deadline and the recursion guard.
        if (tuning.evolvingMatchups) return build()
        val key = MatchupMoveKey(fingerprints.keyOf(position.state), userId, targetId,
            catalogKey(position.publicActionCatalog),
            Identity(tuning), matchupRecovery)
        matchupMoves[key]?.let { matchupMoveListHits++; return it }
        matchupMoveListsBuilt++
        return build().also {
            if (matchupMoves.size >= MAXIMUM_MATCHUP_ENTRIES) matchupMoves.clear()
            matchupMoves[key] = it
        }
    }

    /** First-use readers share a completed full table, but never publish a short table as a full one. */
    internal fun firstHitMoves(position: BattleDecisionContext, userId: UUID, targetId: UUID,
        build: () -> List<ScoredMove>): List<ScoredMove> {
        val tuning = LocalActiveTuning.current()
        val key = MatchupMoveKey(fingerprints.keyOf(position.state), userId, targetId,
            catalogKey(position.publicActionCatalog), Identity(tuning), matchupRecovery)
        matchupMoves[key]?.let {
            jbro.cobblemon.mcc.betterai.matchup.LocalHpMatchupAudit.firstHitFullCacheHit()
            return it
        }
        firstHitMoves[key]?.let { return it }
        return build().also {
            if (firstHitMoves.size >= MAXIMUM_MATCHUP_ENTRIES) firstHitMoves.clear()
            firstHitMoves[key] = it
        }
    }

    private val forecastSeeds = HashMap<MatchupMoveKey, Any>()
    @Suppress("UNCHECKED_CAST")
    internal fun <T : Any> forecastSeed(position: BattleDecisionContext, user: UUID, target: UUID, build: () -> T): T {
        val key = MatchupMoveKey(fingerprints.keyOf(position.state), user, target, catalogKey(position.publicActionCatalog),
            Identity(LocalActiveTuning.current()), matchupRecovery)
        return (forecastSeeds[key] as? T) ?: build().also {
            if (forecastSeeds.size >= MAXIMUM_MATCHUP_ENTRIES) forecastSeeds.clear()
            forecastSeeds[key] = it
        }
    }

    private data class MatchupMoveKey(
        val state: LocalBattleStateFingerprint.Key, val user: UUID, val target: UUID, val catalog: CatalogKey,
        val tuning: Identity,
        val recovery: Boolean,
    )

    var slotActionListsBuilt: Int = 0
        private set

    /**
     * A side's single-slot actions, shared by every position with the same action inputs
     * ([LocalBattleStateFingerprint.ofActionInputs]): the lists a leaf evaluation builds for positions
     * a damage roll apart are identical, and building them was a quarter of a Boss search.
     */
    fun slotActions(
        state: BattleStateView,
        side: BattleSide,
        catalog: BattlePublicActionCatalogView,
        includeMoveHypotheses: Boolean,
        build: () -> List<BattleActionCandidate>,
    ): List<BattleActionCandidate> = slotActionsByInputs.getOrPut(
        SlotActionsKey(fingerprints.keyOfActionInputs(state), side, catalogKey(catalog), includeMoveHypotheses),
    ) {
        slotActionListsBuilt++
        build()
    }

    private fun catalogKey(source: BattlePublicActionCatalogView): CatalogKey = catalogKeys.getOrPut(source) {
        fun entries(values: List<jbro.cobblemon.mcc.internal.ai.BattlePokemonActionCatalogView>) =
            values.map { CatalogEntryKey(it.battlePokemonId, it.moves, it.moveSetComplete) }
        CatalogKey(entries(source.entries), entries(source.originalEntries), source.candidatePools, source.opponentMoveInferences)
    }

    fun getOrCalculate(
        state: BattleStateView,
        side: BattleSide,
        action: BattleActionCandidate,
        catalog: BattlePublicActionCatalogView? = null,
        calculation: () -> BattleDecisionContext,
    ): BattleDecisionContext {
        val catalogKey = catalog?.let(::catalogKey)
        val key = ActionKey(action, side, catalogKey,
            (actionHashes.getOrPut(action) { actionHash(action) } * 31 + side.hashCode()) * 31 + catalogKey.hashCode())
        if (countIdentityKeying && byStateIdentity.getOrPut(state) { HashSet() }.add(key)) calculationsUnderIdentityKeying++
        val stateEntries = byState.getOrPut(fingerprints.keyOf(state)) { HashMap() }
        return stateEntries.getOrPut(key) {
            calculationsPerformed++
            calculation()
        }
    }

    /**
     * An action's calculation inputs, compared field by field but hashed once: the hash used to walk the move's
     * details and the whole catalog on every lookup, a few percent of a doubles search.
     */
    /** Full structural action inputs; shared scope keys must not rely on the action id alone. */
    internal fun actionInputKey(action: BattleActionCandidate): Any = ActionKey(action, BattleSide.ALLY, null,
        actionHashes.getOrPut(action) { actionHash(action) })

    private class ActionKey(
        val action: BattleActionCandidate,
        val side: BattleSide,
        val catalog: CatalogKey?,
        private val hash: Int,
    ) {
        override fun hashCode(): Int = hash
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ActionKey || other.hash != hash || other.side != side || other.catalog != catalog) return false
            return sameInputs(action, other.action)
        }

        private fun sameInputs(a: BattleActionCandidate, b: BattleActionCandidate): Boolean {
            val am = a.mechanic
            val bm = b.mechanic
            val sameMechanic = am === bm || am != null && bm != null &&
                am.mechanicId == bm.mechanicId && am.target == bm.target && am.publicCost == bm.publicCost &&
                am.transformedMoveId == bm.transformedMoveId && am.transformedActorTypeIds == bm.transformedActorTypeIds &&
                am.transformedActorCombatStats == bm.transformedActorCombatStats
            return a === b || a.actionId == b.actionId && a.kind == b.kind && a.actorSlot == b.actorSlot &&
                a.moveSlot == b.moveSlot && a.moveId == b.moveId && a.targets == b.targets &&
                a.switchPokemonId == b.switchPokemonId && sameMechanic &&
                a.moveDetails == b.moveDetails && a.facts == b.facts && a.tags == b.tags &&
                a.componentActionIds == b.componentActionIds && a.componentActions.size == b.componentActions.size &&
                a.componentActions.zip(b.componentActions).all { (first, second) -> sameInputs(first, second) }
        }
    }

    private val actionHashes = java.util.IdentityHashMap<BattleActionCandidate, Int>()

    private fun actionHash(action: BattleActionCandidate): Int = listOf(
        action.actionId, action.kind, action.actorSlot, action.moveSlot, action.moveId, action.targets,
        action.switchPokemonId, action.mechanic?.let { listOf(it.mechanicId, it.target, it.publicCost,
            it.transformedMoveId, it.transformedActorTypeIds, it.transformedActorCombatStats) },
        action.moveDetails, action.facts, action.tags, action.componentActionIds,
        action.componentActions.map { component -> actionHashes.getOrPut(component) { actionHash(component) } },
    ).hashCode()
    private data class SlotActionsKey(
        val state: LocalBattleStateFingerprint.Key,
        val side: BattleSide,
        val catalog: CatalogKey,
        val includeMoveHypotheses: Boolean,
    )
    private data class CatalogEntryKey(val id: UUID, val moves: List<BattlePublicMoveOptionView>, val complete: Boolean)
    /** Hashed once; one is made per catalog object and every lookup of it hashed the whole catalog. */
    private class CatalogKey(val current: List<CatalogEntryKey>, val original: List<CatalogEntryKey>,
        val pools: List<BattlePublicMoveCandidatePoolView>, val inferences: List<BattleOpponentMoveInferenceView>) {
        private val hash = ((current.hashCode() * 31 + original.hashCode()) * 31 + pools.hashCode()) * 31 + inferences.hashCode()
        override fun hashCode(): Int = hash
        override fun equals(other: Any?): Boolean =
            this === other || other is CatalogKey && other.hash == hash && other.current == current && other.original == original &&
                other.pools == pools && other.inferences == inferences
    }

    /** Release references at the last use rather than waiting for the surrounding decision. */
    override fun close() {
        byState.clear()
        leafAttacks.clear()
        leafMatchups.clear()
        byStateIdentity.clear()
        catalogKeys.clear()
        slotActionsByInputs.clear()
        matchupMoves.clear()
        firstHitMoves.clear()
        hpReadings.clear()
        matchupEntries.clear()
        forecastSeeds.clear()
        actionHashes.clear()
        fingerprints.clear()
    }

    companion object {
        private const val MAXIMUM_MATCHUP_ENTRIES = 4096

        /** Measurement switch for [calculationsUnderIdentityKeying]; off in play. */
        @Volatile
        internal var countIdentityKeying: Boolean = false
    }
}
