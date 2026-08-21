package jbro.cobblemon.morebattlecontent.internal.factory

import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.selection.RecentSelectionHistory

internal class FactoryDraftOfferService(
    private val catalogSource: () -> FactoryCatalog?,
    private val random: FactoryCatalogRandom,
) {
    private val recentSpeciesIds = RecentSelectionHistory<UUID, String>(RECENT_SPECIES_LIMIT)

    @Synchronized
    fun select(
        playerId: UUID,
        levelMode: FactoryLevelMode,
        round: Int,
        rentAndTradeCount: Int,
    ): FactoryRentalDraft? {
        val catalog = catalogSource() ?: return null
        val draft = FactoryDraftSelector(catalog, random).select(
            levelMode,
            round,
            rentAndTradeCount,
            recentSpeciesIds = recentSpeciesIds.recent(playerId),
        ) ?: return null
        draft.sets.forEach { recentSpeciesIds.record(playerId, it.speciesId) }
        return draft
    }

    @Synchronized
    fun forget(playerId: UUID) {
        recentSpeciesIds.forget(playerId)
    }

    private companion object {
        const val RECENT_SPECIES_LIMIT = 18
    }
}
