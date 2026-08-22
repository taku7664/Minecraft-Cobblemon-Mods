package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.pokemon.stats.Stat
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.pokemon.FormData
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.Species
import jbro.cobblemon.morebattlecontent.api.ai.BattleCombatStatRangesView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonFormStateView

/** Publicly describes battle forms; exact spreads are used only for the local trainer's Pokemon. */
internal object Cobblemon173KnownFormStates {
    fun exactOwn(pokemon: Pokemon): Map<String, BattlePokemonFormStateView> = pokemon.species.battleForms()
        .associate { form ->
            form.name to BattlePokemonFormStateView(
                formId = form.name,
                knownTypeIds = form.types.mapTo(linkedSetOf()) { it.name },
                combatStats = exactStats(pokemon, form),
            )
        }

    fun publicRanges(level: Int, species: Species): Map<String, BattlePokemonFormStateView> = species.battleForms()
        .mapNotNull { form ->
            Cobblemon173PublicStatHypothesis.fromForm(level, form)?.let { stats ->
                form.name to BattlePokemonFormStateView(
                    formId = form.name,
                    knownTypeIds = form.types.mapTo(linkedSetOf()) { it.name },
                    combatStats = stats,
                )
            }
        }.toMap(linkedMapOf())

    private fun Species.battleForms(): List<FormData> = (listOf(standardForm) + forms)
        .distinctBy { it.name.lowercase() }

    private fun exactStats(pokemon: Pokemon, form: FormData): BattleCombatStatRangesView =
        BattleCombatStatRangesView.exact(
            maxHp = hpStat(pokemon, form, Stats.HP),
            attack = nonHpStat(pokemon, form, Stats.ATTACK),
            defence = nonHpStat(pokemon, form, Stats.DEFENCE),
            specialAttack = nonHpStat(pokemon, form, Stats.SPECIAL_ATTACK),
            specialDefence = nonHpStat(pokemon, form, Stats.SPECIAL_DEFENCE),
            speed = nonHpStat(pokemon, form, Stats.SPEED),
        )

    private fun hpStat(pokemon: Pokemon, form: FormData, stat: Stat): Int {
        val base = requireNotNull(form.baseStats[stat])
        if (base == SHEDINJA_BASE_HP) return 1
        return ((2 * base + pokemon.ivs.getEffectiveBattleIV(stat) + pokemon.evs.getOrDefault(stat) / 4 + 100) *
            pokemon.level) / 100 + 10
    }

    private fun nonHpStat(pokemon: Pokemon, form: FormData, stat: Stat): Int {
        val base = requireNotNull(form.baseStats[stat])
        val neutral = ((2 * base + pokemon.ivs.getEffectiveBattleIV(stat) + pokemon.evs.getOrDefault(stat) / 4) *
            pokemon.level) / 100 + 5
        return pokemon.effectiveNature.modifyStat(stat, neutral)
    }

    private const val SHEDINJA_BASE_HP = 1
}
