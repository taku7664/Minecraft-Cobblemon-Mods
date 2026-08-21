package jbro.cobblemon.morebattlecontent.internal.bp.shop

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.io.Reader
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import jbro.cobblemon.morebattlecontent.internal.validation.IdentifierSyntax

internal object BattlePointShopCatalogLoader {
    fun load(reader: Reader, itemExists: (String) -> Boolean): BattlePointShopCatalogLoadResult = try {
        val root = JsonParser.parseReader(reader).requireObject("$")
        root.rejectUnknownFields("$", ROOT_FIELDS)
        val schema = root.requiredInt("$", "schema_version")
        if (schema != 1) reject(BattlePointShopCatalogIssueCode.UNSUPPORTED_SCHEMA, "$.schema_version", "Unsupported shop catalog schema: $schema")

        val catalogId = root.requiredStableId("$", "catalog_id")
        val limitsObject = root.requiredObject("$", "limits").also { it.rejectUnknownFields("$.limits", LIMIT_FIELDS) }
        val limits = BattlePointShopLimits(
            maxCartLines = limitsObject.requiredPositiveInt("$.limits", "max_cart_lines"),
            maxQuantityPerLine = limitsObject.requiredPositiveInt("$.limits", "max_quantity_per_line"),
            maxTotalItems = limitsObject.requiredPositiveInt("$.limits", "max_total_items"),
        )
        val entries = root.requiredArray("$", "entries").mapIndexed { index, element ->
            parseEntry(element.requireObject("$.entries[$index]"), "$.entries[$index]", itemExists)
        }
        if (entries.isEmpty()) reject(BattlePointShopCatalogIssueCode.INVALID_VALUE, "$.entries", "entries must not be empty")
        rejectDuplicates(entries.map(BattlePointShopEntry::entryId), "$.entries", "entry ID")
        rejectDuplicates(entries.map { it.sortOrder.toString() }, "$.entries", "sort order")

        BattlePointShopCatalogLoadResult.Loaded(
            BattlePointShopCatalog(catalogId, revision(catalogId, limits, entries), limits, entries),
        )
    } catch (error: ShopCatalogDecodeException) {
        BattlePointShopCatalogLoadResult.Rejected(listOf(error.issue))
    } catch (error: JsonParseException) {
        malformed(error)
    } catch (error: IllegalStateException) {
        malformed(error)
    }

    fun loadSeparated(
        ruleFragments: List<Pair<String, Reader>>,
        entryFragments: List<Pair<String, Reader>>,
        itemExists: (String) -> Boolean,
    ): BattlePointShopCatalogLoadResult = try {
        if (ruleFragments.size != 1) {
            reject(
                BattlePointShopCatalogIssueCode.INVALID_VALUE,
                "$.rules",
                "Exactly one BP shop rules JSON file must be active, found ${ruleFragments.size}",
            )
        }
        if (entryFragments.isEmpty()) {
            reject(BattlePointShopCatalogIssueCode.MISSING_FIELD, "$.entries", "No BP shop entry JSON files were found")
        }

        val (ruleResourceId, ruleReader) = ruleFragments.single()
        val rulePath = "resource[$ruleResourceId]"
        val rules = ruleReader.use { JsonParser.parseReader(it).requireObject(rulePath) }
        rules.rejectUnknownFields(rulePath, RULE_ROOT_FIELDS)
        val ruleSchema = rules.requiredInt(rulePath, "schema_version")
        if (ruleSchema != RULE_SCHEMA_VERSION) {
            reject(
                BattlePointShopCatalogIssueCode.UNSUPPORTED_SCHEMA,
                "$rulePath.schema_version",
                "Unsupported BP shop rules schema: $ruleSchema",
            )
        }
        val catalogId = rules.requiredStableId(rulePath, "catalog_id")
        val limitsObject = rules.requiredObject(rulePath, "limits").also {
            it.rejectUnknownFields("$rulePath.limits", LIMIT_FIELDS)
        }
        val limits = BattlePointShopLimits(
            maxCartLines = limitsObject.requiredPositiveInt("$rulePath.limits", "max_cart_lines"),
            maxQuantityPerLine = limitsObject.requiredPositiveInt("$rulePath.limits", "max_quantity_per_line"),
            maxTotalItems = limitsObject.requiredPositiveInt("$rulePath.limits", "max_total_items"),
        )
        val entries = entryFragments.flatMap { (resourceId, reader) ->
            val path = "resource[$resourceId]"
            val root = reader.use { JsonParser.parseReader(it).requireObject(path) }
            root.rejectUnknownFields(path, ENTRY_ROOT_FIELDS)
            val schema = root.requiredInt(path, "schema_version")
            if (schema != ENTRY_SCHEMA_VERSION) {
                reject(
                    BattlePointShopCatalogIssueCode.UNSUPPORTED_SCHEMA,
                    "$path.schema_version",
                    "Unsupported BP shop entry schema: $schema",
                )
            }
            root.requiredArray(path, "entries").mapIndexed { index, element ->
                parseEntry(element.requireObject("$path.entries[$index]"), "$path.entries[$index]", itemExists)
            }
        }
        if (entries.isEmpty()) reject(BattlePointShopCatalogIssueCode.INVALID_VALUE, "$.entries", "entries must not be empty")
        rejectDuplicates(entries.map(BattlePointShopEntry::entryId), "$.entries", "entry ID")
        rejectDuplicates(entries.map { it.sortOrder.toString() }, "$.entries", "sort order")

        BattlePointShopCatalogLoadResult.Loaded(
            BattlePointShopCatalog(catalogId, revision(catalogId, limits, entries), limits, entries),
        )
    } catch (error: ShopCatalogDecodeException) {
        BattlePointShopCatalogLoadResult.Rejected(listOf(error.issue))
    } catch (error: JsonParseException) {
        malformed(error)
    } catch (error: IllegalStateException) {
        malformed(error)
    }

    private fun parseEntry(
        value: JsonObject,
        path: String,
        itemExists: (String) -> Boolean,
    ): BattlePointShopEntry {
        value.rejectUnknownFields(path, ENTRY_FIELDS)
        val itemId = value.requiredResourceId(path, "item_id")
        if (!itemExists(itemId)) {
            reject(BattlePointShopCatalogIssueCode.UNAVAILABLE_ITEM, "$path.item_id", "Unavailable item: $itemId")
        }
        return BattlePointShopEntry(
            entryId = value.requiredStableId(path, "entry_id"),
            itemId = itemId,
            itemCount = value.requiredPositiveInt(path, "item_count"),
            priceBp = value.requiredPositiveLong(path, "price_bp"),
            sortOrder = value.requiredInt(path, "sort_order"),
        )
    }

    private fun revision(
        catalogId: String,
        limits: BattlePointShopLimits,
        entries: List<BattlePointShopEntry>,
    ): String {
        val canonical = buildString {
            append(catalogId).append('\n')
            append(limits.maxCartLines).append(':')
                .append(limits.maxQuantityPerLine).append(':')
                .append(limits.maxTotalItems).append('\n')
            entries.sortedBy(BattlePointShopEntry::entryId).forEach { entry ->
                append(entry.entryId).append(':').append(entry.itemId).append(':')
                    .append(entry.itemCount).append(':').append(entry.priceBp).append(':')
                    .append(entry.sortOrder).append('\n')
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun malformed(error: Exception) = BattlePointShopCatalogLoadResult.Rejected(
        listOf(BattlePointShopCatalogIssue(BattlePointShopCatalogIssueCode.MALFORMED_JSON, "$", error.message ?: "Malformed JSON")),
    )
}

private class ShopCatalogDecodeException(val issue: BattlePointShopCatalogIssue) : RuntimeException(issue.message)

private fun reject(code: BattlePointShopCatalogIssueCode, path: String, message: String): Nothing =
    throw ShopCatalogDecodeException(BattlePointShopCatalogIssue(code, path, message))

private fun JsonElement.requireObject(path: String): JsonObject =
    if (isJsonObject) asJsonObject else reject(BattlePointShopCatalogIssueCode.INVALID_VALUE, path, "Expected an object")

private fun JsonObject.rejectUnknownFields(path: String, allowed: Set<String>) {
    keySet().firstOrNull { it !in allowed }?.let { field ->
        reject(BattlePointShopCatalogIssueCode.UNKNOWN_FIELD, "$path.$field", "Unknown field: $field")
    }
}

private fun JsonObject.requiredElement(path: String, field: String): JsonElement =
    get(field) ?: reject(BattlePointShopCatalogIssueCode.MISSING_FIELD, "$path.$field", "Missing field: $field")

private fun JsonObject.requiredObject(path: String, field: String): JsonObject = requiredElement(path, field).requireObject("$path.$field")

private fun JsonObject.requiredArray(path: String, field: String): JsonArray {
    val element = requiredElement(path, field)
    if (!element.isJsonArray) reject(BattlePointShopCatalogIssueCode.INVALID_VALUE, "$path.$field", "Expected an array")
    return element.asJsonArray
}

private fun JsonObject.requiredString(path: String, field: String): String {
    val element = requiredElement(path, field)
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
        reject(BattlePointShopCatalogIssueCode.INVALID_VALUE, "$path.$field", "Expected a string")
    }
    return element.asString
}

private fun JsonObject.requiredLong(path: String, field: String): Long {
    val element = requiredElement(path, field)
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
        reject(BattlePointShopCatalogIssueCode.INVALID_VALUE, "$path.$field", "Expected an integer")
    }
    return try {
        element.asBigDecimal.toBigIntegerExact().longValueExact()
    } catch (_: ArithmeticException) {
        reject(BattlePointShopCatalogIssueCode.INVALID_VALUE, "$path.$field", "Expected an integer")
    } catch (_: NumberFormatException) {
        reject(BattlePointShopCatalogIssueCode.INVALID_VALUE, "$path.$field", "Expected an integer")
    }
}

private fun JsonObject.requiredInt(path: String, field: String): Int = try {
    Math.toIntExact(requiredLong(path, field))
} catch (_: ArithmeticException) {
    reject(BattlePointShopCatalogIssueCode.INVALID_VALUE, "$path.$field", "Expected a 32-bit integer")
}

private fun JsonObject.requiredPositiveInt(path: String, field: String): Int =
    requiredInt(path, field).also { if (it <= 0) reject(BattlePointShopCatalogIssueCode.INVALID_VALUE, "$path.$field", "$field must be positive") }

private fun JsonObject.requiredPositiveLong(path: String, field: String): Long =
    requiredLong(path, field).also { if (it <= 0) reject(BattlePointShopCatalogIssueCode.INVALID_VALUE, "$path.$field", "$field must be positive") }

private fun JsonObject.requiredStableId(path: String, field: String): String =
    requiredString(path, field).also { value ->
        if (!IdentifierSyntax.isStableId(value)) reject(BattlePointShopCatalogIssueCode.INVALID_VALUE, "$path.$field", "Invalid stable ID: $value")
    }

private fun JsonObject.requiredResourceId(path: String, field: String): String =
    requiredString(path, field).also { value ->
        if (!IdentifierSyntax.isResourceId(value)) reject(BattlePointShopCatalogIssueCode.INVALID_VALUE, "$path.$field", "Invalid resource ID: $value")
    }

private fun rejectDuplicates(values: List<String>, path: String, label: String) {
    val seen = HashSet<String>()
    values.forEachIndexed { index, value ->
        if (!seen.add(value)) reject(BattlePointShopCatalogIssueCode.DUPLICATE_ID, "$path[$index]", "Duplicate $label: $value")
    }
}

private val ROOT_FIELDS = setOf("schema_version", "catalog_id", "limits", "entries")
private val RULE_ROOT_FIELDS = setOf("schema_version", "catalog_id", "limits")
private val ENTRY_ROOT_FIELDS = setOf("schema_version", "entries")
private val LIMIT_FIELDS = setOf("max_cart_lines", "max_quantity_per_line", "max_total_items")
private val ENTRY_FIELDS = setOf("entry_id", "item_id", "item_count", "price_bp", "sort_order")
private const val RULE_SCHEMA_VERSION = 1
private const val ENTRY_SCHEMA_VERSION = 1
