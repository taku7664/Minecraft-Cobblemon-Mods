package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import jbro.cobblemon.morebattlecontent.api.ai.BattleIntegerRange

/**
 * Public Gen 9 effect-duration facts from the Showdown data bundled with Cobblemon 1.7.3.
 * A range preserves uncertainty from an unrevealed duration-extending item or ability.
 */
internal object Cobblemon173PublicEffectDurationKnowledge {
    fun weather(effectId: String): BattleIntegerRange? = when (effectId) {
        "raindance", "sunnyday", "sandstorm", "hail", "snow" -> turns(5, 8)
        else -> null
    }

    fun field(effectId: String, scope: FieldEffectScope): BattleIntegerRange? = when (scope) {
        FieldEffectScope.TERRAIN -> when (effectId) {
            "electricterrain", "grassyterrain", "mistyterrain", "psychicterrain" -> turns(5, 8)
            else -> null
        }

        FieldEffectScope.ROOM -> when (effectId) {
            "trickroom", "wonderroom", "magicroom" -> turns(5, 7)
            else -> null
        }

        FieldEffectScope.GLOBAL -> when (effectId) {
            "gravity" -> turns(5, 7)
            "fairylock" -> turns(2)
            "iondeluge" -> turns(1)
            "mudsport", "watersport" -> turns(5)
            else -> null
        }
    }

    fun side(effectId: String): BattleIntegerRange? = when (effectId) {
        "reflect", "lightscreen", "auroraveil" -> turns(5, 8)
        "safeguard" -> turns(5, 7)
        "tailwind" -> turns(4, 6)
        "mist", "luckychant" -> turns(5)
        "firepledge", "grasspledge", "waterpledge",
        "gmaxcannonade", "gmaxvinelash", "gmaxvolcalith", "gmaxwildfire" -> turns(4)
        "craftyshield", "matblock", "quickguard", "wideguard" -> turns(1)
        else -> null
    }

    private fun turns(exact: Int) = BattleIntegerRange(exact, exact)

    private fun turns(minimum: Int, maximum: Int) = BattleIntegerRange(minimum, maximum)
}
