package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.pokemon.FormData
import jbro.cobblemon.morebattlecontent.api.ai.BattleCombatStatKnowledge
import jbro.cobblemon.morebattlecontent.api.ai.BattleCombatStatRangesView
import jbro.cobblemon.morebattlecontent.api.ai.BattleIntegerRange

/** Builds combat-stat facts without accepting opponent IVs, EVs, nature, item, or ability. */
internal object Cobblemon173PublicStatHypothesis {
    fun fromForm(level: Int, form: FormData): BattleCombatStatRangesView? = runCatching {
        fromBaseStats(
            level = level,
            hp = requireNotNull(form.baseStats[Stats.HP]),
            attack = requireNotNull(form.baseStats[Stats.ATTACK]),
            defence = requireNotNull(form.baseStats[Stats.DEFENCE]),
            specialAttack = requireNotNull(form.baseStats[Stats.SPECIAL_ATTACK]),
            specialDefence = requireNotNull(form.baseStats[Stats.SPECIAL_DEFENCE]),
            speed = requireNotNull(form.baseStats[Stats.SPEED]),
        )
    }.getOrNull()

    fun fromBaseStats(
        level: Int,
        hp: Int,
        attack: Int,
        defence: Int,
        specialAttack: Int,
        specialDefence: Int,
        speed: Int,
    ): BattleCombatStatRangesView {
        require(level in 1..100)
        val baseStats = listOf(hp, attack, defence, specialAttack, specialDefence, speed)
        require(baseStats.all { it > 0 })
        return BattleCombatStatRangesView(
            maxHp = if (hp == SHEDINJA_BASE_HP) BattleIntegerRange(1, 1) else BattleIntegerRange(
                hpStat(hp, level, MINIMUM_IV, MINIMUM_EV),
                hpStat(hp, level, MAXIMUM_IV, MAXIMUM_EV),
            ),
            attack = nonHpRange(attack, level),
            defence = nonHpRange(defence, level),
            specialAttack = nonHpRange(specialAttack, level),
            specialDefence = nonHpRange(specialDefence, level),
            speed = nonHpRange(speed, level),
            knowledge = BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
        )
    }

    fun exactOwn(
        maxHp: Int,
        attack: Int,
        defence: Int,
        specialAttack: Int,
        specialDefence: Int,
        speed: Int,
    ): BattleCombatStatRangesView = BattleCombatStatRangesView.exact(
        maxHp, attack, defence, specialAttack, specialDefence, speed,
    )

    private fun nonHpRange(base: Int, level: Int): BattleIntegerRange {
        val minimumNeutral = nonHpStat(base, level, MINIMUM_IV, MINIMUM_EV)
        val maximumNeutral = nonHpStat(base, level, MAXIMUM_IV, MAXIMUM_EV)
        return BattleIntegerRange(
            minimum = minimumNeutral * 90 / 100,
            maximum = maximumNeutral * 110 / 100,
        )
    }

    private fun hpStat(base: Int, level: Int, iv: Int, ev: Int): Int =
        ((2 * base + iv + ev / 4 + 100) * level) / 100 + 10

    private fun nonHpStat(base: Int, level: Int, iv: Int, ev: Int): Int =
        ((2 * base + iv + ev / 4) * level) / 100 + 5

    private const val MINIMUM_IV = 0
    private const val MAXIMUM_IV = 31
    private const val MINIMUM_EV = 0
    private const val MAXIMUM_EV = 252
    private const val SHEDINJA_BASE_HP = 1
}
