package jbro.cobblemon.morebattlecontent.internal.factory

import java.io.Reader
import java.util.Collections
import jbro.cobblemon.morebattlecontent.api.ai.BattleStrategyObjective
import jbro.cobblemon.morebattlecontent.api.ai.BattleTeamRole

internal class FactoryRentalTemplate(
    val setId: String,
    val poolGroup: FactoryPoolGroup,
    val variant: Int,
    val speciesId: String,
    moveSlots: List<List<String>>,
    val abilityId: String,
    heldItemIds: List<String?>,
    natureIds: List<String>,
    val evs: FactoryStatSpread,
    val ivs: FactoryStatSpread? = null,
    val formId: String? = null,
) {
    val moveSlots: List<List<String>> = immutableList(moveSlots.map(::immutableList))
    val moveIds: List<String> = immutableList(this.moveSlots.flatten())
    val heldItemIds: List<String?> = immutableList(heldItemIds)
    val natureIds: List<String> = immutableList(natureIds)

    init {
        require(variant in 1..4) { "Factory set variant must be between 1 and 4" }
        require(this.moveSlots.size in 1..4) { "Factory move slots must contain 1 to 4 slots" }
        require(this.moveSlots.none(List<String>::isEmpty)) { "Factory move slots must not be empty" }
        require(moveIds.distinct().size == moveIds.size) { "Factory move candidates must be unique across slots" }
        require(this.heldItemIds.isNotEmpty()) { "Factory held item candidates must not be empty" }
        require(this.heldItemIds.distinct().size == this.heldItemIds.size) { "Factory held item candidates must be unique" }
        require(this.natureIds.isNotEmpty()) { "Factory nature candidates must not be empty" }
        require(this.natureIds.distinct().size == this.natureIds.size) { "Factory nature candidates must be unique" }
        FactoryRentalSet(
            setId = setId,
            speciesId = speciesId,
            moveIds = this.moveSlots.map(List<String>::first),
            abilityId = abilityId,
            heldItemId = this.heldItemIds.first(),
            natureId = this.natureIds.first(),
            ivs = ivs ?: FactoryStatSpread(0, 0, 0, 0, 0, 0),
            evs = evs,
            formId = formId,
        )
    }

    constructor(
        setId: String,
        poolGroup: FactoryPoolGroup,
        variant: Int,
        speciesId: String,
        moveIds: List<String>,
        abilityId: String,
        heldItemId: String?,
        natureId: String,
        evs: FactoryStatSpread,
        ivs: FactoryStatSpread? = null,
        formId: String? = null,
    ) : this(
        setId = setId,
        poolGroup = poolGroup,
        variant = variant,
        speciesId = speciesId,
        moveSlots = moveIds.map(::listOf),
        abilityId = abilityId,
        heldItemIds = listOf(heldItemId),
        natureIds = listOf(natureId),
        evs = evs,
        ivs = ivs,
        formId = formId,
    )

    fun materialize(uniformIv: Int, heldItemId: String?, random: FactoryCatalogRandom): FactoryRentalSet {
        require(uniformIv in 0..31) { "Factory uniform IV must be between 0 and 31" }
        require(heldItemId in heldItemIds) { "Factory held item must come from the template candidate pool" }
        val materializedIvs = ivs
            ?: FactoryStatSpread(uniformIv, uniformIv, uniformIv, uniformIv, uniformIv, uniformIv)
        return FactoryRentalSet(
            setId = setId,
            speciesId = speciesId,
            moveIds = moveSlots.map { it[random.nextInt(it.size)] },
            abilityId = abilityId,
            heldItemId = heldItemId,
            natureId = natureIds[random.nextInt(natureIds.size)],
            ivs = materializedIvs,
            evs = evs,
            formId = formId,
        )
    }

    fun belongsTo(window: FactoryPoolWindow): Boolean = poolGroup == window.group && variant in window.variants
}

internal object FactoryNaturePool {
    val ALL: List<String> = immutableList(
        listOf(
            "cobblemon:hardy",
            "cobblemon:lonely",
            "cobblemon:brave",
            "cobblemon:adamant",
            "cobblemon:naughty",
            "cobblemon:bold",
            "cobblemon:docile",
            "cobblemon:relaxed",
            "cobblemon:impish",
            "cobblemon:lax",
            "cobblemon:timid",
            "cobblemon:hasty",
            "cobblemon:serious",
            "cobblemon:jolly",
            "cobblemon:naive",
            "cobblemon:modest",
            "cobblemon:mild",
            "cobblemon:quiet",
            "cobblemon:bashful",
            "cobblemon:rash",
            "cobblemon:calm",
            "cobblemon:gentle",
            "cobblemon:sassy",
            "cobblemon:careful",
            "cobblemon:quirky",
        ),
    )
}

internal class FactoryConceptMemberPlan(
    val planId: String,
    val required: Boolean,
    roles: Set<BattleTeamRole>,
    val tacticalSummary: String,
    preferredMoveIds: Set<String>,
    val leadPriority: Int,
    val preservationPriority: Int,
    setIds: List<String>,
) {
    val roles: Set<BattleTeamRole> = immutableSet(roles)
    val preferredMoveIds: Set<String> = immutableSet(preferredMoveIds)
    val setIds: List<String> = immutableList(setIds)
}

internal class FactoryTrainerConcept(
    val conceptId: String,
    val displayNameKey: String,
    val descriptionKey: String,
    formats: Set<FactoryBattleFormat>,
    val weight: Int,
    val aiSkill: Int,
    val aiSummary: String,
    objectives: Set<BattleStrategyObjective>,
    members: List<FactoryConceptMemberPlan>,
) {
    val formats: Set<FactoryBattleFormat> = immutableSet(formats)
    val objectives: Set<BattleStrategyObjective> = immutableSet(objectives)
    val members: List<FactoryConceptMemberPlan> = immutableList(members)
}

internal class FactoryCatalog(
    val catalogId: String,
    concepts: List<FactoryTrainerConcept>,
    sets: List<FactoryRentalTemplate>,
) {
    private val concepts: List<FactoryTrainerConcept> = immutableList(concepts)
    private val setsById: Map<String, FactoryRentalTemplate> = Collections.unmodifiableMap(
        LinkedHashMap(sets.associateBy(FactoryRentalTemplate::setId)),
    )

    fun conceptsFor(format: FactoryBattleFormat): List<FactoryTrainerConcept> =
        immutableList(concepts.filter { format in it.formats })

    fun setsFor(member: FactoryConceptMemberPlan): List<FactoryRentalTemplate> =
        immutableList(member.setIds.map(setsById::getValue))

    fun rentalPool(window: FactoryPoolWindow): List<FactoryRentalTemplate> =
        immutableList(setsById.values.filter { it.belongsTo(window) })
}

internal enum class FactoryCatalogIssueCode {
    MALFORMED_JSON,
    UNSUPPORTED_SCHEMA,
    UNKNOWN_FIELD,
    MISSING_FIELD,
    INVALID_VALUE,
    DUPLICATE_ID,
    UNKNOWN_REFERENCE,
    INVALID_CONCEPT,
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
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun <T> immutableSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))
