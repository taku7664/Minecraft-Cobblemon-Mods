package jbro.cobblemon.morebattlecontent.internal.factory

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.io.Reader
import jbro.cobblemon.morebattlecontent.api.ai.BattleStrategyObjective
import jbro.cobblemon.morebattlecontent.api.ai.BattleTeamRole
import jbro.cobblemon.morebattlecontent.internal.ai.BattleAiSkillRange
import jbro.cobblemon.morebattlecontent.internal.validation.IdentifierSyntax

internal object FactoryCatalogLoader {
    fun load(reader: Reader): FactoryCatalogLoadResult = try {
        val root = JsonParser.parseReader(reader).requireObject("$")
        root.rejectUnknown("$", ROOT_FIELDS)
        val schema = root.requiredInt("$", "schema_version")
        if (schema !in SUPPORTED_SCHEMA_VERSIONS) {
            reject(FactoryCatalogIssueCode.UNSUPPORTED_SCHEMA, "$.schema_version", "Unsupported schema: $schema")
        }
        val catalogId = root.requiredStableId("$", "catalog_id")
        val sets = root.requiredArray("$", "sets").mapIndexed { index, element ->
            parseSet(element.requireObject("$.sets[$index]"), "$.sets[$index]")
        }
        val trainers = root.requiredArray("$", "trainers").mapIndexed { index, element ->
            parseTrainer(element.requireObject("$.trainers[$index]"), "$.trainers[$index]")
        }
        requireNotEmpty(sets, "$.sets", "sets")
        requireNotEmpty(trainers, "$.trainers", "trainers")
        rejectDuplicates(sets.map(FactoryRentalTemplate::setId), "$.sets")
        rejectDuplicates(trainers.map(FactoryTrainerProfile::trainerId), "$.trainers")
        validateLegalTeams(sets)
        FactoryCatalogLoadResult.Loaded(FactoryCatalog(catalogId, trainers, sets))
    } catch (error: FactoryDecodeException) {
        FactoryCatalogLoadResult.Rejected(listOf(error.issue))
    } catch (error: JsonParseException) {
        malformed(error)
    } catch (error: IllegalStateException) {
        malformed(error)
    } catch (error: IllegalArgumentException) {
        FactoryCatalogLoadResult.Rejected(
            listOf(FactoryCatalogIssue(FactoryCatalogIssueCode.INVALID_VALUE, "$", error.message ?: "Invalid value")),
        )
    }

    private fun parseSet(value: JsonObject, path: String): FactoryRentalTemplate {
        value.rejectUnknown(path, SET_FIELDS)
        val poolGroupId = value.requiredString(path, "pool_group")
        val poolGroup = enumValue<FactoryPoolGroup>(poolGroupId, "$path.pool_group", "pool group")
        val variant = value.requiredInt(path, "variant")
        if (variant !in 1..4) reject(FactoryCatalogIssueCode.INVALID_VALUE, "$path.variant", "Variant must be between 1 and 4")
        val moves = value.requiredStringList(path, "moves")
        if (moves.size != 4) reject(FactoryCatalogIssueCode.INVALID_VALUE, "$path.moves", "A complete rental set requires exactly 4 moves")
        moves.forEachIndexed { index, id -> requireResourceId(id, "$path.moves[$index]") }
        rejectDuplicates(moves, "$path.moves")
        val roles = value.requiredStringList(path, "roles").mapIndexed { index, id ->
            enumValue<BattleTeamRole>(id, "$path.roles[$index]", "team role")
        }.toSet()
        if (roles.isEmpty()) reject(FactoryCatalogIssueCode.INVALID_VALUE, "$path.roles", "Roles must not be empty")
        val preferredMoves = value.requiredStringList(path, "preferred_move_ids")
        preferredMoves.forEachIndexed { index, id -> requireResourceId(id, "$path.preferred_move_ids[$index]") }
        rejectDuplicates(preferredMoves, "$path.preferred_move_ids")
        if (preferredMoves.any { it !in moves }) {
            reject(FactoryCatalogIssueCode.INVALID_VALUE, "$path.preferred_move_ids", "Preferred moves must belong to the fixed move set")
        }
        val leadPriority = value.requiredInt(path, "lead_priority")
        val preservationPriority = value.requiredInt(path, "preservation_priority")
        if (leadPriority !in PRIORITY_RANGE) reject(FactoryCatalogIssueCode.INVALID_VALUE, "$path.lead_priority", "Lead priority must be between 0 and 100")
        if (preservationPriority !in PRIORITY_RANGE) {
            reject(FactoryCatalogIssueCode.INVALID_VALUE, "$path.preservation_priority", "Preservation priority must be between 0 and 100")
        }
        return FactoryRentalTemplate(
            setId = value.requiredStableId(path, "set_id"),
            poolGroup = poolGroup,
            variant = variant,
            speciesId = value.requiredResourceId(path, "species_id"),
            moveIds = moves,
            abilityId = value.requiredResourceId(path, "ability_id"),
            heldItemId = value.requiredResourceId(path, "held_item_id"),
            natureId = value.requiredResourceId(path, "nature_id"),
            evs = parseEvs(value.requiredObject(path, "evs"), "$path.evs"),
            ivs = value.get("ivs")?.let { parseIvs(it.requireObject("$path.ivs"), "$path.ivs") },
            formId = value.optionalStableId(path, "form_id"),
            roles = roles,
            preferredMoveIds = preferredMoves.toSet(),
            leadPriority = leadPriority,
            preservationPriority = preservationPriority,
        )
    }

    private fun parseTrainer(value: JsonObject, path: String): FactoryTrainerProfile {
        value.rejectUnknown(path, TRAINER_FIELDS)
        val formats = value.requiredStringList(path, "formats").mapIndexed { index, id ->
            enumValue<FactoryBattleFormat>(id, "$path.formats[$index]", "format")
        }.toSet()
        if (formats.isEmpty()) reject(FactoryCatalogIssueCode.INVALID_VALUE, "$path.formats", "Formats must not be empty")
        val objectives = value.requiredStringList(path, "objectives").mapIndexed { index, id ->
            enumValue<BattleStrategyObjective>(id, "$path.objectives[$index]", "strategy objective")
        }.toSet()
        if (objectives.isEmpty()) reject(FactoryCatalogIssueCode.INVALID_VALUE, "$path.objectives", "Objectives must not be empty")
        val weight = value.requiredInt(path, "weight")
        if (weight <= 0) reject(FactoryCatalogIssueCode.INVALID_VALUE, "$path.weight", "Weight must be positive")
        val aiSkill = value.requiredInt(path, "ai_skill")
        if (aiSkill !in BattleAiSkillRange.supported) {
            reject(FactoryCatalogIssueCode.INVALID_VALUE, "$path.ai_skill", "AI skill is outside the supported range")
        }
        val aiSummary = value.requiredString(path, "ai_summary")
        if (aiSummary.isBlank() || aiSummary.length > MAX_AI_SUMMARY) {
            reject(FactoryCatalogIssueCode.INVALID_VALUE, "$path.ai_summary", "AI summary must contain 1 to $MAX_AI_SUMMARY characters")
        }
        return FactoryTrainerProfile(
            trainerId = value.requiredStableId(path, "trainer_id"),
            displayNameKey = value.requiredTranslationKey(path, "display_name_key"),
            descriptionKey = value.requiredTranslationKey(path, "description_key"),
            formats = formats,
            weight = weight,
            aiSkill = aiSkill,
            aiSummary = aiSummary,
            objectives = objectives,
        )
    }

    private fun parseEvs(value: JsonObject, path: String): FactoryStatSpread = parseStats(value, path, 0..252).also { spread ->
        if (spread.total > 510) reject(FactoryCatalogIssueCode.INVALID_VALUE, path, "Total EVs must not exceed 510")
    }

    private fun parseIvs(value: JsonObject, path: String): FactoryStatSpread = parseStats(value, path, 0..31)

    private fun parseStats(value: JsonObject, path: String, range: IntRange): FactoryStatSpread {
        value.rejectUnknown(path, STAT_FIELDS)
        fun stat(name: String): Int {
            val amount = value.requiredInt(path, name)
            if (amount !in range) {
                reject(
                    FactoryCatalogIssueCode.INVALID_VALUE,
                    "$path.$name",
                    "$name must be between ${range.first} and ${range.last}",
                )
            }
            return amount
        }
        return FactoryStatSpread(
            hp = stat("hp"),
            attack = stat("attack"),
            defense = stat("defense"),
            specialAttack = stat("special_attack"),
            specialDefense = stat("special_defense"),
            speed = stat("speed"),
        )
    }

    private fun validateLegalTeams(sets: List<FactoryRentalTemplate>) {
        sets.groupBy { it.poolGroup to it.variant }.forEach { (window, candidates) ->
            if (!hasLegalTeam(candidates, DRAFT_SIZE, 0, HashSet(), HashSet())) {
                reject(
                    FactoryCatalogIssueCode.NO_LEGAL_TEAM,
                    "$.sets",
                    "Factory pool ${window.first.name.lowercase()}-${window.second} cannot produce six unique species and held items",
                )
            }
        }
    }

    private fun hasLegalTeam(
        candidates: List<FactoryRentalTemplate>,
        remaining: Int,
        startIndex: Int,
        species: MutableSet<String>,
        heldItems: MutableSet<String>,
    ): Boolean {
        if (remaining == 0) return true
        if (candidates.size - startIndex < remaining) return false
        for (index in startIndex until candidates.size) {
            val candidate = candidates[index]
            if (candidate.speciesId in species || candidate.heldItemId in heldItems) continue
            species += candidate.speciesId
            heldItems += candidate.heldItemId
            if (hasLegalTeam(candidates, remaining - 1, index + 1, species, heldItems)) return true
            species -= candidate.speciesId
            heldItems -= candidate.heldItemId
        }
        return false
    }

    private fun malformed(error: Exception) = FactoryCatalogLoadResult.Rejected(
        listOf(FactoryCatalogIssue(FactoryCatalogIssueCode.MALFORMED_JSON, "$", error.message ?: "Malformed JSON")),
    )
}

private class FactoryDecodeException(val issue: FactoryCatalogIssue) : RuntimeException(issue.message)

private fun reject(code: FactoryCatalogIssueCode, path: String, message: String): Nothing =
    throw FactoryDecodeException(FactoryCatalogIssue(code, path, message))

private inline fun <reified T : Enum<T>> enumValue(id: String, path: String, label: String): T =
    enumValues<T>().singleOrNull { it.name.equals(id, ignoreCase = true) }
        ?: reject(FactoryCatalogIssueCode.INVALID_VALUE, path, "Unknown $label: $id")

private fun JsonElement.requireObject(path: String): JsonObject =
    if (isJsonObject) asJsonObject else reject(FactoryCatalogIssueCode.INVALID_VALUE, path, "Expected an object")

private fun JsonObject.rejectUnknown(path: String, allowed: Set<String>) {
    keySet().firstOrNull { it !in allowed }?.let { reject(FactoryCatalogIssueCode.UNKNOWN_FIELD, "$path.$it", "Unknown field: $it") }
}

private fun JsonObject.requiredElement(path: String, field: String): JsonElement =
    get(field) ?: reject(FactoryCatalogIssueCode.MISSING_FIELD, "$path.$field", "Missing field: $field")

private fun JsonObject.requiredObject(path: String, field: String): JsonObject =
    requiredElement(path, field).requireObject("$path.$field")

private fun JsonObject.requiredArray(path: String, field: String): JsonArray {
    val value = requiredElement(path, field)
    if (!value.isJsonArray) reject(FactoryCatalogIssueCode.INVALID_VALUE, "$path.$field", "Expected an array")
    return value.asJsonArray
}

private fun JsonObject.requiredString(path: String, field: String): String {
    val value = requiredElement(path, field)
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
        reject(FactoryCatalogIssueCode.INVALID_VALUE, "$path.$field", "Expected a string")
    }
    return value.asString
}

private fun JsonObject.requiredInt(path: String, field: String): Int {
    val value = requiredElement(path, field)
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) {
        reject(FactoryCatalogIssueCode.INVALID_VALUE, "$path.$field", "Expected an integer")
    }
    return try {
        value.asBigDecimal.toBigIntegerExact().intValueExact()
    } catch (_: ArithmeticException) {
        reject(FactoryCatalogIssueCode.INVALID_VALUE, "$path.$field", "Expected an integer")
    } catch (_: NumberFormatException) {
        reject(FactoryCatalogIssueCode.INVALID_VALUE, "$path.$field", "Expected an integer")
    }
}

private fun JsonObject.requiredStringList(path: String, field: String): List<String> =
    requiredArray(path, field).mapIndexed { index, value ->
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
            reject(FactoryCatalogIssueCode.INVALID_VALUE, "$path.$field[$index]", "Expected a string")
        }
        value.asString
    }

private fun JsonObject.requiredStableId(path: String, field: String): String =
    requiredString(path, field).also { if (!IdentifierSyntax.isStableId(it)) reject(FactoryCatalogIssueCode.INVALID_VALUE, "$path.$field", "Invalid stable ID: $it") }

private fun JsonObject.requiredTranslationKey(path: String, field: String): String =
    requiredString(path, field).also { if (!TRANSLATION_KEY.matches(it)) reject(FactoryCatalogIssueCode.INVALID_VALUE, "$path.$field", "Invalid translation key: $it") }

private fun JsonObject.requiredResourceId(path: String, field: String): String =
    requiredString(path, field).also { requireResourceId(it, "$path.$field") }

private fun JsonObject.optionalStableId(path: String, field: String): String? {
    val value = get(field) ?: return null
    if (value.isJsonNull) return null
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
        reject(FactoryCatalogIssueCode.INVALID_VALUE, "$path.$field", "Expected a string or null")
    }
    return value.asString.also {
        if (!IdentifierSyntax.isStableId(it)) reject(FactoryCatalogIssueCode.INVALID_VALUE, "$path.$field", "Invalid stable ID: $it")
    }
}

private fun requireResourceId(id: String, path: String) {
    if (!IdentifierSyntax.isResourceId(id)) reject(FactoryCatalogIssueCode.INVALID_VALUE, path, "Invalid resource ID: $id")
}

private fun requireNotEmpty(values: Collection<*>, path: String, label: String) {
    if (values.isEmpty()) reject(FactoryCatalogIssueCode.INVALID_VALUE, path, "$label must not be empty")
}

private fun rejectDuplicates(values: List<String>, path: String) {
    val seen = HashSet<String>()
    values.forEachIndexed { index, value ->
        if (!seen.add(value)) reject(FactoryCatalogIssueCode.DUPLICATE_ID, "$path[$index]", "Duplicate ID: $value")
    }
}

private val SUPPORTED_SCHEMA_VERSIONS = setOf(4)
private const val MAX_AI_SUMMARY = 512
private const val DRAFT_SIZE = 6
private val PRIORITY_RANGE = 0..100
private val TRANSLATION_KEY = Regex("[a-z0-9][a-z0-9_.-]*")
private val ROOT_FIELDS = setOf("schema_version", "catalog_id", "trainers", "sets")
private val SET_FIELDS = setOf(
    "set_id", "pool_group", "variant", "species_id", "form_id", "ability_id", "held_item_id", "nature_id", "moves", "evs", "ivs",
    "roles", "preferred_move_ids", "lead_priority", "preservation_priority",
)
private val TRAINER_FIELDS = setOf(
    "trainer_id", "display_name_key", "description_key", "formats", "weight", "ai_skill", "ai_summary", "objectives",
)
private val STAT_FIELDS = setOf("hp", "attack", "defense", "special_attack", "special_defense", "speed")
