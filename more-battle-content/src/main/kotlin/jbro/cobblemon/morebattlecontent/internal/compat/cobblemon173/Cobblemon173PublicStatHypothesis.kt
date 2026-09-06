package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.pokemon.FormData
import jbro.cobblemon.morebattlecontent.api.ai.BattleCombatStatRangesView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicStatRanges

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
    ): BattleCombatStatRangesView = BattlePublicStatRanges.fromBaseStats(
        level, hp, attack, defence, specialAttack, specialDefence, speed,
    )

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

}
