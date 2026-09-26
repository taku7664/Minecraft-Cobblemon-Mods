package jbro.cobblemon.morebattlecontent.api.ai

/**
 * Independent per-stat bounds from the visibly revealed species/form and level.
 * These are not one jointly achievable build and accept no opponent build inputs.
 */
object BattlePublicStatRanges {
    /** A local AI hypothesis: IV/EV are fixed, while an unobserved nature remains a range. */
    fun fromAssumedSpread(
        level: Int,
        baseStats: Map<String, Int>,
        ivs: Map<String, Int>,
        evs: Map<String, Int>,
    ): BattleCombatStatRangesView {
        val stats = setOf("hp", "atk", "def", "spa", "spd", "spe")
        require(level in 1..100 && baseStats.keys == stats && baseStats.values.all { it > 0 })
        require(ivs.keys == stats && ivs.values.all { it in 0..31 })
        require(evs.keys == stats && evs.values.all { it in 0..252 } && evs.values.sum() <= 510)
        fun nonHp(id: String): BattleIntegerRange {
            val neutral = nonHpStat(baseStats.getValue(id), level, ivs.getValue(id), evs.getValue(id))
            return BattleIntegerRange(neutral * 90 / 100, neutral * 110 / 100)
        }
        val hp = if (baseStats.getValue("hp") == SHEDINJA_BASE_HP) 1 else
            hpStat(baseStats.getValue("hp"), level, ivs.getValue("hp"), evs.getValue("hp"))
        return BattleCombatStatRangesView(
            maxHp = BattleIntegerRange(hp, hp),
            attack = nonHp("atk"),
            defence = nonHp("def"),
            specialAttack = nonHp("spa"),
            specialDefence = nonHp("spd"),
            speed = nonHp("spe"),
            knowledge = BattleCombatStatKnowledge.LOCAL_OPPONENT_ESTIMATE,
        )
    }

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
        require(listOf(hp, attack, defence, specialAttack, specialDefence, speed).all { it > 0 })
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

    private fun nonHpRange(base: Int, level: Int): BattleIntegerRange {
        val minimumNeutral = nonHpStat(base, level, MINIMUM_IV, MINIMUM_EV)
        val maximumNeutral = nonHpStat(base, level, MAXIMUM_IV, MAXIMUM_EV)
        return BattleIntegerRange(minimumNeutral * 90 / 100, maximumNeutral * 110 / 100)
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
