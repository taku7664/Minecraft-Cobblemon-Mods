package jbro.cobblemon.morebattlecontent.internal.factory

import java.util.Collections
import jbro.cobblemon.morebattlecontent.internal.validation.IdentifierSyntax

internal enum class FactoryBattleFormat(
    private val id: String,
    val selectionSize: Int,
) {
    SINGLE("single", 3),
    DOUBLE("double", 4),
    ;

    fun recordId(levelMode: FactoryLevelMode): String = "${id}_${levelMode.id}"
}

internal enum class FactoryLevelMode(
    val id: String,
    val battleLevel: Int,
) {
    LEVEL_50("level_50", 50),
    OPEN_LEVEL("open_level", 100),
    ;

    companion object {
        fun fromId(id: String): FactoryLevelMode = entries.singleOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown Factory level mode: $id")
    }
}

internal enum class FactoryPoolGroup {
    STARTER,
    INTERMEDIATE,
    ADVANCED,
}

internal data class FactoryPoolWindow(
    val group: FactoryPoolGroup,
    val variants: Set<Int>,
) {
    init {
        require(variants.isNotEmpty() && variants.all { it in 1..4 }) {
            "Factory pool variants must be between 1 and 4"
        }
    }
}

internal data class FactoryStatSpread(
    val hp: Int,
    val attack: Int,
    val defense: Int,
    val specialAttack: Int,
    val specialDefense: Int,
    val speed: Int,
) {
    val total: Int
        get() = hp + attack + defense + specialAttack + specialDefense + speed

    internal fun requireWithin(range: IntRange, label: String) {
        require(listOf(hp, attack, defense, specialAttack, specialDefense, speed).all { it in range }) {
            "$label stats must be between ${range.first} and ${range.last}"
        }
    }
}

internal class FactoryRentalSet(
    val setId: String,
    val speciesId: String,
    moveIds: List<String>,
    val abilityId: String,
    val heldItemId: String?,
    val natureId: String,
    val ivs: FactoryStatSpread,
    val evs: FactoryStatSpread,
    val formId: String? = null,
) {
    val moveIds: List<String> = Collections.unmodifiableList(ArrayList(moveIds))

    init {
        require(IdentifierSyntax.isStableId(setId)) { "Invalid Factory rental set ID: $setId" }
        require(IdentifierSyntax.isResourceId(speciesId)) { "Invalid Factory rental species ID: $speciesId" }
        require(formId == null || IdentifierSyntax.isStableId(formId)) { "Invalid Factory rental form ID: $formId" }
        require(IdentifierSyntax.isResourceId(abilityId)) { "Invalid Factory rental ability ID: $abilityId" }
        require(heldItemId == null || IdentifierSyntax.isResourceId(heldItemId)) { "Invalid Factory held item ID" }
        require(IdentifierSyntax.isResourceId(natureId)) { "Invalid Factory rental nature ID: $natureId" }
        require(this.moveIds.size in 1..4) { "Factory rental sets must contain one to four moves" }
        require(this.moveIds.distinct().size == this.moveIds.size) { "Factory rental moves must be unique" }
        require(this.moveIds.all(IdentifierSyntax::isResourceId)) { "Invalid Factory rental move ID" }
        ivs.requireWithin(0..31, "Factory IV")
        evs.requireWithin(0..252, "Factory EV")
        require(evs.total <= 510) { "Factory EV total must not exceed 510" }
    }
}

internal class FactoryRentalTeam internal constructor(
    val format: FactoryBattleFormat,
    sets: List<FactoryRentalSet>,
) {
    val sets: List<FactoryRentalSet> = Collections.unmodifiableList(ArrayList(sets))

    init {
        require(this.sets.size == format.selectionSize) {
            "${format.name.lowercase()} Factory teams require exactly ${format.selectionSize} rentals"
        }
        requireLegalRentalCollection(this.sets, "Factory team")
    }
}

internal class FactoryRentalDraft(sets: List<FactoryRentalSet>) {
    val sets: List<FactoryRentalSet> = Collections.unmodifiableList(ArrayList(sets))
    private val byId: Map<String, FactoryRentalSet>

    init {
        require(this.sets.size == DRAFT_SIZE) { "Factory rental drafts require exactly $DRAFT_SIZE sets" }
        requireLegalRentalCollection(this.sets, "Factory draft")
        byId = this.sets.associateBy(FactoryRentalSet::setId)
    }

    fun select(setIds: List<String>, format: FactoryBattleFormat): FactoryRentalTeam {
        require(setIds.size == format.selectionSize && setIds.distinct().size == setIds.size) {
            "Factory selection must contain ${format.selectionSize} unique set IDs"
        }
        return FactoryRentalTeam(format, setIds.map { setId -> byId[setId] ?: error("Rental set is not in this draft: $setId") })
    }

    private companion object {
        const val DRAFT_SIZE = 6
    }
}

private fun requireLegalRentalCollection(sets: List<FactoryRentalSet>, label: String) {
    require(sets.map(FactoryRentalSet::setId).distinct().size == sets.size) { "$label set IDs must be unique" }
    require(sets.map(FactoryRentalSet::speciesId).distinct().size == sets.size) { "$label species must be unique" }
    val heldItems = sets.mapNotNull(FactoryRentalSet::heldItemId)
    require(heldItems.distinct().size == heldItems.size) { "$label held items must be unique" }
}
