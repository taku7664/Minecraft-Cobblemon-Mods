package jbro.cobblemon.mcc.betterai.mechanics

import java.util.UUID
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
) {
    private val byState = HashMap<String, MutableMap<ActionKey, BattleDecisionContext>>()

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
        SlotActionsKey(fingerprints.ofActionInputs(state), side, catalogKey(catalog), includeMoveHypotheses),
    ) {
        slotActionListsBuilt++
        build()
    }

    private fun catalogKey(source: BattlePublicActionCatalogView): CatalogKey = catalogKeys.getOrPut(source) {
        fun entries(values: List<jbro.cobblemon.mcc.internal.ai.BattlePokemonActionCatalogView>) =
            values.map { CatalogEntryKey(it.battlePokemonId, it.moves, it.moveSetComplete) }
        CatalogKey(entries(source.entries), entries(source.originalEntries))
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
        val stateEntries = byState.getOrPut(fingerprints.of(state)) { HashMap() }
        return stateEntries.getOrPut(key) {
            calculationsPerformed++
            calculation()
        }
    }

    /**
     * An action's calculation inputs, compared field by field but hashed once: the hash used to walk the move's
     * details and the whole catalog on every lookup, a few percent of a doubles search.
     */
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
            val a = action
            val b = other.action
            return a === b || a.actionId == b.actionId && a.kind == b.kind && a.actorSlot == b.actorSlot &&
                a.moveSlot == b.moveSlot && a.moveId == b.moveId && a.targets == b.targets &&
                a.switchPokemonId == b.switchPokemonId && a.mechanic?.mechanicId == b.mechanic?.mechanicId &&
                a.moveDetails == b.moveDetails && a.tags == b.tags
        }
    }

    private val actionHashes = java.util.IdentityHashMap<BattleActionCandidate, Int>()

    private fun actionHash(action: BattleActionCandidate): Int = listOf(
        action.actionId, action.kind, action.actorSlot, action.moveSlot, action.moveId, action.targets,
        action.switchPokemonId, action.mechanic?.mechanicId, action.moveDetails, action.tags,
    ).hashCode()
    private data class SlotActionsKey(
        val state: String,
        val side: BattleSide,
        val catalog: CatalogKey,
        val includeMoveHypotheses: Boolean,
    )
    private data class CatalogEntryKey(val id: UUID, val moves: List<BattlePublicMoveOptionView>, val complete: Boolean)
    /** Hashed once; one is made per catalog object and every lookup of it hashed the whole catalog. */
    private class CatalogKey(val current: List<CatalogEntryKey>, val original: List<CatalogEntryKey>) {
        private val hash = current.hashCode() * 31 + original.hashCode()
        override fun hashCode(): Int = hash
        override fun equals(other: Any?): Boolean =
            this === other || other is CatalogKey && other.hash == hash && other.current == current && other.original == original
    }

    companion object {
        /** Measurement switch for [calculationsUnderIdentityKeying]; off in play. */
        @Volatile
        internal var countIdentityKeying: Boolean = false
    }
}
