package jbro.cobblemon.customspecies.compat

import com.cobblemon.mod.common.api.Priority
import com.cobblemon.mod.common.api.abilities.AbilityPool
import com.cobblemon.mod.common.api.abilities.PotentialAbility
import com.cobblemon.mod.common.api.moves.Moves
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.moves.Learnset
import com.cobblemon.mod.common.api.pokemon.stats.Stat
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.pokemon.FormData
import com.cobblemon.mod.common.pokemon.Species
import com.google.gson.JsonArray
import jbro.cobblemon.customspecies.config.FormSelector
import jbro.cobblemon.customspecies.service.SpeciesCatalog
import jbro.cobblemon.customspecies.service.SpeciesTargetKey
import jbro.cobblemon.customspecies.service.SpeciesTargetState
import net.minecraft.resources.ResourceLocation
import java.lang.reflect.Field

/** Isolates the only Cobblemon-internal compatibility access used by this addon. */
class CobblemonSpeciesCatalog : SpeciesCatalog {
    private data class RuntimeTarget(val species: Species, val form: FormData?)

    private val targets: Map<SpeciesTargetKey, RuntimeTarget> = buildMap {
        PokemonSpecies.species.forEach { species ->
            val speciesId = species.resourceIdentifier.toString()
            put(SpeciesTargetKey(speciesId, "base"), RuntimeTarget(species, null))
            species.forms.forEach { form ->
                put(SpeciesTargetKey(speciesId, form.formOnlyShowdownId()), RuntimeTarget(species, form))
            }
        }
    }
    private val abilitiesBySignature = mutableMapOf<String, PotentialAbility>()

    init {
        require(FORM_MOVES_FIELD.type == Learnset::class.java) { "Cobblemon FormData._moves changed type" }
        require(FORM_ABILITIES_FIELD.type == AbilityPool::class.java) { "Cobblemon FormData._abilities changed type" }
        require(Map::class.java.isAssignableFrom(FORM_STATS_FIELD.type)) { "Cobblemon FormData._baseStats changed type" }
    }

    override fun resolve(species: String, selector: FormSelector): List<SpeciesTargetKey> {
        if (ResourceLocation.tryParse(species) == null) return emptyList()
        return when (selector) {
            FormSelector.Base -> listOfNotNull(targets.keys.firstOrNull { it.species == species && it.form == "base" })
            FormSelector.All -> targets.keys.filter { it.species == species }
            is FormSelector.Named -> listOfNotNull(targets.keys.firstOrNull { it.species == species && it.form == selector.id })
        }
    }

    override fun read(key: SpeciesTargetKey): SpeciesTargetState {
        val target = targets.getValue(key)
        val stats = if (target.form == null) target.species.baseStats else target.form.baseStats
        val moves = if (target.form == null) target.species.moves else target.form.moves
        val abilities = if (target.form == null) target.species.abilities else target.form.abilities
        return SpeciesTargetState(
            baseStats = STAT_BY_NAME.mapValuesTo(linkedMapOf<String, Int>()) { (_, stat) -> requireNotNull(stats[stat]) },
            abilities = abilities.mapTo(linkedSetOf()) { abilitySignature(it) },
            moves = encodeMoves(moves)
        )
    }

    override fun write(key: SpeciesTargetKey, state: SpeciesTargetState) {
        val target = targets.getValue(key)
        val stats = STAT_BY_NAME.entries.associateTo(linkedMapOf<Stat, Int>()) { (name, stat) ->
            stat to requireNotNull(state.baseStats[name]) { "Missing base stat $name for $key" }
        }
        val moves = decodeMoves(state.moves)
        val abilities = AbilityPool().also { pool ->
            state.abilities.forEach { signature ->
                val ability = requireNotNull(abilitiesBySignature[signature]) { "Missing parsed ability $signature" }
                pool.add(ability.priority, ability)
            }
        }
        require(!abilities.isEmpty()) { "Ability pool cannot be empty for $key" }

        if (target.form == null) {
            target.species.baseStats.clear()
            target.species.baseStats.putAll(stats)
            replaceLearnset(target.species.moves, moves)
            replaceAbilityPool(target.species.abilities, abilities)
        } else {
            // Forms may inherit all three values. Assigning independent copies prevents a form edit
            // (for example Rotom-Wash) from mutating the base form or another regional form.
            FORM_STATS_FIELD.set(target.form, stats)
            FORM_MOVES_FIELD.set(target.form, moves)
            FORM_ABILITIES_FIELD.set(target.form, abilities)
        }
    }

    override fun validateMove(entry: String): Boolean = runCatching { parseMove(entry) }.isSuccess

    override fun canonicalMove(entry: String): String = parseMove(entry).first

    override fun validateAbility(entry: String): Boolean = runCatching { parseAbility(entry) }.isSuccess

    override fun canonicalAbility(entry: String): String = abilitySignature(parseAbility(entry))

    private fun parseAbility(entry: String): PotentialAbility {
        val json = JsonArray().also { it.add(entry) }
        val pool = PokemonSpecies.gson.fromJson(json, AbilityPool::class.java)
        val values = pool.toList()
        require(values.size == 1) { "Ability token must resolve to exactly one ability: $entry" }
        return values.single()
    }

    private fun abilitySignature(ability: PotentialAbility): String {
        val signature = "${ability.type.javaClass.name}|${ability.priority.name}|${ability.template.name}"
        abilitiesBySignature[signature] = ability
        return signature
    }

    private fun parseMove(entry: String): Pair<String, com.cobblemon.mod.common.api.moves.MoveTemplate> {
        val separator = entry.indexOf(':')
        require(separator > 0 && separator < entry.lastIndex) { "Move entry must be method:move: $entry" }
        val method = entry.substring(0, separator).lowercase()
        val moveName = entry.substring(separator + 1).lowercase()
        val canonicalMethod = method.toIntOrNull()?.let {
            require(it in 0..100) { "Level-up move level must be between 0 and 100: $entry" }
            it.toString()
        } ?: method.also { require(it in MOVE_METHODS) { "Unknown move method: $method" } }
        val move = requireNotNull(Moves.getByName(moveName)) { "Unknown move: $moveName" }
        return "$canonicalMethod:$moveName" to move
    }

    private fun encodeMoves(learnset: Learnset): MutableSet<String> = linkedSetOf<String>().also { result ->
        learnset.levelUpMoves.toSortedMap().forEach { (level, moves) -> moves.forEach { result += "$level:${it.name}" } }
        learnset.eggMoves.forEach { result += "egg:${it.name}" }
        learnset.tutorMoves.forEach { result += "tutor:${it.name}" }
        learnset.legacyMoves.forEach { result += "legacy:${it.name}" }
        learnset.specialMoves.forEach { result += "special:${it.name}" }
        learnset.tmMoves.forEach { result += "tm:${it.name}" }
        learnset.evolutionMoves.forEach { result += "evolution:${it.name}" }
        learnset.formChangeMoves.forEach { result += "form_change:${it.name}" }
    }

    private fun decodeMoves(entries: Set<String>): Learnset = Learnset().also { learnset ->
        entries.forEach { entry ->
            val (canonical, move) = parseMove(entry)
            when (val method = canonical.substringBefore(':')) {
                "egg" -> learnset.eggMoves.add(move)
                "tutor" -> learnset.tutorMoves.add(move)
                "legacy" -> learnset.legacyMoves.add(move)
                "special" -> learnset.specialMoves.add(move)
                "tm" -> learnset.tmMoves.add(move)
                "evolution" -> learnset.evolutionMoves.add(move)
                "form_change" -> learnset.formChangeMoves.add(move)
                else -> learnset.levelUpMoves.getOrPut(method.toInt()) { mutableListOf() }.add(move)
            }
        }
    }

    private fun replaceLearnset(target: Learnset, source: Learnset) {
        target.levelUpMoves.clear()
        source.levelUpMoves.forEach { (level, moves) -> target.levelUpMoves[level] = moves.toMutableList() }
        target.eggMoves.clear()
        target.eggMoves.addAll(source.eggMoves)
        target.tutorMoves.clear()
        target.tutorMoves.addAll(source.tutorMoves)
        target.legacyMoves.clear()
        target.legacyMoves.addAll(source.legacyMoves)
        target.specialMoves.clear()
        target.specialMoves.addAll(source.specialMoves)
        target.tmMoves.clear()
        target.tmMoves.addAll(source.tmMoves)
        target.evolutionMoves.clear()
        target.evolutionMoves.addAll(source.evolutionMoves)
        target.formChangeMoves.clear()
        target.formChangeMoves.addAll(source.formChangeMoves)
    }

    private fun replaceAbilityPool(target: AbilityPool, source: AbilityPool) {
        target.clear()
        source.forEach { target.add(it.priority, it) }
    }

    private companion object {
        val MOVE_METHODS = setOf("egg", "tutor", "legacy", "special", "tm", "evolution", "form_change")
        val STAT_BY_NAME = linkedMapOf(
            "hp" to Stats.HP,
            "attack" to Stats.ATTACK,
            "defence" to Stats.DEFENCE,
            "special_attack" to Stats.SPECIAL_ATTACK,
            "special_defence" to Stats.SPECIAL_DEFENCE,
            "speed" to Stats.SPEED
        )
        val FORM_STATS_FIELD: Field = accessibleFormField("_baseStats")
        val FORM_MOVES_FIELD: Field = accessibleFormField("_moves")
        val FORM_ABILITIES_FIELD: Field = accessibleFormField("_abilities")

        fun accessibleFormField(name: String): Field = FormData::class.java.getDeclaredField(name).also {
            require(it.trySetAccessible()) { "Cobblemon FormData.$name is not accessible" }
        }
    }
}
