package jbro.cobblemon.mcc.internal.ai

/**
 * What an item or an ability does, read from its code in the Showdown bundle Cobblemon ships (`data/items.js`,
 * `data/abilities.js`), so no item or ability is named where the brain asks (2026-10-20). Each reading is null or false
 * when the code does not show the shape, or the bundle cannot be read.
 */
object BattleShowdownHeldEffects {
    /** A Choice item's boost: the stat (`atk`, `spa`, `spe`), its multiplier, and whether a Dynamaxed holder loses it. */
    data class ChoiceBoost(val stat: String, val multiplier: Double, val offWhileDynamaxed: Boolean)

    /** A form-changing ability's two forms by suffix (`""` for the base form): the one for status moves, the one for attacks. */
    data class StanceForms(val statusForm: String, val attackForm: String, val statusFormMove: String?)

    /** An ability that takes the first hit's damage away and changes form (`busted`), at a share of maximum HP. */
    data class FirstHitShield(val brokenForm: String, val chip: Double)

    /** A status condition's end-of-turn damage as a share of maximum HP; growing each turn (Toxic's stage) or flat. */
    data class StatusResidual(val share: Double, val grows: Boolean)

    private val conditions: Map<String, Map<String, String>> by lazy(LazyThreadSafetyMode.PUBLICATION) { load(CONDITIONS_FILE, CONDITIONS_MARKER) }
    private val items: Map<String, Map<String, String>> by lazy(LazyThreadSafetyMode.PUBLICATION) { load(ITEMS_FILE, ITEMS_MARKER) }
    private val abilities: Map<String, Map<String, String>> by lazy(LazyThreadSafetyMode.PUBLICATION) { load(ABILITIES_FILE, ABILITIES_MARKER) }

    /** The item locks its holder into its first move (`isChoice`). */
    fun isChoice(itemId: String?): Boolean = itemId?.let { items[canonical(it)] }?.get("isChoice")?.trim() == "true"

    fun choiceBoost(itemId: String?): ChoiceBoost? {
        val item = itemId?.let { items[canonical(it)] } ?: return null
        if (item["isChoice"]?.trim() != "true") return null
        return STAT_HOOKS.firstNotNullOfOrNull { (hook, stat) ->
            val body = item[hook] ?: return@firstNotNullOfOrNull null
            val multiplier = CHAIN_MODIFY.find(body)?.groupValues?.get(1)?.toDoubleOrNull() ?: return@firstNotNullOfOrNull null
            ChoiceBoost(stat, multiplier, DYNAMAX_GUARD.containsMatchIn(body))
        }
    }

    /** The item strips the secondary effects of moves that hit its holder (Covert Cloak). */
    fun shieldsSecondaries(itemId: String?): Boolean = itemId?.let { items[canonical(it)] }?.containsKey("onModifySecondaries") == true

    fun stanceForms(abilityId: String?): StanceForms? {
        // The form picked as each move is chosen, by whether it is a status move (Stance Change's onModifyMove).
        val code = abilityId?.let { abilities[canonical(it)] }?.get("onModifyMove") ?: return null
        if (!code.contains("formeChange(") || !STATUS_CATEGORY.containsMatchIn(code)) return null
        val (statusForme, attackForme) = FORME_CHOICE.find(code)?.destructured ?: return null
        return StanceForms(suffix(statusForme), suffix(attackForme), FORME_MOVE.find(code)?.groupValues?.get(1)?.let(::canonical))
    }

    fun firstHitShield(abilityId: String?): FirstHitShield? {
        val ability = abilityId?.let { abilities[canonical(it)] } ?: return null
        val onDamage = ability["onDamage"] ?: return null
        if (!ZERO_RETURN.containsMatchIn(onDamage)) return null
        val code = ability.values.joinToString("\n")
        // The form it changes to once the hit is taken: the shortest form name the code gives (Mimikyu-Busted, not its
        // Totem's).
        if (!code.contains("formeChange(")) return null
        val broken = FORME_NAME.findAll(code).map { suffix(it.groupValues[1]) }.filter { it.isNotEmpty() }.minByOrNull { it.length } ?: return null
        val chip = MAX_HP_SHARE.find(code)?.groupValues?.get(1)?.toDoubleOrNull()?.let { 1.0 / it } ?: return null
        return FirstHitShield(broken, chip)
    }

    /** What a status condition takes at each end of turn (`data/conditions.js` onResidual), or null when it takes nothing. */
    fun statusResidual(statusId: String?): StatusResidual? {
        val body = statusId?.let { conditions[canonical(it)] }?.get("onResidual") ?: return null
        if (!body.contains("this.damage(")) return null
        val share = MAX_HP_SHARE.find(body)?.groupValues?.get(1)?.toDoubleOrNull()?.let { 1.0 / it } ?: return null
        return StatusResidual(share, body.contains("stage++"))
    }

    /** The ability keeps its holder in against a forced switch (Suction Cups, Guard Dog): it answers onDragOut. */
    fun resistsForcedSwitch(abilityId: String?): Boolean = abilityId?.let { abilities[canonical(it)] }?.containsKey("onDragOut") == true

    /** The ability takes no damage but from moves (Magic Guard): its onDamage refuses every other effect. */
    fun blocksIndirectDamage(abilityId: String?): Boolean =
        abilityId?.let { abilities[canonical(it)] }?.get("onDamage")?.let { MOVE_ONLY_DAMAGE.containsMatchIn(it) && it.contains("return false") } == true

    /** The share of maximum HP the ability heals in place of [statusId]'s damage (Poison Heal: an eighth for poison), or null. */
    fun statusHealShare(abilityId: String?, statusId: String?): Double? {
        val status = statusId?.let(::canonical) ?: return null
        val body = abilityId?.let { abilities[canonical(it)] }?.get("onDamage") ?: return null
        if (!Regex("""effect\.id\s*===\s*["']$status["']""").containsMatchIn(body) || !body.contains("this.heal(")) return null
        return MAX_HP_SHARE.find(body)?.groupValues?.get(1)?.toDoubleOrNull()?.let { 1.0 / it }
    }

    private fun load(file: String, marker: String): Map<String, Map<String, String>> = try {
        BattleShowdownMoveEffects.showdownSource(file)?.let { BattleDeclarativeMoveEffects.entries(it, marker) }.orEmpty()
    } catch (_: RuntimeException) {
        emptyMap()
    } catch (_: LinkageError) {
        emptyMap()
    }

    private fun suffix(forme: String): String = forme.substringAfter('-', "").lowercase().filter(Char::isLetterOrDigit)
    private fun canonical(id: String): String = id.substringAfter(':').lowercase().filter(Char::isLetterOrDigit)

    private val STAT_HOOKS = listOf("onModifyAtk" to "atk", "onModifySpA" to "spa", "onModifySpe" to "spe")
    private val CHAIN_MODIFY = Regex("""chainModify\(\s*([\d.]+)\s*\)""")
    private val DYNAMAX_GUARD = Regex("""volatiles\[\s*["']dynamax["']\s*]""")
    private val STATUS_CATEGORY = Regex("""category\s*===\s*["']Status["']""")
    private val FORME_CHOICE = Regex("""\?\s*["']([^"']+)["']\s*:\s*["']([^"']+)["']""")
    private val FORME_MOVE = Regex("""move\.id\s*===\s*["']([^"']+)["']\s*\?""")
    private val FORME_NAME = Regex("""["']([A-Z][A-Za-z0-9]*-[A-Za-z0-9-]+)["']""")
    private val ZERO_RETURN = Regex("""return\s+0\s*;""")
    private val MAX_HP_SHARE = Regex("""(?i)maxhp\s*/\s*(\d+)""")
    private val MOVE_ONLY_DAMAGE = Regex("""effect\.effectType\s*!==\s*["']Move["']""")
    private const val CONDITIONS_FILE = "data/conditions.js"
    private const val CONDITIONS_MARKER = "const Conditions ="
    private const val ITEMS_FILE = "data/items.js"
    private const val ITEMS_MARKER = "const Items ="
    private const val ABILITIES_FILE = "data/abilities.js"
    private const val ABILITIES_MARKER = "const Abilities ="
}
