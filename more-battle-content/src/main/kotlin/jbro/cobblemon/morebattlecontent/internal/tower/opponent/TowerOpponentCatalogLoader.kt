package jbro.cobblemon.morebattlecontent.internal.tower.opponent

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.io.Reader
import jbro.cobblemon.morebattlecontent.api.rules.MajorBattleMechanic
import jbro.cobblemon.morebattlecontent.internal.ai.BattleAiSkillRange
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleFormat
import jbro.cobblemon.morebattlecontent.internal.tower.TowerOpponentKind
import jbro.cobblemon.morebattlecontent.internal.tower.TowerRank
import jbro.cobblemon.morebattlecontent.internal.validation.IdentifierSyntax

internal object TowerOpponentCatalogLoader {
    fun load(reader: Reader): TowerOpponentCatalogLoadResult = decode {
        val root = JsonParser.parseReader(reader).requireObject("$")
        parseCatalog(root)
    }

    fun loadFragments(fragments: List<Pair<String, Reader>>): TowerOpponentCatalogLoadResult = decode {
        if (fragments.isEmpty()) {
            reject(TowerOpponentCatalogIssueCode.MISSING_FIELD, "$", "No Battle Tower opponent JSON files were found")
        }
        val profiles = JsonArray()
        val sets = JsonArray()
        var schemaVersion: Int? = null
        var singleCatalogId: String? = null
        fragments.forEach { (resourceId, reader) ->
            val path = "resource[$resourceId]"
            val root = reader.use { JsonParser.parseReader(it).requireObject(path) }
            root.rejectUnknownFields(path, ROOT_FIELDS)
            val schema = root.requiredInt(path, "schema_version")
            if (schemaVersion != null && schemaVersion != schema) {
                reject(
                    TowerOpponentCatalogIssueCode.UNSUPPORTED_SCHEMA,
                    "$path.schema_version",
                    "All Battle Tower catalog fragments must use schema $schemaVersion, found $schema",
                )
            }
            schemaVersion = schema
            val catalogId = if (root.has("catalog_id")) root.requiredStableId(path, "catalog_id") else null
            if (fragments.size == 1) singleCatalogId = catalogId
            val fragmentProfiles = root.optionalArray(path, "profiles")
            val fragmentSets = root.optionalArray(path, "sets")
            if ((fragmentProfiles == null || fragmentProfiles.isEmpty) && (fragmentSets == null || fragmentSets.isEmpty)) {
                reject(TowerOpponentCatalogIssueCode.INVALID_VALUE, path, "A Tower fragment must define profiles or sets")
            }
            fragmentProfiles?.forEach { profiles.add(it.deepCopy()) }
            fragmentSets?.forEach { sets.add(it.deepCopy()) }
        }
        parseCatalog(
            JsonObject().apply {
                addProperty("schema_version", requireNotNull(schemaVersion))
                addProperty("catalog_id", singleCatalogId ?: MERGED_CATALOG_ID)
                add("profiles", profiles)
                add("sets", sets)
            },
        )
    }

    private fun parseCatalog(root: JsonObject): TowerOpponentCatalog {
        root.rejectUnknownFields("$", ROOT_FIELDS)

        val schemaVersion = root.requiredInt("$", "schema_version")
        if (schemaVersion !in SUPPORTED_SCHEMA_VERSIONS) {
            reject(
                TowerOpponentCatalogIssueCode.UNSUPPORTED_SCHEMA,
                "$.schema_version",
                "Unsupported opponent catalog schema: $schemaVersion",
            )
        }

        val catalogId = root.requiredStableId("$", "catalog_id")
        val profiles = root.requiredArray("$", "profiles").mapIndexed { index, element ->
            parseProfile(element.requireObject("$.profiles[$index]"), "$.profiles[$index]", schemaVersion)
        }
        val sets = root.requiredArray("$", "sets").mapIndexed { index, element ->
            parseSet(element.requireObject("$.sets[$index]"), "$.sets[$index]", schemaVersion)
        }

        requireNotEmpty(profiles, "$.profiles", "profiles")
        requireNotEmpty(sets, "$.sets", "sets")
        rejectDuplicateIds(profiles.map(TowerOpponentProfile::profileId), "$.profiles")
        rejectDuplicateIds(sets.map(TowerPokemonSet::setId), "$.sets")
        validateProfileReferences(schemaVersion, profiles, sets)

        return TowerOpponentCatalog(catalogId, profiles, sets)
    }

    private fun decode(load: () -> TowerOpponentCatalog): TowerOpponentCatalogLoadResult = try {
        TowerOpponentCatalogLoadResult.Loaded(load())
    } catch (error: CatalogDecodeException) {
        TowerOpponentCatalogLoadResult.Rejected(listOf(error.issue))
    } catch (error: JsonParseException) {
        malformed(error)
    } catch (error: IllegalStateException) {
        malformed(error)
    }

    private fun parseProfile(
        objectValue: JsonObject,
        path: String,
        schemaVersion: Int,
    ): TowerOpponentProfile {
        objectValue.rejectUnknownFields(
            path,
            if (schemaVersion == 1) PROFILE_FIELDS_V1 else PROFILE_FIELDS_V2,
        )
        val rankIds = objectValue.requiredStringList(path, "rank_ids").mapIndexed { index, rankId ->
            TowerRank.entries.singleOrNull { it.serializedId == rankId }
                ?: reject(
                    TowerOpponentCatalogIssueCode.INVALID_VALUE,
                    "$path.rank_ids[$index]",
                    "Unknown tower rank ID: $rankId",
                )
        }
        requireNotEmpty(rankIds, "$path.rank_ids", "rank_ids")
        rejectDuplicateIds(rankIds.map(TowerRank::serializedId), "$path.rank_ids")

        val formatId = objectValue.requiredString(path, "format")
        val format = TowerBattleFormat.entries.singleOrNull { it.recordId == formatId }
            ?: reject(TowerOpponentCatalogIssueCode.INVALID_VALUE, "$path.format", "Unknown format: $formatId")
        val opponentKindId = objectValue.requiredString(path, "opponent_kind")
        val opponentKind = TowerOpponentKind.entries.singleOrNull { it.name.lowercase() == opponentKindId }
            ?: reject(
                TowerOpponentCatalogIssueCode.INVALID_VALUE,
                "$path.opponent_kind",
                "Unknown opponent kind: $opponentKindId",
            )
        val mechanic = if (schemaVersion >= 2) {
            val mechanicId = objectValue.requiredString(path, "mechanic_id")
            MajorBattleMechanic.entries.singleOrNull { it.id == mechanicId }
                ?: reject(
                    TowerOpponentCatalogIssueCode.INVALID_VALUE,
                    "$path.mechanic_id",
                    "Unknown tower mechanic: $mechanicId",
                )
        } else {
            null
        }
        val weight = objectValue.requiredInt(path, "weight")
        if (weight <= 0) {
            reject(TowerOpponentCatalogIssueCode.INVALID_VALUE, "$path.weight", "Weight must be positive")
        }
        val aiSkill = objectValue.requiredInt(path, "ai_skill")
        if (aiSkill !in BattleAiSkillRange.supported) {
            reject(
                TowerOpponentCatalogIssueCode.INVALID_VALUE,
                "$path.ai_skill",
                "AI skill must be between ${BattleAiSkillRange.supported.first} and " +
                    "${BattleAiSkillRange.supported.last}",
            )
        }
        val setIds = objectValue.requiredStringList(path, "set_pool")
        if (setIds.size < MINIMUM_PROFILE_POOL_SIZE) {
            reject(
                TowerOpponentCatalogIssueCode.INSUFFICIENT_POOL,
                "$path.set_pool",
                "A profile must contain at least $MINIMUM_PROFILE_POOL_SIZE set IDs",
            )
        }
        rejectDuplicateIds(setIds, "$path.set_pool")

        return TowerOpponentProfile(
            profileId = objectValue.requiredStableId(path, "profile_id"),
            displayNameKey = objectValue.requiredTranslationKey(path, "display_name_key"),
            rankIds = rankIds,
            format = format,
            opponentKind = opponentKind,
            mechanic = mechanic,
            weight = weight,
            aiSkill = aiSkill,
            theme = objectValue.requiredStableId(path, "theme"),
            setIds = setIds,
        )
    }

    private fun parseSet(
        objectValue: JsonObject,
        path: String,
        schemaVersion: Int,
    ): TowerPokemonSet {
        objectValue.rejectUnknownFields(path, if (schemaVersion >= 3) SET_FIELDS_V3 else SET_FIELDS_V1_V2)
        val setTier = objectValue.requiredInt(path, "set_tier")
        if (setTier <= 0) {
            reject(TowerOpponentCatalogIssueCode.INVALID_VALUE, "$path.set_tier", "Set tier must be positive")
        }
        val moves = objectValue.requiredStringList(path, "moves")
        if (moves.size !in 1..4) {
            reject(TowerOpponentCatalogIssueCode.INVALID_VALUE, "$path.moves", "Moves must contain 1 to 4 IDs")
        }
        rejectDuplicateIds(moves, "$path.moves")
        moves.forEachIndexed { index, move -> requireResourceId(move, "$path.moves[$index]") }

        val ivs = parseStats(objectValue.requiredObject(path, "ivs"), "$path.ivs", IV_RANGE)
        val evs = parseStats(objectValue.requiredObject(path, "evs"), "$path.evs", EV_RANGE)
        if (evs.total > MAX_TOTAL_EVS) {
            reject(
                TowerOpponentCatalogIssueCode.INVALID_VALUE,
                "$path.evs",
                "Total EVs must not exceed $MAX_TOTAL_EVS",
            )
        }

        val teraType = if (schemaVersion >= 3) objectValue.optionalString(path, "tera_type") else null
        if (teraType != null && teraType !in TowerPokemonSet.SUPPORTED_TERA_TYPES) {
            reject(
                TowerOpponentCatalogIssueCode.INVALID_VALUE,
                "$path.tera_type",
                "Tera type must be one of the 18 standard Pokemon types: $teraType",
            )
        }
        val dmaxLevel = if (schemaVersion >= 3) objectValue.optionalInt(path, "dmax_level") else null
        if (dmaxLevel != null && dmaxLevel !in TowerPokemonSet.SUPPORTED_DMAX_LEVELS) {
            reject(
                TowerOpponentCatalogIssueCode.INVALID_VALUE,
                "$path.dmax_level",
                "Dynamax level must be between ${TowerPokemonSet.SUPPORTED_DMAX_LEVELS.first} and " +
                    "${TowerPokemonSet.SUPPORTED_DMAX_LEVELS.last}",
            )
        }
        val gmaxFactor = if (schemaVersion >= 3) objectValue.optionalBoolean(path, "gmax_factor") else null
        validateMechanicPropertyShape(path, teraType, dmaxLevel, gmaxFactor)

        return TowerPokemonSet(
            setId = objectValue.requiredStableId(path, "set_id"),
            setTier = setTier,
            speciesId = objectValue.requiredResourceId(path, "species_id"),
            formId = objectValue.optionalStableId(path, "form_id"),
            abilityId = objectValue.optionalResourceId(path, "ability_id"),
            natureId = objectValue.requiredResourceId(path, "nature_id"),
            heldItemId = objectValue.optionalResourceId(path, "held_item_id"),
            moves = moves,
            ivs = ivs,
            evs = evs,
            teraType = teraType,
            dmaxLevel = dmaxLevel,
            gmaxFactor = gmaxFactor,
        )
    }

    private fun validateMechanicPropertyShape(
        path: String,
        teraType: String?,
        dmaxLevel: Int?,
        gmaxFactor: Boolean?,
    ) {
        if (teraType != null && (dmaxLevel != null || gmaxFactor != null)) {
            reject(
                TowerOpponentCatalogIssueCode.INVALID_VALUE,
                path,
                "Tera and Dynamax properties must not be mixed",
            )
        }
        if (dmaxLevel == null && gmaxFactor != null) {
            reject(
                TowerOpponentCatalogIssueCode.MISSING_FIELD,
                "$path.dmax_level",
                "dmax_level is required when gmax_factor is defined",
            )
        }
        if (dmaxLevel != null && gmaxFactor == null) {
            reject(
                TowerOpponentCatalogIssueCode.MISSING_FIELD,
                "$path.gmax_factor",
                "gmax_factor is required when dmax_level is defined",
            )
        }
    }

    private fun parseStats(objectValue: JsonObject, path: String, range: IntRange): TowerStatSpread {
        objectValue.rejectUnknownFields(path, STAT_FIELDS)
        fun stat(name: String): Int {
            val value = objectValue.requiredInt(path, name)
            if (value !in range) {
                reject(
                    TowerOpponentCatalogIssueCode.INVALID_VALUE,
                    "$path.$name",
                    "$name must be between ${range.first} and ${range.last}",
                )
            }
            return value
        }
        return TowerStatSpread(
            hp = stat("hp"),
            attack = stat("attack"),
            defense = stat("defense"),
            specialAttack = stat("special_attack"),
            specialDefense = stat("special_defense"),
            speed = stat("speed"),
        )
    }

    private fun validateProfileReferences(
        schemaVersion: Int,
        profiles: List<TowerOpponentProfile>,
        sets: List<TowerPokemonSet>,
    ) {
        val indexedSets = sets.mapIndexed { index, set -> set.setId to IndexedValue(index, set) }.toMap()
        profiles.forEachIndexed { profileIndex, profile ->
            val profileSets = profile.setIds.mapIndexed { setIndex, setId ->
                indexedSets[setId] ?: reject(
                    TowerOpponentCatalogIssueCode.UNKNOWN_REFERENCE,
                    "$.profiles[$profileIndex].set_pool[$setIndex]",
                    "Unknown Pokemon set ID: $setId",
                )
            }
            if (schemaVersion >= 3) {
                profileSets.forEach { indexedSet ->
                    validateMechanicProperties(requireNotNull(profile.mechanic), indexedSet)
                }
            }
            if (!TowerLegalTeamSearch.exists(profileSets.map(IndexedValue<TowerPokemonSet>::value), profile.format.selectionSize)) {
                reject(
                    TowerOpponentCatalogIssueCode.NO_LEGAL_TEAM,
                    "$.profiles[$profileIndex].set_pool",
                    "Set pool cannot produce a ${profile.format.selectionSize}-member team with unique species and items",
                )
            }
        }
    }

    private fun validateMechanicProperties(
        mechanic: MajorBattleMechanic,
        indexedSet: IndexedValue<TowerPokemonSet>,
    ) {
        val set = indexedSet.value
        val setPath = "$.sets[${indexedSet.index}]"
        when (mechanic) {
            MajorBattleMechanic.MEGA -> {
                rejectPresent(set.teraType, "$setPath.tera_type", mechanic)
                rejectPresent(set.dmaxLevel, "$setPath.dmax_level", mechanic)
                rejectPresent(set.gmaxFactor, "$setPath.gmax_factor", mechanic)
            }
            MajorBattleMechanic.DYNAMAX -> {
                requirePresent(set.dmaxLevel, "$setPath.dmax_level", mechanic)
                requirePresent(set.gmaxFactor, "$setPath.gmax_factor", mechanic)
                rejectPresent(set.teraType, "$setPath.tera_type", mechanic)
            }
            MajorBattleMechanic.TERA -> {
                requirePresent(set.teraType, "$setPath.tera_type", mechanic)
                rejectPresent(set.dmaxLevel, "$setPath.dmax_level", mechanic)
                rejectPresent(set.gmaxFactor, "$setPath.gmax_factor", mechanic)
            }
        }
    }

    private fun requirePresent(value: Any?, path: String, mechanic: MajorBattleMechanic) {
        if (value == null) {
            reject(
                TowerOpponentCatalogIssueCode.MISSING_FIELD,
                path,
                "${mechanic.id} profiles require ${path.substringAfterLast('.')}",
            )
        }
    }

    private fun rejectPresent(value: Any?, path: String, mechanic: MajorBattleMechanic) {
        if (value != null) {
            reject(
                TowerOpponentCatalogIssueCode.INVALID_VALUE,
                path,
                "${mechanic.id} profiles must not define ${path.substringAfterLast('.')}",
            )
        }
    }

    private fun malformed(error: Exception) = TowerOpponentCatalogLoadResult.Rejected(
        listOf(
            TowerOpponentCatalogIssue(
                TowerOpponentCatalogIssueCode.MALFORMED_JSON,
                "$",
                error.message ?: "Malformed JSON",
            ),
        ),
    )
}

private class CatalogDecodeException(val issue: TowerOpponentCatalogIssue) : RuntimeException(issue.message)

private fun reject(code: TowerOpponentCatalogIssueCode, path: String, message: String): Nothing =
    throw CatalogDecodeException(TowerOpponentCatalogIssue(code, path, message))

private fun JsonElement.requireObject(path: String): JsonObject = if (isJsonObject) {
    asJsonObject
} else {
    reject(TowerOpponentCatalogIssueCode.INVALID_VALUE, path, "Expected an object")
}

private fun JsonObject.rejectUnknownFields(path: String, allowed: Set<String>) {
    keySet().firstOrNull { it !in allowed }?.let { field ->
        reject(TowerOpponentCatalogIssueCode.UNKNOWN_FIELD, "$path.$field", "Unknown field: $field")
    }
}

private fun JsonObject.requiredElement(path: String, field: String): JsonElement =
    get(field) ?: reject(TowerOpponentCatalogIssueCode.MISSING_FIELD, "$path.$field", "Missing field: $field")

private fun JsonObject.requiredObject(path: String, field: String): JsonObject =
    requiredElement(path, field).requireObject("$path.$field")

private fun JsonObject.requiredArray(path: String, field: String): JsonArray {
    val element = requiredElement(path, field)
    if (!element.isJsonArray) {
        reject(TowerOpponentCatalogIssueCode.INVALID_VALUE, "$path.$field", "Expected an array")
    }
    return element.asJsonArray
}

private fun JsonObject.optionalArray(path: String, field: String): JsonArray? {
    val element = get(field) ?: return null
    if (!element.isJsonArray) {
        reject(TowerOpponentCatalogIssueCode.INVALID_VALUE, "$path.$field", "Expected an array")
    }
    return element.asJsonArray
}

private fun JsonObject.requiredString(path: String, field: String): String {
    val element = requiredElement(path, field)
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
        reject(TowerOpponentCatalogIssueCode.INVALID_VALUE, "$path.$field", "Expected a string")
    }
    return element.asString
}

private fun JsonObject.requiredInt(path: String, field: String): Int {
    val element = requiredElement(path, field)
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
        reject(TowerOpponentCatalogIssueCode.INVALID_VALUE, "$path.$field", "Expected an integer")
    }
    return try {
        element.asBigDecimal.toBigIntegerExact().intValueExact()
    } catch (_: ArithmeticException) {
        reject(TowerOpponentCatalogIssueCode.INVALID_VALUE, "$path.$field", "Expected an integer")
    } catch (_: NumberFormatException) {
        reject(TowerOpponentCatalogIssueCode.INVALID_VALUE, "$path.$field", "Expected an integer")
    }
}

private fun JsonObject.optionalInt(path: String, field: String): Int? {
    val element = get(field) ?: return null
    if (element.isJsonNull) return null
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
        reject(TowerOpponentCatalogIssueCode.INVALID_VALUE, "$path.$field", "Expected an integer or null")
    }
    return try {
        element.asBigDecimal.toBigIntegerExact().intValueExact()
    } catch (_: ArithmeticException) {
        reject(TowerOpponentCatalogIssueCode.INVALID_VALUE, "$path.$field", "Expected an integer or null")
    } catch (_: NumberFormatException) {
        reject(TowerOpponentCatalogIssueCode.INVALID_VALUE, "$path.$field", "Expected an integer or null")
    }
}

private fun JsonObject.optionalBoolean(path: String, field: String): Boolean? {
    val element = get(field) ?: return null
    if (element.isJsonNull) return null
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isBoolean) {
        reject(TowerOpponentCatalogIssueCode.INVALID_VALUE, "$path.$field", "Expected a boolean or null")
    }
    return element.asBoolean
}

private fun JsonObject.requiredStringList(path: String, field: String): List<String> =
    requiredArray(path, field).mapIndexed { index, element ->
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
            reject(TowerOpponentCatalogIssueCode.INVALID_VALUE, "$path.$field[$index]", "Expected a string")
        }
        element.asString
    }

private fun JsonObject.requiredStableId(path: String, field: String): String =
    requiredString(path, field).also { value -> requireStableId(value, "$path.$field") }

private fun JsonObject.requiredTranslationKey(path: String, field: String): String =
    requiredString(path, field).also { value ->
        if (!TRANSLATION_KEY.matches(value)) {
            reject(TowerOpponentCatalogIssueCode.INVALID_VALUE, "$path.$field", "Invalid translation key: $value")
        }
    }

private fun JsonObject.requiredResourceId(path: String, field: String): String =
    requiredString(path, field).also { value -> requireResourceId(value, "$path.$field") }

private fun JsonObject.optionalStableId(path: String, field: String): String? =
    optionalString(path, field)?.also { value -> requireStableId(value, "$path.$field") }

private fun JsonObject.optionalResourceId(path: String, field: String): String? =
    optionalString(path, field)?.also { value -> requireResourceId(value, "$path.$field") }

private fun JsonObject.optionalString(path: String, field: String): String? {
    val element = get(field) ?: return null
    if (element.isJsonNull) return null
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
        reject(TowerOpponentCatalogIssueCode.INVALID_VALUE, "$path.$field", "Expected a string or null")
    }
    return element.asString
}

private fun requireStableId(value: String, path: String) {
    if (!IdentifierSyntax.isStableId(value)) {
        reject(TowerOpponentCatalogIssueCode.INVALID_VALUE, path, "Invalid stable ID: $value")
    }
}

private fun requireResourceId(value: String, path: String) {
    if (!IdentifierSyntax.isResourceId(value)) {
        reject(TowerOpponentCatalogIssueCode.INVALID_VALUE, path, "Invalid resource ID: $value")
    }
}

private fun requireNotEmpty(values: Collection<*>, path: String, label: String) {
    if (values.isEmpty()) {
        reject(TowerOpponentCatalogIssueCode.INVALID_VALUE, path, "$label must not be empty")
    }
}

private fun rejectDuplicateIds(values: List<String>, path: String) {
    val seen = HashSet<String>()
    values.forEachIndexed { index, value ->
        if (!seen.add(value)) {
            reject(TowerOpponentCatalogIssueCode.DUPLICATE_ID, "$path[$index]", "Duplicate ID: $value")
        }
    }
}

private val SUPPORTED_SCHEMA_VERSIONS = 1..3
private const val MERGED_CATALOG_ID = "merged_tower_catalog"
private const val MINIMUM_PROFILE_POOL_SIZE = 6
private const val MAX_TOTAL_EVS = 510
private val IV_RANGE = 0..31
private val EV_RANGE = 0..252
private val TRANSLATION_KEY = Regex("[a-z0-9][a-z0-9_.-]*")

private val ROOT_FIELDS = setOf("schema_version", "catalog_id", "profiles", "sets")
private val PROFILE_FIELDS_V1 = setOf(
    "profile_id",
    "display_name_key",
    "rank_ids",
    "format",
    "opponent_kind",
    "weight",
    "ai_skill",
    "theme",
    "set_pool",
)
private val PROFILE_FIELDS_V2 = PROFILE_FIELDS_V1 + "mechanic_id"
private val SET_FIELDS_V1_V2 = setOf(
    "set_id",
    "set_tier",
    "species_id",
    "form_id",
    "ability_id",
    "nature_id",
    "held_item_id",
    "moves",
    "ivs",
    "evs",
)
private val SET_FIELDS_V3 = SET_FIELDS_V1_V2 + setOf("tera_type", "dmax_level", "gmax_factor")
private val STAT_FIELDS = setOf("hp", "attack", "defense", "special_attack", "special_defense", "speed")
