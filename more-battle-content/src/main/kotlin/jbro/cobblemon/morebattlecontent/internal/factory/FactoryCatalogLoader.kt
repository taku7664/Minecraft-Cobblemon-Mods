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
            parseSet(element.requireObject("$.sets[$index]"), "$.sets[$index]", schema)
        }
        val concepts = root.requiredArray("$", "concepts").mapIndexed { index, element ->
            parseConcept(element.requireObject("$.concepts[$index]"), "$.concepts[$index]")
        }
        requireNotEmpty(sets, "$.sets", "sets")
        requireNotEmpty(concepts, "$.concepts", "concepts")
        rejectDuplicates(sets.map(FactoryRentalTemplate::setId), "$.sets")
        rejectDuplicates(concepts.map(FactoryTrainerConcept::conceptId), "$.concepts")
        validateReferences(concepts, sets)
        FactoryCatalogLoadResult.Loaded(FactoryCatalog(catalogId, concepts, sets))
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

    private fun parseSet(value: JsonObject, path: String, schema: Int): FactoryRentalTemplate {
        value.rejectUnknown(
            path,
            when (schema) {
                1 -> SET_FIELDS_SCHEMA_1
                2 -> SET_FIELDS_SCHEMA_2
                else -> SET_FIELDS_SCHEMA_3
            },
        )
        val poolGroupId = value.requiredString(path, "pool_group")
        val poolGroup = enumValue<FactoryPoolGroup>(poolGroupId, "$path.pool_group", "pool group")
        val variant = value.requiredInt(path, "variant")
        if (variant !in 1..4) reject(FactoryCatalogIssueCode.INVALID_VALUE, "$path.variant", "Variant must be between 1 and 4")
        val moveSlots = if (schema >= 3) parseMoveSlots(value, path) else parseLegacyMoveSlots(value, path)
        val heldItemIds = if (schema >= 3) parseHeldItemCandidates(value, path) else {
            listOf(value.optionalResourceId(path, "held_item_id"))
        }
        val natureIds = if (schema >= 3) {
            val naturePool = value.requiredString(path, "nature_pool")
            if (naturePool != "all") {
                reject(FactoryCatalogIssueCode.INVALID_VALUE, "$path.nature_pool", "Nature pool must be 'all'")
            }
            FactoryNaturePool.ALL
        } else {
            listOf(value.requiredResourceId(path, "nature_id"))
        }
        return FactoryRentalTemplate(
            setId = value.requiredStableId(path, "set_id"),
            poolGroup = poolGroup,
            variant = variant,
            speciesId = value.requiredResourceId(path, "species_id"),
            moveSlots = moveSlots,
            abilityId = value.requiredResourceId(path, "ability_id"),
            heldItemIds = heldItemIds,
            natureIds = natureIds,
            evs = parseEvs(value.requiredObject(path, "evs"), "$path.evs"),
            ivs = if (schema >= 2) parseIvs(value.requiredObject(path, "ivs"), "$path.ivs") else null,
            formId = value.optionalStableId(path, "form_id"),
        )
    }

    private fun parseLegacyMoveSlots(value: JsonObject, path: String): List<List<String>> {
        val moves = value.requiredStringList(path, "moves")
        if (moves.size !in 1..4) reject(FactoryCatalogIssueCode.INVALID_VALUE, "$path.moves", "Moves must contain 1 to 4 IDs")
        rejectDuplicates(moves, "$path.moves")
        moves.forEachIndexed { index, id -> requireResourceId(id, "$path.moves[$index]") }
        return moves.map(::listOf)
    }

    private fun parseMoveSlots(value: JsonObject, path: String): List<List<String>> {
        val slots = value.requiredArray(path, "move_slots").mapIndexed { slotIndex, element ->
            if (!element.isJsonArray) {
                reject(FactoryCatalogIssueCode.INVALID_VALUE, "$path.move_slots[$slotIndex]", "Expected an array")
            }
            element.asJsonArray.mapIndexed { candidateIndex, candidate ->
                if (!candidate.isJsonPrimitive || !candidate.asJsonPrimitive.isString) {
                    reject(
                        FactoryCatalogIssueCode.INVALID_VALUE,
                        "$path.move_slots[$slotIndex][$candidateIndex]",
                        "Expected a string",
                    )
                }
                candidate.asString.also { requireResourceId(it, "$path.move_slots[$slotIndex][$candidateIndex]") }
            }.also { candidates ->
                requireNotEmpty(candidates, "$path.move_slots[$slotIndex]", "move slot")
                rejectDuplicates(candidates, "$path.move_slots[$slotIndex]")
            }
        }
        if (slots.size != 4) {
            reject(FactoryCatalogIssueCode.INVALID_VALUE, "$path.move_slots", "Schema 3 requires exactly 4 move slots")
        }
        rejectDuplicates(slots.flatten(), "$path.move_slots")
        return slots
    }

    private fun parseHeldItemCandidates(value: JsonObject, path: String): List<String> =
        value.requiredStringList(path, "held_items").also { items ->
            requireNotEmpty(items, "$path.held_items", "held_items")
            items.forEachIndexed { index, id -> requireResourceId(id, "$path.held_items[$index]") }
            rejectDuplicates(items, "$path.held_items")
        }

    private fun parseConcept(value: JsonObject, path: String): FactoryTrainerConcept {
        value.rejectUnknown(path, CONCEPT_FIELDS)
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
        val members = value.requiredArray(path, "members").mapIndexed { index, element ->
            parseMember(element.requireObject("$path.members[$index]"), "$path.members[$index]")
        }
        if (members.size < formats.maxOf(FactoryBattleFormat::selectionSize)) {
            reject(FactoryCatalogIssueCode.INVALID_CONCEPT, "$path.members", "Concept does not define enough team member plans")
        }
        rejectDuplicates(members.map(FactoryConceptMemberPlan::planId), "$path.members")
        val aceMembers = members.filter { BattleTeamRole.ACE in it.roles }
        if (aceMembers.size != 1 || !aceMembers.single().required) {
            reject(FactoryCatalogIssueCode.INVALID_CONCEPT, "$path.members", "Concept requires exactly one required ace")
        }
        if (members.none { member -> member.roles.any { it in ENABLING_ROLES } }) {
            reject(FactoryCatalogIssueCode.INVALID_CONCEPT, "$path.members", "Concept requires an ace enabler or weakness complement")
        }
        return FactoryTrainerConcept(
            conceptId = value.requiredStableId(path, "concept_id"),
            displayNameKey = value.requiredTranslationKey(path, "display_name_key"),
            descriptionKey = value.requiredTranslationKey(path, "description_key"),
            formats = formats,
            weight = weight,
            aiSkill = aiSkill,
            aiSummary = aiSummary,
            objectives = objectives,
            members = members,
        )
    }

    private fun parseMember(value: JsonObject, path: String): FactoryConceptMemberPlan {
        value.rejectUnknown(path, MEMBER_FIELDS)
        val roles = value.requiredStringList(path, "roles").mapIndexed { index, id ->
            enumValue<BattleTeamRole>(id, "$path.roles[$index]", "team role")
        }.toSet()
        if (roles.isEmpty()) reject(FactoryCatalogIssueCode.INVALID_VALUE, "$path.roles", "Roles must not be empty")
        val summary = value.requiredString(path, "tactical_summary")
        if (summary.isBlank() || summary.length > MAX_TACTICAL_SUMMARY) {
            reject(
                FactoryCatalogIssueCode.INVALID_VALUE,
                "$path.tactical_summary",
                "Tactical summary must contain 1 to $MAX_TACTICAL_SUMMARY characters",
            )
        }
        val preferredMoves = value.requiredStringList(path, "preferred_move_ids")
        preferredMoves.forEachIndexed { index, id -> requireResourceId(id, "$path.preferred_move_ids[$index]") }
        rejectDuplicates(preferredMoves, "$path.preferred_move_ids")
        val setIds = value.requiredStringList(path, "set_pool")
        requireNotEmpty(setIds, "$path.set_pool", "set_pool")
        rejectDuplicates(setIds, "$path.set_pool")
        val leadPriority = value.requiredInt(path, "lead_priority")
        val preservationPriority = value.requiredInt(path, "preservation_priority")
        if (leadPriority !in PRIORITY_RANGE) reject(FactoryCatalogIssueCode.INVALID_VALUE, "$path.lead_priority", "Lead priority must be between 0 and 100")
        if (preservationPriority !in PRIORITY_RANGE) {
            reject(FactoryCatalogIssueCode.INVALID_VALUE, "$path.preservation_priority", "Preservation priority must be between 0 and 100")
        }
        return FactoryConceptMemberPlan(
            planId = value.requiredStableId(path, "plan_id"),
            required = value.requiredBoolean(path, "required"),
            roles = roles,
            tacticalSummary = summary,
            preferredMoveIds = preferredMoves.toSet(),
            leadPriority = leadPriority,
            preservationPriority = preservationPriority,
            setIds = setIds,
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

    private fun validateReferences(concepts: List<FactoryTrainerConcept>, sets: List<FactoryRentalTemplate>) {
        val byId = sets.associateBy(FactoryRentalTemplate::setId)
        concepts.forEachIndexed { conceptIndex, concept ->
            val usedSetIds = HashSet<String>()
            concept.members.forEachIndexed { memberIndex, member ->
                val memberSets = member.setIds.mapIndexed { setIndex, id ->
                    byId[id] ?: reject(
                        FactoryCatalogIssueCode.UNKNOWN_REFERENCE,
                        "$.concepts[$conceptIndex].members[$memberIndex].set_pool[$setIndex]",
                        "Unknown Factory set ID: $id",
                    )
                }
                member.setIds.forEach { id ->
                    if (!usedSetIds.add(id)) {
                        reject(
                            FactoryCatalogIssueCode.INVALID_CONCEPT,
                            "$.concepts[$conceptIndex].members[$memberIndex].set_pool",
                            "A set cannot fill two roles in one concept: $id",
                        )
                    }
                }
                member.preferredMoveIds.forEach { moveId ->
                    if (memberSets.none { moveId in it.moveIds }) {
                        reject(
                            FactoryCatalogIssueCode.INVALID_CONCEPT,
                            "$.concepts[$conceptIndex].members[$memberIndex].preferred_move_ids",
                            "Preferred move is unavailable to this member: $moveId",
                        )
                    }
                }
            }
            val catalog = FactoryCatalog("validation", listOf(concept), sets)
            concept.formats.forEach { format ->
                if (FactoryConceptTeamSearch.select(catalog, concept, format) == null) {
                    reject(
                        FactoryCatalogIssueCode.NO_LEGAL_TEAM,
                        "$.concepts[$conceptIndex].members",
                        "Concept cannot produce a legal ${format.name.lowercase()} team",
                    )
                }
            }
        }
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

private fun JsonObject.requiredBoolean(path: String, field: String): Boolean {
    val value = requiredElement(path, field)
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) {
        reject(FactoryCatalogIssueCode.INVALID_VALUE, "$path.$field", "Expected a boolean")
    }
    return value.asBoolean
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

private fun JsonObject.optionalResourceId(path: String, field: String): String? {
    val value = get(field) ?: return null
    if (value.isJsonNull) return null
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
        reject(FactoryCatalogIssueCode.INVALID_VALUE, "$path.$field", "Expected a string or null")
    }
    return value.asString.also { requireResourceId(it, "$path.$field") }
}

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

private val SUPPORTED_SCHEMA_VERSIONS = setOf(1, 2, 3)
private const val MAX_AI_SUMMARY = 512
private const val MAX_TACTICAL_SUMMARY = 256
private val PRIORITY_RANGE = 0..100
private val TRANSLATION_KEY = Regex("[a-z0-9][a-z0-9_.-]*")
private val ENABLING_ROLES = setOf(
    BattleTeamRole.SETUP_ENABLER,
    BattleTeamRole.WEAKNESS_COVER,
    BattleTeamRole.PIVOT,
    BattleTeamRole.SPEED_CONTROL,
    BattleTeamRole.FIELD_SUPPORT,
    BattleTeamRole.DISRUPTOR,
)
private val ROOT_FIELDS = setOf("schema_version", "catalog_id", "concepts", "sets")
private val SET_FIELDS_SCHEMA_1 = setOf(
    "set_id", "pool_group", "variant", "species_id", "form_id", "ability_id", "held_item_id", "nature_id", "moves", "evs",
)
private val SET_FIELDS_SCHEMA_2 = SET_FIELDS_SCHEMA_1 + "ivs"
private val SET_FIELDS_SCHEMA_3 = setOf(
    "set_id", "pool_group", "variant", "species_id", "form_id", "ability_id", "held_items", "nature_pool", "move_slots", "evs", "ivs",
)
private val CONCEPT_FIELDS = setOf(
    "concept_id", "display_name_key", "description_key", "formats", "weight", "ai_skill", "ai_summary", "objectives", "members",
)
private val MEMBER_FIELDS = setOf(
    "plan_id", "required", "roles", "tactical_summary", "preferred_move_ids", "lead_priority", "preservation_priority", "set_pool",
)
private val STAT_FIELDS = setOf("hp", "attack", "defense", "special_attack", "special_defense", "speed")
