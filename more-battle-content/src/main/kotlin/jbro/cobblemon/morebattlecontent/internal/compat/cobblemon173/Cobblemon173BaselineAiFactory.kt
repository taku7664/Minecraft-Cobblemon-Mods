package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.battles.model.ai.BattleAI
import com.cobblemon.mod.common.battles.ai.StrongBattleAI
import jbro.cobblemon.morebattlecontent.internal.ai.BattleAiSkillRange

internal object Cobblemon173BaselineAiFactory {
    fun create(skill: Int): BattleAI {
        require(skill in BattleAiSkillRange.supported) {
            "Cobblemon 1.7.3 trainer AI skill must be between " +
                "${BattleAiSkillRange.supported.first} and ${BattleAiSkillRange.supported.last}"
        }
        return StrongBattleAI(skill)
    }
}
