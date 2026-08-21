package jbro.cobblemon.morebattlecontent.internal.factory

import java.io.Reader
import java.util.Collections
import jbro.cobblemon.morebattlecontent.api.ai.BattleStrategyObjective
import jbro.cobblemon.morebattlecontent.api.ai.BattleTeamRole

/** A complete, immutable rental preset. Randomness chooses this preset, never its contents. */
internal class FactoryRentalTemplate(
    val setId: String,
    val poolGroup: FactoryPoolGroup,
    val variant: Int,
    val speciesId: String,
    moveIds: List<String>,
    val abilityId: String,
    val heldItemId: String,
    val natureId: String,
    val evs: FactoryStatSpread,
    val ivs: FactoryStatSpread? = null,
    val formId: String? = null,
    roles: Set<BattleTeamRole>,
    preferredMoveIds: Set<String>,
    val leadPriority: Int,
    val preservationPriority: Int,
) {
    val moveIds: List<String> = immutableList(moveIds)
    val roles: Set<BattleTeamRole> = immutableSet(roles)
    val preferredMoveIds: Set<String> = immutableSet(preferredMoveIds)

    init {
        require(variant in 1..4) { "Factory set variant must be between 1 and 4" }
        require(this.moveIds.size == 4) { "Factory complete rental set must contain exactly 4 moves" }
        require(this.moveIds.distinct().size == this.moveIds.size) { "Factory rental moves must be unique" }
        require(this.roles.isNotEmpty()) { "Factory rental roles must not be empty" }
        require(this.preferredMoveIds.all(this.moveIds::contains)) { "Factory preferred moves must belong to the fixed move set" }
        require(leadPriority in 0..100) { "Factory lead priority must be between 0 and 100" }
        require(preservationPriority in 0..100) { "Factory preservation priority must be between 0 and 100" }
    }

    fun materialize(uniformIv: Int): FactoryRentalSet {
        require(uniformIv in 0..31) { "Factory uniform IV must be between 0 and 31" }
        return FactoryRentalSet(
            setId = setId,
            speciesId = speciesId,
            moveIds = moveIds,
            abilityId = abilityId,
            heldItemId = heldItemId,
            natureId = natureId,
            ivs = ivs ?: FactoryStatSpread(uniformIv, uniformIv, uniformIv, uniformIv, uniformIv, uniformIv),
            evs = evs,
            formId = formId,
        )
    }

    fun belongsTo(window: FactoryPoolWindow): Boolean = poolGroup == window.group && variant in window.variants
}

/** Trainer identity and AI framing are independent from the randomly drawn rental team. */
internal class FactoryTrainerProfile(
    val trainerId: String,
    val displayNameKey: String,
    val descriptionKey: String,
    formats: Set<FactoryBattleFormat>,
    val weight: Int,
    val aiSkill: Int,
    val aiSummary: String,
    objectives: Set<BattleStrategyObjective>,
) {
    val formats: Set<FactoryBattleFormat> = immutableSet(formats)
    val objectives: Set<BattleStrategyObjective> = immutableSet(objectives)
}

internal class FactoryCatalog(
    val catalogId: String,
    trainers: List<FactoryTrainerProfile>,
    sets: List<FactoryRentalTemplate>,
) {
    private val trainers: List<FactoryTrainerProfile> = immutableList(trainers)
    private val sets: List<FactoryRentalTemplate> = immutableList(sets)

    fun trainersFor(format: FactoryBattleFormat): List<FactoryTrainerProfile> =
        immutableList(trainers.filter { format in it.formats })

    fun rentalPool(window: FactoryPoolWindow): List<FactoryRentalTemplate> =
        immutableList(sets.filter { it.belongsTo(window) })
}

internal enum class FactoryCatalogIssueCode {
    MALFORMED_JSON,
    UNSUPPORTED_SCHEMA,
    UNKNOWN_FIELD,
    MISSING_FIELD,
    INVALID_VALUE,
    DUPLICATE_ID,
    NO_LEGAL_TEAM,
}

internal data class FactoryCatalogIssue(
    val code: FactoryCatalogIssueCode,
    val path: String,
    val message: String,
)

internal sealed interface FactoryCatalogLoadResult {
    data class Loaded(val catalog: FactoryCatalog) : FactoryCatalogLoadResult
    data class Rejected(val issues: List<FactoryCatalogIssue>) : FactoryCatalogLoadResult {
        init {
            require(issues.isNotEmpty()) { "A rejected Factory catalog requires at least one issue" }
        }
    }
}

internal class FactoryCatalogStore {
    @Volatile
    private var current: FactoryCatalog? = null

    fun snapshot(): FactoryCatalog? = current

    fun reload(reader: Reader): FactoryCatalogLoadResult = FactoryCatalogLoader.load(reader).also { result ->
        if (result is FactoryCatalogLoadResult.Loaded) current = result.catalog
    }

    fun reloadFragments(fragments: List<Pair<String, Reader>>): FactoryCatalogLoadResult =
        FactoryCatalogLoader.loadFragments(fragments).also { result ->
            if (result is FactoryCatalogLoadResult.Loaded) current = result.catalog
        }
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun <T> immutableSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))
