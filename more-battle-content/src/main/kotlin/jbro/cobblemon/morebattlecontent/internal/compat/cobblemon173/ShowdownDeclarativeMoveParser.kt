package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import jbro.cobblemon.morebattlecontent.api.ai.BattleFractionRange
import jbro.cobblemon.morebattlecontent.api.ai.BattleIntegerRange
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectCoverage
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectTarget
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectView
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectsView
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveRequirementKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveRequirementView

/** Extracts only static object-literal facts. JavaScript callbacks are never executed or guessed. */
internal object ShowdownDeclarativeMoveParser {
    fun parse(source: String): Map<String, BattleMoveEffectsView> {
        val marker = source.indexOf(MOVES_MARKER)
        if (marker < 0) return emptyMap()
        val objectStart = source.indexOf('{', marker + MOVES_MARKER.length)
        if (objectStart < 0) return emptyMap()
        val objectEnd = matchingDelimiter(source, objectStart, '{', '}') ?: return emptyMap()
        return objectProperties(source.substring(objectStart + 1, objectEnd)).mapNotNull { (moveId, raw) ->
            raw.takeIf { it.startsWith('{') }?.let { canonicalMoveId(moveId) to parseMove(it) }
        }.toMap(linkedMapOf())
    }

    private fun parseMove(rawObject: String): BattleMoveEffectsView {
        val properties = objectProperties(trimContainer(rawObject, '{', '}'))
        val effects = arrayListOf<BattleMoveEffectView>()
        val requirements = moveRequirements(rawObject)
        val moveTarget = stringValue(properties["target"])
        val directTarget = if (moveTarget == "self") BattleMoveEffectTarget.USER else BattleMoveEffectTarget.SELECTED_TARGET

        fraction(properties["heal"])?.let {
            effects += effect(BattleMoveEffectKind.HEAL_FRACTION, BattleMoveEffectTarget.USER, fraction = it)
        }
        fraction(properties["drain"])?.let {
            effects += effect(BattleMoveEffectKind.DRAIN_FRACTION, BattleMoveEffectTarget.USER, fraction = it)
        }
        fraction(properties["recoil"])?.let {
            effects += effect(BattleMoveEffectKind.RECOIL_FRACTION, BattleMoveEffectTarget.USER, fraction = it)
        }
        integerRange(properties["multihit"])?.let {
            effects += effect(BattleMoveEffectKind.MULTI_HIT, BattleMoveEffectTarget.SELECTED_TARGET, amount = it)
        }
        properties["damage"]?.let { rawDamage ->
            when {
                stringValue(rawDamage) == "level" -> effects += effect(
                    BattleMoveEffectKind.FIXED_DAMAGE_LEVEL,
                    BattleMoveEffectTarget.SELECTED_TARGET,
                )
                rawDamage.trim().toIntOrNull()?.let { it > 0 } == true -> effects += effect(
                    BattleMoveEffectKind.FIXED_DAMAGE_VALUE,
                    BattleMoveEffectTarget.SELECTED_TARGET,
                    amount = BattleIntegerRange(rawDamage.trim().toInt(), rawDamage.trim().toInt()),
                )
            }
        }

        addStatusAndBoostEffects(effects, properties, directTarget, 1.0)
        properties["self"]?.takeIf { it.startsWith('{') }?.let {
            addStatusAndBoostEffects(effects, objectProperties(trimContainer(it, '{', '}')), BattleMoveEffectTarget.USER, 1.0)
        }
        properties["selfBoost"]?.takeIf { it.startsWith('{') }?.let {
            addStatusAndBoostEffects(effects, objectProperties(trimContainer(it, '{', '}')), BattleMoveEffectTarget.USER, 1.0)
        }
        properties["secondary"]?.takeIf { it.startsWith('{') }?.let {
            addSecondaryEffects(effects, it)
        }
        properties["secondaries"]?.takeIf { it.startsWith('[') }?.let { rawArray ->
            arrayObjects(trimContainer(rawArray, '[', ']')).forEach { addSecondaryEffects(effects, it) }
        }

        if (booleanLike(properties["selfSwitch"])) {
            effects += effect(BattleMoveEffectKind.SWITCH_USER, BattleMoveEffectTarget.USER)
        }
        if (booleanLike(properties["forceSwitch"])) {
            effects += effect(BattleMoveEffectKind.SWITCH_TARGET, BattleMoveEffectTarget.SELECTED_TARGET)
        }
        val stallingMove = booleanLike(properties["stallingMove"])
        val usesSharedStallCheck = STALL_MOVE_CHECK_PATTERN.containsMatchIn(rawObject)
        val advancesSharedStallCounter = STALL_COUNTER_ADVANCE_PATTERN.containsMatchIn(rawObject)
        if (stallingMove) {
            effects += effect(BattleMoveEffectKind.PROTECT_USER, BattleMoveEffectTarget.USER)
        }
        stringValue(properties["sideCondition"])?.let { condition ->
            val target = if (moveTarget == "allySide" || moveTarget == "allyTeam") {
                BattleMoveEffectTarget.USER_SIDE
            } else {
                BattleMoveEffectTarget.TARGET_SIDE
            }
            val maximumStacks = MAXIMUM_LAYERS_PATTERN.find(properties["condition"].orEmpty())
                ?.groupValues?.get(1)?.toIntOrNull()
            effects += effect(
                BattleMoveEffectKind.SIDE_CONDITION,
                target,
                valueId = condition,
                amount = maximumStacks?.let { BattleIntegerRange(1, it) },
            )
        }
        stringValue(properties["slotCondition"])?.let {
            effects += effect(BattleMoveEffectKind.SLOT_CONDITION, directTarget, valueId = it)
        }
        stringValue(properties["pseudoWeather"])?.let {
            effects += effect(BattleMoveEffectKind.FIELD_CONDITION, BattleMoveEffectTarget.FIELD, valueId = it)
        }
        stringValue(properties["weather"])?.let {
            effects += effect(BattleMoveEffectKind.WEATHER, BattleMoveEffectTarget.FIELD, valueId = it)
        }
        stringValue(properties["terrain"])?.let {
            effects += effect(BattleMoveEffectKind.TERRAIN, BattleMoveEffectTarget.FIELD, valueId = it)
        }
        if (booleanLike(properties["selfdestruct"])) {
            effects += effect(BattleMoveEffectKind.SELF_DESTRUCT, BattleMoveEffectTarget.USER)
        }

        val flags = properties["flags"]?.takeIf { it.startsWith('{') }
            ?.let { objectProperties(trimContainer(it, '{', '}')) }.orEmpty()
        if (flagEnabled(flags["futuremove"])) {
            effects += effect(
                BattleMoveEffectKind.SLOT_CONDITION,
                BattleMoveEffectTarget.SELECTED_TARGET,
                valueId = "futuremove",
            )
        }
        if (flags["charge"]?.trim()?.toIntOrNull() == 1) {
            effects += effect(BattleMoveEffectKind.CHARGE_TURN, BattleMoveEffectTarget.USER)
            CHARGE_SKIP_WEATHER_PATTERN.find(rawObject)?.groupValues?.get(1)?.let(::quotedValues)
                ?.forEach { weatherId ->
                    effects += effect(
                        BattleMoveEffectKind.CHARGE_SKIP_WEATHER,
                        BattleMoveEffectTarget.FIELD,
                        valueId = weatherId,
                    )
                }
        }
        if (flags["recharge"]?.trim()?.toIntOrNull() == 1 || booleanLike(properties["mustrecharge"])) {
            effects += effect(BattleMoveEffectKind.RECHARGE_TURN, BattleMoveEffectTarget.USER)
        }
        if (FIRST_ACTIVE_TURN_ONLY_PATTERN.containsMatchIn(rawObject)) {
            effects += effect(BattleMoveEffectKind.FIRST_ACTIVE_TURN_ONLY, BattleMoveEffectTarget.USER)
        }
        if (booleanLike(properties["ohko"])) {
            effects += effect(BattleMoveEffectKind.ONE_HIT_KO, BattleMoveEffectTarget.SELECTED_TARGET)
        }
        if (booleanLike(properties["hasCrashDamage"])) {
            effects += effect(BattleMoveEffectKind.CRASH_RECOIL, BattleMoveEffectTarget.USER)
        }
        if (booleanLike(properties["mindBlownRecoil"])) {
            effects += effect(
                BattleMoveEffectKind.MAX_HP_RECOIL,
                BattleMoveEffectTarget.USER,
                fraction = BattleFractionRange(0.5, 0.5),
            )
        }
        if (booleanLike(properties["struggleRecoil"])) {
            effects += effect(
                BattleMoveEffectKind.STRUGGLE_RECOIL,
                BattleMoveEffectTarget.USER,
                fraction = BattleFractionRange(0.25, 0.25),
            )
        }
        addBooleanEffect(effects, properties, "breaksProtect", BattleMoveEffectKind.BREAKS_PROTECTION)
        addBooleanEffect(effects, properties, "willCrit", BattleMoveEffectKind.ALWAYS_CRITICAL)
        addBooleanEffect(effects, properties, "stealsBoosts", BattleMoveEffectKind.STEALS_STAT_STAGES)
        addBooleanEffect(effects, properties, "thawsTarget", BattleMoveEffectKind.THAWS_TARGET)
        addBooleanEffect(effects, properties, "sleepUsable", BattleMoveEffectKind.USABLE_WHILE_ASLEEP)
        addBooleanEffect(effects, properties, "multiaccuracy", BattleMoveEffectKind.MULTI_ACCURACY)
        addBooleanEffect(effects, properties, "ignoreAbility", BattleMoveEffectKind.IGNORE_ABILITY)
        addBooleanEffect(effects, properties, "ignoreDefensive", BattleMoveEffectKind.IGNORE_DEFENSIVE_STAGES)
        addBooleanEffect(effects, properties, "ignoreEvasion", BattleMoveEffectKind.IGNORE_EVASION_STAGES)
        if (properties["ignoreImmunity"] != null && properties["ignoreImmunity"]?.trim() != "false") {
            effects += effect(BattleMoveEffectKind.IGNORE_TYPE_IMMUNITY, BattleMoveEffectTarget.SELECTED_TARGET)
        }

        val scripted = properties.any { (key, value) ->
            key.startsWith("on") || key.endsWith("Callback") || key == "condition" ||
                value.contains("=>") || value.contains("function(")
        }
        return BattleMoveEffectsView(
            coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
            effects = effects,
            scriptedBehavior = scripted,
            requirements = requirements,
            mechanicFlags = buildSet {
                addAll(flags.filterValues(::flagEnabled).keys)
                if (usesSharedStallCheck) add(STALLING_MOVE_FLAG)
                if (advancesSharedStallCounter) add(STALL_COUNTER_ADVANCE_FLAG)
            },
        )
    }

    private fun addBooleanEffect(
        effects: MutableList<BattleMoveEffectView>,
        properties: Map<String, String>,
        property: String,
        kind: BattleMoveEffectKind,
    ) {
        if (booleanLike(properties[property])) {
            effects += effect(kind, BattleMoveEffectTarget.SELECTED_TARGET)
        }
    }

    private fun moveRequirements(rawObject: String): List<BattleMoveRequirementView> {
        val requirements = arrayListOf<BattleMoveRequirementView>()

        WEATHER_REQUIREMENT_PATTERN.find(rawObject)?.groupValues?.get(1)?.let(::quotedValues)?.takeIf {
            it.isNotEmpty()
        }?.let {
            requirements += requirement(BattleMoveRequirementKind.WEATHER_ANY_OF, it)
        }
        if (TERRAIN_PRESENT_PATTERN.containsMatchIn(rawObject)) {
            requirements += requirement(BattleMoveRequirementKind.TERRAIN_PRESENT)
        }
        if (USER_SLEEP_PATTERN.containsMatchIn(rawObject)) {
            requirements += requirement(BattleMoveRequirementKind.USER_STATUS_ANY_OF, setOf("slp"))
        }
        if (TARGET_SLEEP_PATTERN.containsMatchIn(rawObject)) {
            requirements += requirement(BattleMoveRequirementKind.TARGET_STATUS_ANY_OF, setOf("slp"))
        }
        if (USER_STATUS_PRESENT_PATTERN.containsMatchIn(rawObject)) {
            requirements += requirement(BattleMoveRequirementKind.USER_STATUS_PRESENT)
        }
        if (TARGET_STATUS_ABSENT_PATTERN.containsMatchIn(rawObject)) {
            requirements += requirement(BattleMoveRequirementKind.TARGET_STATUS_ABSENT)
        }
        USER_TYPE_PATTERN.findAll(rawObject).mapTo(linkedSetOf()) { it.groupValues[1].lowercase() }
            .takeIf { it.isNotEmpty() }?.let {
                requirements += requirement(BattleMoveRequirementKind.USER_TYPE_ANY_OF, it)
            }

        userHpThreshold(rawObject)?.let {
            requirements += requirement(BattleMoveRequirementKind.USER_HP_ABOVE_FRACTION, threshold = it)
        }
        if (TARGET_HP_ABOVE_USER_PATTERN.containsMatchIn(rawObject)) {
            requirements += requirement(BattleMoveRequirementKind.TARGET_HP_ABOVE_USER)
        }
        if (USER_HELD_BERRY_PATTERN.containsMatchIn(rawObject)) {
            requirements += requirement(BattleMoveRequirementKind.USER_HELD_BERRY)
        }
        if (TARGET_HELD_ITEM_PATTERN.containsMatchIn(rawObject)) {
            requirements += requirement(BattleMoveRequirementKind.TARGET_HELD_ITEM_PRESENT)
        }
        if (FAINTED_ALLY_PATTERN.containsMatchIn(rawObject)) {
            requirements += requirement(BattleMoveRequirementKind.FAINTED_ALLY_PRESENT)
        }
        if (RESERVE_ALLY_PATTERN.containsMatchIn(rawObject)) {
            requirements += requirement(BattleMoveRequirementKind.RESERVE_ALLY_PRESENT)
        }
        if (PRIOR_DAMAGE_PATTERN.containsMatchIn(rawObject)) {
            requirements += requirement(BattleMoveRequirementKind.PRIOR_DAMAGE_THIS_TURN)
        }
        if (OTHER_MOVES_USED_PATTERN.containsMatchIn(rawObject)) {
            requirements += requirement(BattleMoveRequirementKind.OTHER_MOVES_USED)
        }
        if (STOCKPILE_PATTERN.containsMatchIn(rawObject)) {
            requirements += requirement(BattleMoveRequirementKind.USER_VOLATILE_PRESENT, setOf("stockpile"))
        }
        if (TARGET_LAST_MOVE_PATTERN.containsMatchIn(rawObject)) {
            requirements += requirement(BattleMoveRequirementKind.TARGET_LAST_MOVE_PRESENT)
        }
        if (TARGET_PENDING_ATTACK_PATTERN.containsMatchIn(rawObject)) {
            requirements += requirement(BattleMoveRequirementKind.TARGET_PENDING_DAMAGING_MOVE)
        }
        USER_SPECIES_PATTERN.findAll(rawObject).mapTo(linkedSetOf()) {
            it.groupValues[1].lowercase().filter(Char::isLetterOrDigit)
        }.takeIf { it.isNotEmpty() }?.let {
            requirements += requirement(BattleMoveRequirementKind.USER_SPECIES_ANY_OF, it)
        }
        if (MULTIPLE_ACTIVE_PATTERN.containsMatchIn(rawObject)) {
            requirements += requirement(BattleMoveRequirementKind.MULTIPLE_ACTIVE_POKEMON)
        }
        if (OPPOSITE_GENDER_PATTERN.containsMatchIn(rawObject)) {
            requirements += requirement(BattleMoveRequirementKind.OPPOSITE_GENDER)
        }
        return requirements.distinctBy { Triple(it.kind, it.acceptedValueIds, it.threshold) }
    }

    private fun requirement(
        kind: BattleMoveRequirementKind,
        acceptedValueIds: Set<String> = emptySet(),
        threshold: Double? = null,
    ) = BattleMoveRequirementView(kind, acceptedValueIds, threshold)

    private fun quotedValues(raw: String): Set<String> = STRING_VALUE_PATTERN.findAll(raw)
        .mapTo(linkedSetOf()) { it.groupValues[1].lowercase() }

    private fun userHpThreshold(rawObject: String): Double? {
        USER_HP_DIVISOR_PATTERN.find(rawObject)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it > 0.0 }
            ?.let { return 1.0 / it }
        USER_HP_CEIL_DIVISOR_PATTERN.find(rawObject)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it > 0.0 }
            ?.let { return 1.0 / it }
        val multiplied = USER_HP_MULTIPLIER_PATTERN.find(rawObject) ?: return null
        val numerator = multiplied.groupValues[1].toDoubleOrNull() ?: return null
        val denominator = multiplied.groupValues[2].toDoubleOrNull()?.takeIf { it > 0.0 } ?: return null
        return (numerator / denominator).takeIf { it in 0.0..1.0 }
    }

    private fun addSecondaryEffects(effects: MutableList<BattleMoveEffectView>, rawObject: String) {
        val properties = objectProperties(trimContainer(rawObject, '{', '}'))
        val probability = properties["chance"]?.trim()?.toDoubleOrNull()?.div(100.0) ?: 1.0
        addStatusAndBoostEffects(effects, properties, BattleMoveEffectTarget.SELECTED_TARGET, probability)
        properties["self"]?.takeIf { it.startsWith('{') }?.let {
            addStatusAndBoostEffects(
                effects,
                objectProperties(trimContainer(it, '{', '}')),
                BattleMoveEffectTarget.USER,
                probability,
            )
        }
    }

    private val FIRST_ACTIVE_TURN_ONLY_PATTERN = Regex("""\bactiveMoveActions\s*>\s*1\b""")
    private val WEATHER_REQUIREMENT_PATTERN = Regex("""field\.isWeather\(\[([^]]+)]\)""")
    private val TERRAIN_PRESENT_PATTERN = Regex("""!this\.field\.isTerrain\([\"']{2}\)""")
    private val USER_SLEEP_PATTERN = Regex("""return\s+source\.status\s*===\s*[\"']slp[\"']""")
    private val TARGET_SLEEP_PATTERN = Regex("""return\s+target\.status\s*===\s*[\"']slp[\"']""")
    private val USER_STATUS_PRESENT_PATTERN = Regex("""if\s*\(\s*!source\.status\s*\)\s*return\s+false""")
    private val TARGET_STATUS_ABSENT_PATTERN = Regex("""if\s*\(\s*target\.status\s*\|\|""")
    private val USER_TYPE_PATTERN = Regex("""if\s*\(\s*(?:source|pokemon)\.hasType\([\"']([^\"']+)[\"']\)\s*\)\s*(?:\{\s*)?return\s*;""")
    private val USER_HP_DIVISOR_PATTERN = Regex("""(?:source|pokemon)\.hp\s*<=\s*(?:source|pokemon)\.maxhp\s*/\s*(\d+)""")
    private val USER_HP_CEIL_DIVISOR_PATTERN = Regex("""(?:source|pokemon)\.hp\s*<=\s*Math\.ceil\((?:source|pokemon)\.maxhp\s*/\s*(\d+)\)""")
    private val USER_HP_MULTIPLIER_PATTERN = Regex("""(?:source|pokemon)\.hp\s*<=\s*(?:source|pokemon)\.maxhp\s*\*\s*(\d+)\s*/\s*(\d+)""")
    private val TARGET_HP_ABOVE_USER_PATTERN = Regex("""(?:source|pokemon)\.hp\s*<\s*target\.hp""")
    private val USER_HELD_BERRY_PATTERN = Regex("""source\.getItem\(\)\.isBerry""")
    private val TARGET_HELD_ITEM_PATTERN = Regex("""return\s+!!target\.item""")
    private val FAINTED_ALLY_PATTERN = Regex("""side\.pokemon\.filter\([\s\S]*?ally\.fainted\)\.length""")
    private val RESERVE_ALLY_PATTERN = Regex("""canSwitch\((?:source|pokemon)\.side\)""")
    private val PRIOR_DAMAGE_PATTERN = Regex("""getLastDamagedBy\(|(?:source|pokemon)\.volatiles\[[\"'](?:counter|mirrorcoat)[\"']]""")
    private val OTHER_MOVES_USED_PATTERN = Regex("""moveSlot\.used""")
    private val STOCKPILE_PATTERN = Regex("""return\s+!!source\.volatiles\[[\"']stockpile[\"']]""")
    private val TARGET_LAST_MOVE_PATTERN = Regex("""target\.lastMove""")
    private val TARGET_PENDING_ATTACK_PATTERN = Regex("""queue\.willMove\(target\)[\s\S]*?category\s*===\s*[\"']Status[\"']""")
    private val USER_SPECIES_PATTERN = Regex("""if\s*\(\s*source\.species\.(?:baseSpecies|name)\s*===\s*[\"']([^\"']+)[\"'](?:\s*\|\|[^)]*)?\s*\)\s*\{?\s*return\s*;""")
    private val MULTIPLE_ACTIVE_PATTERN = Regex("""activePerHalf\s*(?:>|===)\s*1""")
    private val OPPOSITE_GENDER_PATTERN = Regex("""target\.gender\s*===\s*[\"']M[\"'][\s\S]*source\.gender\s*===\s*[\"']F[\"']""")
    private val STRING_VALUE_PATTERN = Regex("""[\"']([^\"']+)[\"']""")
    private val MAXIMUM_LAYERS_PATTERN = Regex("""effectState\.layers\s*>=\s*(\d+)""")
    private val CHARGE_SKIP_WEATHER_PATTERN = Regex("""\[([^]]+)]\.includes\((?:attacker|source)\.effectiveWeather\(\)\)""")

    private fun addStatusAndBoostEffects(
        effects: MutableList<BattleMoveEffectView>,
        properties: Map<String, String>,
        target: BattleMoveEffectTarget,
        probability: Double,
    ) {
        stringValue(properties["status"])?.let {
            effects += effect(BattleMoveEffectKind.STATUS, target, probability, valueId = it)
        }
        stringValue(properties["volatileStatus"])?.let {
            effects += effect(BattleMoveEffectKind.VOLATILE_STATUS, target, probability, valueId = it)
        }
        statStages(properties["boosts"])?.takeIf { it.isNotEmpty() }?.let {
            effects += BattleMoveEffectView(
                kind = BattleMoveEffectKind.STAT_STAGE,
                target = target,
                probability = probability,
                statStages = it,
            )
        }
    }

    private fun effect(
        kind: BattleMoveEffectKind,
        target: BattleMoveEffectTarget,
        probability: Double = 1.0,
        valueId: String? = null,
        fraction: BattleFractionRange? = null,
        amount: BattleIntegerRange? = null,
    ) = BattleMoveEffectView(kind, target, probability, valueId, fraction, amount)

    private fun fraction(raw: String?): BattleFractionRange? {
        val values = raw?.takeIf { it.startsWith('[') }?.let { trimContainer(it, '[', ']') }
            ?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() } ?: return null
        if (values.size != 2 || values[1] == 0.0) return null
        val fraction = values[0] / values[1]
        return fraction.takeIf { it in 0.0..1.0 }?.let { BattleFractionRange(it, it) }
    }

    private fun integerRange(raw: String?): BattleIntegerRange? {
        val trimmed = raw?.trim() ?: return null
        trimmed.toIntOrNull()?.takeIf { it > 0 }?.let { return BattleIntegerRange(it, it) }
        val values = trimmed.takeIf { it.startsWith('[') }?.let { trimContainer(it, '[', ']') }
            ?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: return null
        return if (values.size == 2 && values[0] > 0 && values[0] <= values[1]) {
            BattleIntegerRange(values[0], values[1])
        } else null
    }

    private fun statStages(raw: String?): Map<String, Int>? {
        val body = raw?.takeIf { it.startsWith('{') }?.let { trimContainer(it, '{', '}') } ?: return null
        return objectProperties(body).mapNotNull { (id, value) ->
            value.trim().toIntOrNull()?.takeIf { it != 0 }?.let { publicStatId(id) to it }
        }.toMap(linkedMapOf())
    }

    private fun publicStatId(id: String): String = when (id) {
        "atk" -> "attack"
        "def" -> "defence"
        "spa" -> "special_attack"
        "spd" -> "special_defence"
        "spe" -> "speed"
        else -> id
    }

    private fun stringValue(raw: String?): String? {
        val value = raw?.trim() ?: return null
        if (value.length < 2 || value.first() !in STRING_QUOTES || value.last() != value.first()) return null
        return value.substring(1, value.length - 1)
    }

    private fun booleanLike(raw: String?): Boolean {
        val value = raw?.trim() ?: return false
        return value == "true" || stringValue(value) != null
    }

    private fun flagEnabled(raw: String?): Boolean = raw?.trim()?.toIntOrNull() == 1 || booleanLike(raw)

    private fun canonicalMoveId(value: String): String = value.lowercase().filter(Char::isLetterOrDigit)

    private fun trimContainer(raw: String, open: Char, close: Char): String {
        val value = raw.trim()
        return if (value.firstOrNull() == open && value.lastOrNull() == close) value.substring(1, value.length - 1) else value
    }

    private fun arrayObjects(body: String): List<String> = buildList {
        var index = 0
        while (index < body.length) {
            index = skipTrivia(body, index)
            if (index >= body.length) break
            if (body[index] == ',') {
                index++
                continue
            }
            if (body[index] != '{') {
                index++
                continue
            }
            val end = matchingDelimiter(body, index, '{', '}') ?: break
            add(body.substring(index, end + 1))
            index = end + 1
        }
    }

    private fun objectProperties(body: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var index = 0
        while (index < body.length) {
            index = skipTrivia(body, index)
            while (index < body.length && body[index] == ',') index = skipTrivia(body, index + 1)
            if (index >= body.length) break
            val keyToken = readKey(body, index) ?: run {
                index++
                continue
            }
            val key = keyToken.first
            var cursor = skipTrivia(body, keyToken.second)
            val hasColon = cursor < body.length && body[cursor] == ':'
            if (hasColon) cursor = skipTrivia(body, cursor + 1)
            val end = valueEnd(body, cursor)
            result[key] = body.substring(cursor, end).trim()
            index = if (end < body.length && body[end] == ',') end + 1 else end
        }
        return result
    }

    private fun readKey(source: String, start: Int): Pair<String, Int>? {
        if (start >= source.length) return null
        val first = source[start]
        if (first in STRING_QUOTES) {
            val end = stringEnd(source, start, first) ?: return null
            return source.substring(start + 1, end) to (end + 1)
        }
        if (!first.isLetterOrDigit() && first != '_' && first != '$') return null
        var end = start + 1
        while (end < source.length && (source[end].isLetterOrDigit() || source[end] == '_' || source[end] == '$')) end++
        return source.substring(start, end) to end
    }

    private fun valueEnd(source: String, start: Int): Int {
        var braces = 0
        var brackets = 0
        var parentheses = 0
        var index = start
        while (index < source.length) {
            when (val current = source[index]) {
                '\'', '"', '`' -> index = (stringEnd(source, index, current) ?: (source.length - 1))
                '/' -> {
                    val next = source.getOrNull(index + 1)
                    if (next == '/') index = lineCommentEnd(source, index + 2)
                    else if (next == '*') index = blockCommentEnd(source, index + 2)
                }
                '{' -> braces++
                '}' -> if (braces > 0) braces-- else return index
                '[' -> brackets++
                ']' -> if (brackets > 0) brackets--
                '(' -> parentheses++
                ')' -> if (parentheses > 0) parentheses--
                ',' -> if (braces == 0 && brackets == 0 && parentheses == 0) return index
            }
            index++
        }
        return source.length
    }

    private fun matchingDelimiter(source: String, start: Int, open: Char, close: Char): Int? {
        var depth = 0
        var index = start
        while (index < source.length) {
            when (val current = source[index]) {
                '\'', '"', '`' -> index = stringEnd(source, index, current) ?: return null
                '/' -> {
                    val next = source.getOrNull(index + 1)
                    if (next == '/') index = lineCommentEnd(source, index + 2)
                    else if (next == '*') index = blockCommentEnd(source, index + 2)
                }
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        return null
    }

    private fun skipTrivia(source: String, start: Int): Int {
        var index = start
        while (index < source.length) {
            when {
                source[index].isWhitespace() -> index++
                source[index] == '/' && source.getOrNull(index + 1) == '/' -> index = lineCommentEnd(source, index + 2) + 1
                source[index] == '/' && source.getOrNull(index + 1) == '*' -> index = blockCommentEnd(source, index + 2) + 1
                else -> return index
            }
        }
        return index
    }

    private fun stringEnd(source: String, start: Int, quote: Char): Int? {
        var index = start + 1
        while (index < source.length) {
            if (source[index] == '\\') index += 2
            else if (source[index] == quote) return index
            else index++
        }
        return null
    }

    private fun lineCommentEnd(source: String, start: Int): Int {
        val end = source.indexOf('\n', start)
        return if (end < 0) source.length - 1 else end
    }

    private fun blockCommentEnd(source: String, start: Int): Int {
        val end = source.indexOf("*/", start)
        return if (end < 0) source.length - 1 else end + 1
    }

    private const val MOVES_MARKER = "const Moves ="
    private const val STALLING_MOVE_FLAG = "stalling_move"
    private const val STALL_COUNTER_ADVANCE_FLAG = "stall_counter_advance"
    private val STALL_MOVE_CHECK_PATTERN = Regex("runEvent\\(\\s*['\"]StallMove['\"]")
    private val STALL_COUNTER_ADVANCE_PATTERN = Regex("addVolatile\\(\\s*['\"]stall['\"]")
    private val STRING_QUOTES = setOf('\'', '"', '`')
}
