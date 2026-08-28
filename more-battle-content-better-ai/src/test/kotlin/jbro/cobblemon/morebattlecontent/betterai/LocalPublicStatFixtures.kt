package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleCombatStatKnowledge
import jbro.cobblemon.morebattlecontent.api.ai.BattleCombatStatRangesView
import jbro.cobblemon.morebattlecontent.api.ai.BattleIntegerRange

/**
 * An opponent spread that is public but has no width.
 *
 * The contract refuses `EXACT_OWN` on the far side of the board, which is the fair-information rule
 * doing its job. A test that wants to isolate one mechanic still needs the sixteen damage rolls to
 * be the only spread in the answer, so this states the stats as a public range whose ends coincide:
 * nothing is claimed that the AI is not allowed to know, and nothing else moves.
 */
internal fun publicExactStats(
    maxHp: Int,
    attack: Int,
    defence: Int,
    specialAttack: Int,
    specialDefence: Int,
    speed: Int,
) = BattleCombatStatRangesView(
    maxHp = BattleIntegerRange(maxHp, maxHp),
    attack = BattleIntegerRange(attack, attack),
    defence = BattleIntegerRange(defence, defence),
    specialAttack = BattleIntegerRange(specialAttack, specialAttack),
    specialDefence = BattleIntegerRange(specialDefence, specialDefence),
    speed = BattleIntegerRange(speed, speed),
    knowledge = BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
)
