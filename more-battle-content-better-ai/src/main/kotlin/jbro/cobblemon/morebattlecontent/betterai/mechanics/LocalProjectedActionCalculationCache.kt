package jbro.cobblemon.morebattlecontent.betterai.mechanics

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTargetSlot
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicActionCatalogView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicMoveOptionView
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveCandidateView

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

    var calculationsPerformed: Int = 0
        private set

    /**
     * Calculations an identity-keyed cache would have performed instead.
     *
     * Counted by remembering which (state object, action) pairs have been asked for. The gap between
     * this and [calculationsPerformed] is exactly what structural keying saves, which is worth having
     * as a number rather than as a wall-clock impression.
     */
    var calculationsUnderIdentityKeying: Int = 0
        private set

    private val byStateIdentity = java.util.IdentityHashMap<BattleStateView, MutableSet<ActionKey>>()
    private val catalogKeys = java.util.IdentityHashMap<BattlePublicActionCatalogView, CatalogKey>()

    fun getOrCalculate(
        state: BattleStateView,
        side: BattleSide,
        action: BattleActionCandidate,
        catalog: BattlePublicActionCatalogView? = null,
        calculation: () -> BattleDecisionContext,
    ): BattleDecisionContext {
        val key = ActionKey(
            side = side,
            actionId = action.actionId,
            kind = action.kind,
            actorSlot = action.actorSlot,
            moveSlot = action.moveSlot,
            moveId = action.moveId,
            targets = action.targets,
            switchPokemonId = action.switchPokemonId,
            mechanicId = action.mechanic?.mechanicId,
            moveDetails = action.moveDetails,
            catalog = catalog?.let { source -> catalogKeys.getOrPut(source) {
                fun entries(values: List<jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonActionCatalogView>) =
                    values.map { CatalogEntryKey(it.battlePokemonId, it.moves, it.moveSetComplete) }
                CatalogKey(entries(source.entries), entries(source.originalEntries))
            } },
        )
        if (byStateIdentity.getOrPut(state) { HashSet() }.add(key)) calculationsUnderIdentityKeying++
        val stateEntries = byState.getOrPut(fingerprints.of(state)) { HashMap() }
        return stateEntries.getOrPut(key) {
            calculationsPerformed++
            calculation()
        }
    }

    private data class ActionKey(
        val side: BattleSide,
        val actionId: String,
        val kind: BattleActionKind,
        val actorSlot: Int?,
        val moveSlot: Int?,
        val moveId: String?,
        val targets: List<BattleTargetSlot>,
        val switchPokemonId: UUID?,
        val mechanicId: String?,
        val moveDetails: BattleMoveCandidateView?,
        val catalog: CatalogKey?,
    )
    private data class CatalogEntryKey(val id: UUID, val moves: List<BattlePublicMoveOptionView>, val complete: Boolean)
    private data class CatalogKey(val current: List<CatalogEntryKey>, val original: List<CatalogEntryKey>)
}
