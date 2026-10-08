package jbro.cobblemon.mcc.betterai.simulation

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.BattleObservedEventView
import jbro.cobblemon.mcc.internal.ai.BattleOpponentMoveInferenceView
import jbro.cobblemon.mcc.internal.ai.BattleOpponentTeamPreviewView
import jbro.cobblemon.mcc.internal.ai.BattlePokemonActionCatalogView
import jbro.cobblemon.mcc.internal.ai.BattlePublicActionCatalogView
import jbro.cobblemon.mcc.internal.ai.BattlePublicMoveCandidatePoolView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * Binds an opposing Pokemon seen for the first time to the synthetic team member that stood for it.
 *
 * An opening world gives every unrevealed preview member a synthetic ID. When the opponent sends one out,
 * the public battle names it by its real ID, which no world knows. Each world renames the first free
 * synthetic member of the same species to the real ID; a world without one did not select that Pokemon
 * and contradicts the public battle.
 */
internal object NativeRevealedPokemonBinder {
    /** Synthetic → real IDs to apply, empty when nothing new was revealed, or null when the world is contradicted. */
    fun bind(
        definition: NativeBattleDefinition,
        state: BattleStateView,
        preview: BattleOpponentTeamPreviewView?,
        events: List<BattleObservedEventView>,
        publicPokemonIds: Set<UUID>,
    ): Map<UUID, UUID>? {
        val definitionIds = (definition.p1Team + definition.p2Team).mapTo(hashSetOf()) { UUID.fromString(it.uuid) }
        val unknown = linkedSetOf<UUID>()
        state.pokemon.asSequence()
            .filter { it.side == BattleSide.OPPONENT && it.battlePokemonId !in definitionIds }
            .forEach { unknown += it.battlePokemonId }
        events.forEach { event ->
            (listOfNotNull(event.actorPokemonId) + event.targetPokemonIds)
                .filterNot(definitionIds::contains)
                .forEach { unknown += it }
        }
        if (unknown.isEmpty()) return emptyMap()

        val free = definition.p2Team.filter { UUID.fromString(it.uuid) !in publicPokemonIds }.toMutableList()
        val renames = linkedMapOf<UUID, UUID>()
        for (realId in unknown.sortedBy(UUID::toString)) {
            val pokemon = state.pokemon.firstOrNull { it.battlePokemonId == realId } ?: return null
            if (pokemon.side != BattleSide.OPPONENT) return null
            val species = canonical(pokemon.speciesId)
            // A Showdown set names the form (rotomheat), whereas Cobblemon names the species plus a form.
            val names = setOf(species) + preview?.pokemon.orEmpty().asSequence()
                .filter { slot ->
                    canonical(slot.speciesId) == species &&
                        (pokemon.formId == null || slot.formId == null || canonical(slot.formId) == canonical(pokemon.formId))
                }
                .mapNotNull { it.showdownSpeciesId?.let(::canonical) }
            val match = free.firstOrNull { canonical(it.species) in names } ?: return null
            free.remove(match)
            renames[UUID.fromString(match.uuid)] = realId
        }
        return renames
    }

    fun rename(definition: NativeBattleDefinition, renames: Map<UUID, UUID>): NativeBattleDefinition {
        if (renames.isEmpty()) return definition
        val byText = renames.entries.associate { (from, to) -> from.toString() to to.toString() }
        fun id(value: String) = byText[value] ?: value
        return definition.copy(
            p1Team = definition.p1Team.map { it.copy(uuid = id(it.uuid)) },
            p2Team = definition.p2Team.map { it.copy(uuid = id(it.uuid)) },
            openingState = definition.openingState?.let { opening ->
                opening.copy(pokemon = opening.pokemon.map { it.copy(uuid = id(it.uuid)) })
            },
            situation = definition.situation?.let { situation ->
                situation.copy(pokemon = situation.pokemon.map { it.copy(uuid = id(it.uuid)) })
            },
        )
    }

    fun rename(catalog: BattlePublicActionCatalogView, renames: Map<UUID, UUID>): BattlePublicActionCatalogView {
        if (renames.isEmpty()) return catalog
        fun id(value: UUID) = renames[value] ?: value
        fun entry(value: BattlePokemonActionCatalogView) =
            BattlePokemonActionCatalogView(id(value.battlePokemonId), value.moves, value.moveSetComplete)
        return BattlePublicActionCatalogView(
            entries = catalog.entries.map(::entry),
            originalEntries = catalog.originalEntries.map(::entry),
            candidatePools = catalog.candidatePools.map { pool ->
                BattlePublicMoveCandidatePoolView(id(pool.battlePokemonId), pool.speciesId, pool.formId, pool.moveIds,
                    pool.sourceId, pool.moveDetails)
            },
            opponentMoveInferences = catalog.opponentMoveInferences.map { inference ->
                BattleOpponentMoveInferenceView(id(inference.battlePokemonId), inference.slots)
            },
        )
    }

    private fun canonical(value: String): String = PublicIds.canonical(value)
}
