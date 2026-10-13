package jbro.cobblemon.mcc.betterai.mechanics

/**
 * Damage modifiers read from an ability's Showdown script instead of a port: the plain kind that multiplies one damage
 * step when the move matches (`if (move.flags["contact"]) return this.chainModify(0.5);`). It covers abilities the
 * AI's own dex does not have, which a datapack or an addon registers at runtime (Mega Showdown's Aura Guard, Fire Mane).
 *
 * A handler is read only when every statement in it is understood: `this.debug(...)`, an `if` on the move's flags,
 * type or category returning `this.chainModify(x)`, and a final unconditional return of the same. Anything else leaves
 * that handler out rather than guessing at it.
 */
internal object DeclaredAbilityScripts {
    /** One damage step a handler multiplies, from the side of the ability's holder. */
    enum class Step(
        val callback: String,
        /** Whether the holder is the Pokemon being hit. */
        val defender: Boolean,
        /** The move category the step runs for, or null for every damaging move. */
        val category: String?,
        /** A defence stat raised by the factor divides the damage by it. */
        val divides: Boolean = false,
    ) {
        SOURCE_MODIFY_DAMAGE("onSourceModifyDamage", true, null),
        MODIFY_DAMAGE("onModifyDamage", false, null),
        BASE_POWER("onBasePower", false, null),
        SOURCE_BASE_POWER("onSourceBasePower", true, null),
        MODIFY_ATK("onModifyAtk", false, "Physical"),
        MODIFY_SPA("onModifySpA", false, "Special"),
        SOURCE_MODIFY_ATK("onSourceModifyAtk", true, "Physical"),
        SOURCE_MODIFY_SPA("onSourceModifySpA", true, "Special"),
        MODIFY_DEF("onModifyDef", true, "Physical", divides = true),
        MODIFY_SPD("onModifySpD", true, "Special", divides = true),
        ;

        companion object {
            val byCallback = entries.associateBy { it.callback }
        }
    }

    /** What a condition tests on the move: a flag, its type or its category. */
    data class Test(val subject: String, val value: String, val negated: Boolean) {
        fun matches(move: MoveFacts): Boolean = negated != when (subject) {
            "flag" -> value in move.flags
            "type" -> value.equals(move.type, ignoreCase = true)
            else -> value.equals(move.category, ignoreCase = true)
        }
    }

    /** The move as the handlers see it: Showdown's spelling of type and category, flags by id. */
    data class MoveFacts(val type: String, val category: String, val flags: Set<String>)

    /** `if (any of [all of the tests]) return factor`; no condition always returns. */
    data class Branch(val anyOf: List<List<Test>>?, val factor: Double) {
        fun matches(move: MoveFacts): Boolean = anyOf == null || anyOf.any { all -> all.all { it.matches(move) } }
    }

    /** A handler: its branches in order, the first that matches returning; none matching leaves the value alone. */
    data class Handler(val step: Step, val branches: List<Branch>) {
        fun factor(move: MoveFacts): Double = branches.firstOrNull { it.matches(move) }?.factor ?: 1.0
    }

    data class Ability(
        val id: String,
        val name: String,
        val num: Int?,
        val flags: Map<String, Int>,
        /** The handlers' priority and order fields, as the script declares them. */
        val priorities: Map<String, Double>,
        val handlers: List<Handler>,
    ) {
        /**
         * The factor this ability's holder puts on the finished damage of [move], as the attacker or the one hit. A
         * stat or power step moves damage by about the same factor, so all are folded into one.
         */
        fun damageFactor(defender: Boolean, move: MoveFacts): Double = handlers.fold(1.0) { product, handler ->
            val step = handler.step
            if (step.defender != defender || (step.category != null && !step.category.equals(move.category, ignoreCase = true))) product
            else handler.factor(move).let { if (step.divides) product / it else product * it }
        }
    }

    /**
     * Every ability in [source]: Showdown's `abilities.js` (an object of abilities), or a single ability's script as a
     * datapack holds it (`({ ... })`), which takes [singleId] as its id.
     */
    fun parse(source: String, singleId: String? = null): Map<String, Ability> {
        val text = stripComments(source)
        if (singleId != null) {
            val open = text.indexOf('{').takeIf { it >= 0 } ?: return emptyMap()
            val close = matching(text, open) ?: return emptyMap()
            return ability(singleId, text.substring(open + 1, close))?.let { mapOf(it.id to it) } ?: emptyMap()
        }
        val start = ABILITIES_TABLE.find(text)?.range?.last ?: return emptyMap()
        val end = matching(text, start) ?: return emptyMap()
        return members(text.substring(start + 1, end)).mapNotNull { member ->
            if (member.params != null || !member.value.startsWith("{")) return@mapNotNull null
            ability(member.key, member.value.substring(1, member.value.length - 1))
        }.associateBy { it.id }
    }

    private fun ability(id: String, body: String): Ability? {
        val members = members(body)
        val handlers = members.mapNotNull { member ->
            val step = Step.byCallback[member.key] ?: return@mapNotNull null
            val params = member.params ?: return@mapNotNull null
            val move = params.split(',').map { it.trim() }.lastOrNull()?.takeIf { IDENTIFIER.matches(it) } ?: return@mapNotNull null
            branches(member.value.substring(1, member.value.length - 1), move)?.let { Handler(step, it) }
        }
        val values = members.filter { it.params == null }.associate { it.key to it.value }
        val name = values["name"]?.let { STRING.matchEntire(it)?.groupValues?.get(2) } ?: id
        val flags = values["flags"]?.let { flagsText ->
            FLAG_ENTRY.findAll(flagsText).associate { it.groupValues[1] to it.groupValues[2].toInt() }
        }.orEmpty()
        val priorities = values.filterKeys { it.endsWith("Priority") || it.endsWith("Order") || it.endsWith("SubOrder") }
            .mapNotNull { (key, value) -> value.toDoubleOrNull()?.let { key to it } }.toMap()
        return Ability(toId(id), name, values["num"]?.toDoubleOrNull()?.toInt(), flags, priorities, handlers)
    }

    /** The handler body as branches, or null when a statement is not one this reader understands. */
    private fun branches(body: String, move: String): List<Branch>? {
        val out = mutableListOf<Branch>()
        var rest = body.trim()
        while (rest.isNotEmpty()) {
            val debug = DEBUG.find(rest)
            if (debug != null) {
                val close = matching(rest, debug.range.last) ?: return null
                rest = rest.substring(close + 1).trimStart().removePrefix(";").trim()
                continue
            }
            if (rest.startsWith("if") && rest.drop(2).trimStart().startsWith("(")) {
                val open = rest.indexOf('(')
                val close = matching(rest, open) ?: return null
                val anyOf = condition(rest.substring(open + 1, close), move) ?: return null
                var after = rest.substring(close + 1).trim()
                val factor: Double
                if (after.startsWith("{")) {
                    val end = matching(after, 0) ?: return null
                    factor = blockReturn(after.substring(1, end)) ?: return null
                    after = after.substring(end + 1)
                } else {
                    val ret = RETURN.find(after) ?: return null
                    factor = factorOf(ret) ?: return null
                    after = after.substring(ret.range.last + 1)
                }
                out += Branch(anyOf, factor)
                rest = after.trim()
                continue
            }
            val ret = RETURN.find(rest) ?: return null
            out += Branch(null, factorOf(ret) ?: return null)
            if (rest.substring(ret.range.last + 1).isNotBlank()) return null
            break
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** A block of `this.debug(...)` calls ending in `return this.chainModify(x)`. */
    private fun blockReturn(block: String): Double? {
        val branches = branches(block, "\u0000") ?: return null
        return branches.singleOrNull()?.takeIf { it.anyOf == null }?.factor
    }

    private fun condition(text: String, move: String): List<List<Test>>? {
        if ('(' in text || ')' in text) return null
        return text.split("||").map { any ->
            any.split("&&").map { atom -> test(atom.trim(), move) ?: return null }
        }
    }

    private fun test(atom: String, move: String): Test? {
        val m = Regex.escape(move)
        Regex("""^(!?)$m\.flags(?:\[\s*["'](\w+)["']\s*]|\.(\w+))$""").matchEntire(atom)?.let {
            return Test("flag", it.groupValues[2].ifEmpty { it.groupValues[3] }, it.groupValues[1] == "!")
        }
        Regex("""^$m\.(type|category)\s*(===|==|!==|!=)\s*["'](\w+)["']$""").matchEntire(atom)?.let {
            return Test(it.groupValues[1], it.groupValues[3], it.groupValues[2].startsWith("!"))
        }
        return null
    }

    private fun factorOf(match: MatchResult): Double? {
        val (single, numerator, denominator) = match.destructured
        return if (single.isNotEmpty()) single.toDoubleOrNull()
        else numerator.toDoubleOrNull()?.let { n -> denominator.toDoubleOrNull()?.takeIf { it != 0.0 }?.let { n / it } }
    }

    private class Member(val key: String, val params: String?, val value: String)

    /** The top-level members of an object body: `key: value` and `key(params) { body }`. */
    private fun members(body: String): List<Member> {
        val out = mutableListOf<Member>()
        var i = 0
        while (i < body.length) {
            val key = KEY.find(body, i)?.takeIf { it.range.first == skipSpace(body, i) } ?: break
            val name = key.groupValues[1].ifEmpty { key.groupValues[2] }
            var j = key.range.last + 1
            if (key.value.trimEnd().endsWith("(")) {
                val open = key.range.last
                val close = matching(body, open) ?: break
                val brace = skipSpace(body, close + 1)
                if (brace >= body.length || body[brace] != '{') break
                val end = matching(body, brace) ?: break
                out += Member(name, body.substring(open + 1, close), body.substring(brace, end + 1))
                j = end + 1
            } else {
                val start = skipSpace(body, j)
                val end = valueEnd(body, start)
                out += Member(name, null, body.substring(start, end).trim())
                j = end
            }
            j = skipSpace(body, j)
            if (j < body.length && body[j] == ',') j++
            i = j
        }
        return out
    }

    private fun skipSpace(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    /** Where a member's value ends: the next comma outside brackets and strings, or the end. */
    private fun valueEnd(text: String, from: Int): Int {
        var depth = 0
        var i = from
        while (i < text.length) {
            when (val c = text[i]) {
                '"', '\'', '`' -> i = stringEnd(text, i)
                '{', '[', '(' -> depth++
                '}', ']', ')' -> depth--
                ',' -> if (depth == 0) return i
            }
            if (depth < 0) return i
            i++
        }
        return text.length
    }

    /** The index of the bracket closing the one at [open], skipping strings. */
    private fun matching(text: String, open: Int): Int? {
        val opener = text[open]
        val closer = when (opener) { '{' -> '}'; '(' -> ')'; '[' -> ']'; else -> return null }
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '"', '\'', '`' -> i = stringEnd(text, i)
                opener -> depth++
                closer -> if (--depth == 0) return i
            }
            i++
        }
        return null
    }

    private fun stringEnd(text: String, open: Int): Int {
        val quote = text[open]
        var i = open + 1
        while (i < text.length && text[i] != quote) i += if (text[i] == '\\') 2 else 1
        return i
    }

    private fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                c == '"' || c == '\'' || c == '`' -> {
                    val end = stringEnd(source, i).coerceAtMost(source.length - 1)
                    out.append(source, i, end + 1)
                    i = end + 1
                }
                source.startsWith("//", i) -> i = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                source.startsWith("/*", i) -> i = source.indexOf("*/", i + 2).let { if (it < 0) source.length else it + 2 }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    fun toId(value: String): String = value.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

    private val ABILITIES_TABLE = Regex("""\bAbilities\s*=\s*\{""")
    private val KEY = Regex("""(?:"([^"]+)"|'?([A-Za-z_$][\w$]*)'?)\s*(:|\()""")
    private val IDENTIFIER = Regex("""[A-Za-z_$][\w$]*""")
    private val STRING = Regex("""^(["'])(.*)\1$""")
    private val FLAG_ENTRY = Regex("""(\w+)\s*:\s*(\d+)""")
    private val DEBUG = Regex("""^this\.debug\s*\(""")
    private val RETURN = Regex(
        """^return\s+this\.chainModify\(\s*(?:(\d+(?:\.\d+)?)|\[\s*(\d+(?:\.\d+)?)\s*,\s*(\d+(?:\.\d+)?)\s*])\s*\)\s*;?""",
    )
}
