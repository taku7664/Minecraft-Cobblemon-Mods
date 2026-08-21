package jbro.cobblemon.morebattlecontent.internal.tower.opponent

import java.io.Reader
import java.util.Collections
import jbro.cobblemon.morebattlecontent.api.rules.MajorBattleMechanic
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleFormat
import jbro.cobblemon.morebattlecontent.internal.tower.TowerOpponentKind
import jbro.cobblemon.morebattlecontent.internal.tower.TowerRank

internal data class TowerStatSpread(
    val hp: Int,
    val attack: Int,
    val defense: Int,
    val specialAttack: Int,
    val specialDefense: Int,
    val speed: Int,
) {
    val total: Int
        get() = hp + attack + defense + specialAttack + specialDefense + speed
}

internal class TowerPokemonSet internal constructor(
    val setId: String,
    val setTier: Int,
    val speciesId: String,
    val formId: String?,
    val abilityId: String?,
    val natureId: String,
    val heldItemId: String?,
    moves: List<String>,
    val ivs: TowerStatSpread,
    val evs: TowerStatSpread,
    val teraType: String? = null,
    val dmaxLevel: Int? = null,
    val gmaxFactor: Boolean? = null,
) {
    val moves: List<String> = moves.immutableCopy()

    init {
        require(teraType == null || teraType in SUPPORTED_TERA_TYPES) {
            "Tera type must be one of the 18 standard Pokemon types"
        }
        require(dmaxLevel == null || dmaxLevel in SUPPORTED_DMAX_LEVELS) {
            "Dynamax level must be between ${SUPPORTED_DMAX_LEVELS.first} and ${SUPPORTED_DMAX_LEVELS.last}"
        }
        require((dmaxLevel == null) == (gmaxFactor == null)) {
            "Dynamax level and Gigantamax factor must be defined together"
        }
        require(teraType == null || dmaxLevel == null) {
            "Tera and Dynamax properties must not be mixed"
        }
    }

    internal companion object {
        val SUPPORTED_DMAX_LEVELS = 0..10
        val SUPPORTED_TERA_TYPES = setOf(
            "normal",
            "fire",
            "water",
            "electric",
            "grass",
            "ice",
            "fighting",
            "poison",
            "ground",
            "flying",
            "psychic",
            "bug",
            "rock",
            "ghost",
            "dragon",
            "dark",
            "steel",
            "fairy",
        )
    }
}

internal class TowerOpponentProfile internal constructor(
    val profileId: String,
    val displayNameKey: String,
    rankIds: List<TowerRank>,
    val format: TowerBattleFormat,
    val opponentKind: TowerOpponentKind,
    val mechanic: MajorBattleMechanic? = null,
    val weight: Int,
    val aiSkill: Int,
    val theme: String,
    setIds: List<String>,
) {
    val rankIds: List<TowerRank> = rankIds.immutableCopy()
    val setIds: List<String> = setIds.immutableCopy()
}

internal class TowerOpponentCatalog internal constructor(
    val catalogId: String,
    profiles: List<TowerOpponentProfile>,
    sets: List<TowerPokemonSet>,
) {
    private val profiles: List<TowerOpponentProfile> = profiles.immutableCopy()
    private val setsById: Map<String, TowerPokemonSet> = Collections.unmodifiableMap(
        LinkedHashMap(sets.associateBy(TowerPokemonSet::setId)),
    )

    fun profilesFor(
        rank: TowerRank,
        format: TowerBattleFormat,
        opponentKind: TowerOpponentKind,
    ): List<TowerOpponentProfile> = profiles.filter { profile ->
        rank in profile.rankIds && profile.format == format && profile.opponentKind == opponentKind
    }.immutableCopy()

    fun profilesFor(
        rank: TowerRank,
        format: TowerBattleFormat,
        opponentKind: TowerOpponentKind,
        mechanic: MajorBattleMechanic,
    ): List<TowerOpponentProfile> = profiles.filter { profile ->
        rank in profile.rankIds && profile.format == format && profile.opponentKind == opponentKind &&
            profile.mechanic == mechanic
    }.immutableCopy()

    fun setsFor(profile: TowerOpponentProfile): List<TowerPokemonSet> =
        profile.setIds.map(setsById::getValue).immutableCopy()
}

internal enum class TowerOpponentCatalogIssueCode {
    MALFORMED_JSON,
    UNSUPPORTED_SCHEMA,
    UNKNOWN_FIELD,
    MISSING_FIELD,
    INVALID_VALUE,
    DUPLICATE_ID,
    UNKNOWN_REFERENCE,
    INSUFFICIENT_POOL,
    NO_LEGAL_TEAM,
}

internal data class TowerOpponentCatalogIssue(
    val code: TowerOpponentCatalogIssueCode,
    val path: String,
    val message: String,
)

internal sealed interface TowerOpponentCatalogLoadResult {
    data class Loaded(val catalog: TowerOpponentCatalog) : TowerOpponentCatalogLoadResult
    data class Rejected(val issues: List<TowerOpponentCatalogIssue>) : TowerOpponentCatalogLoadResult {
        init {
            require(issues.isNotEmpty()) { "A rejected catalog must contain at least one issue" }
        }
    }
}

internal class TowerOpponentCatalogStore {
    @Volatile
    private var current: TowerOpponentCatalog? = null

    fun snapshot(): TowerOpponentCatalog? = current

    fun reload(reader: Reader): TowerOpponentCatalogLoadResult {
        val result = TowerOpponentCatalogLoader.load(reader)
        if (result is TowerOpponentCatalogLoadResult.Loaded) {
            current = result.catalog
        }
        return result
    }

    fun reloadFragments(fragments: List<Pair<String, Reader>>): TowerOpponentCatalogLoadResult {
        val result = TowerOpponentCatalogLoader.loadFragments(fragments)
        if (result is TowerOpponentCatalogLoadResult.Loaded) {
            current = result.catalog
        }
        return result
    }
}

private fun <T> List<T>.immutableCopy(): List<T> = Collections.unmodifiableList(ArrayList(this))
