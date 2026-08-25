package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleFractionRange
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveCandidateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectCoverage
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectTarget
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectView
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectsView
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveRequirementKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveRequirementView
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveTargetPattern
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalStallingProtectionRules

/** Shared declarative mechanics for complete-preset battle simulations. */
internal object LocalTacticalSimulationMoveLibrary {
    fun details(move: LocalTacticalSimulationMove): BattleMoveCandidateView {
        val id = canonical(move.id)
        val selfTarget = id in SELF_TARGET_MOVES || id in STALLING_PROTECTION_MOVES
        val sideTarget = id in SIDE_TARGET_MOVES
        return BattleMoveCandidateView(
            typeId = move.typeId,
            damageCategory = move.category,
            power = move.power,
            accuracy = move.accuracy,
            priority = move.priority,
            currentPp = move.pp,
            targetPattern = when {
                selfTarget -> BattleMoveTargetPattern.SELF
                sideTarget -> BattleMoveTargetPattern.SIDE
                else -> BattleMoveTargetPattern.SELECTED_OPPONENT
            },
            effects = effectsFor(id),
        )
    }

    private fun effectsFor(id: String): BattleMoveEffectsView? {
        val effects = when (id) {
            "slackoff", "recover", "roost", "moonlight", "morningsun", "softboiled" -> listOf(heal(0.5))
            "synthesis" -> listOf(heal(0.5))
            "swordsdance" -> listOf(stage(BattleMoveEffectTarget.USER, "attack" to 2))
            "nastyplot" -> listOf(stage(BattleMoveEffectTarget.USER, "special_attack" to 2))
            "bulkup" -> listOf(stage(BattleMoveEffectTarget.USER, "attack" to 1, "defense" to 1))
            "irondefense" -> listOf(stage(BattleMoveEffectTarget.USER, "defense" to 2))
            "agility", "rockpolish" -> listOf(stage(BattleMoveEffectTarget.USER, "speed" to 2))
            "coil" -> listOf(stage(BattleMoveEffectTarget.USER, "attack" to 1, "defense" to 1, "accuracy" to 1))
            "shellsmash" -> listOf(
                stage(
                    BattleMoveEffectTarget.USER,
                    "attack" to 2,
                    "special_attack" to 2,
                    "speed" to 2,
                    "defense" to -1,
                    "special_defense" to -1,
                ),
            )
            "calmmind" -> listOf(stage(BattleMoveEffectTarget.USER, "special_attack" to 1, "special_defense" to 1))
            "quiverdance" -> listOf(
                stage(BattleMoveEffectTarget.USER, "special_attack" to 1, "special_defense" to 1, "speed" to 1),
            )
            "dragondance" -> listOf(stage(BattleMoveEffectTarget.USER, "attack" to 1, "speed" to 1))
            "protect", "detect", "kingsshield", "spikyshield", "banefulbunker", "obstruct",
            "silktrap", "burningbulwark", "maxguard",
            -> listOf(effect(BattleMoveEffectKind.PROTECT_USER, BattleMoveEffectTarget.USER))
            "toxic" -> listOf(status("tox"))
            "stunspore", "thunderwave" -> listOf(status("par"))
            "spore" -> listOf(status("slp"))
            "gigadrain", "drainingkiss", "drainpunch" ->
                listOf(fraction(BattleMoveEffectKind.DRAIN_FRACTION, 0.5))
            "uturn", "flipturn", "voltswitch" ->
                listOf(effect(BattleMoveEffectKind.SWITCH_USER, BattleMoveEffectTarget.USER))
            "bravebird", "flareblitz", "wildcharge", "woodhammer", "wavecrash", "doubleedge" ->
                listOf(fraction(BattleMoveEffectKind.RECOIL_FRACTION, 1.0 / 3.0))
            "headsmash" -> listOf(fraction(BattleMoveEffectKind.RECOIL_FRACTION, 0.5))
            "explosion", "selfdestruct", "mistyexplosion" ->
                listOf(effect(BattleMoveEffectKind.SELF_DESTRUCT, BattleMoveEffectTarget.USER))
            "closecombat", "armorcannon" -> listOf(
                stage(BattleMoveEffectTarget.USER, "defense" to -1, "special_defense" to -1),
            )
            "dracometeor", "leafstorm", "overheat", "fleurcannon", "psychoboost" ->
                listOf(stage(BattleMoveEffectTarget.USER, "special_attack" to -2))
            "makeitrain" -> listOf(stage(BattleMoveEffectTarget.USER, "special_attack" to -1))
            "superpower" -> listOf(stage(BattleMoveEffectTarget.USER, "attack" to -1, "defense" to -1))
            "vcreate" -> listOf(
                stage(BattleMoveEffectTarget.USER, "defense" to -1, "special_defense" to -1, "speed" to -1),
            )
            "hammerarm" -> listOf(stage(BattleMoveEffectTarget.USER, "speed" to -1))
            "scald" -> listOf(status("brn", 0.30))
            "sludgebomb" -> listOf(status("psn", 0.30))
            "fierydance" -> listOf(stage(BattleMoveEffectTarget.USER, 0.50, "special_attack" to 1))
            "moonblast" -> listOf(stage(BattleMoveEffectTarget.SELECTED_TARGET, 0.30, "special_attack" to -1))
            "playrough" -> listOf(stage(BattleMoveEffectTarget.SELECTED_TARGET, 0.10, "attack" to -1))
            "saltcure" -> listOf(volatile("saltcure"))
            "stealthrock", "stickyweb", "spikes", "toxicspikes" -> listOf(sideCondition(id))
            else -> emptyList()
        }
        val requirements = if (id == "suckerpunch") {
            listOf(BattleMoveRequirementView(BattleMoveRequirementKind.TARGET_PENDING_DAMAGING_MOVE))
        } else {
            emptyList()
        }
        val mechanicFlags = buildSet {
            if (id in CONTACT_MOVES) add("contact")
            if (id in STALLING_PROTECTION_MOVES) add(LocalStallingProtectionRules.MECHANIC_FLAG)
        }
        if (effects.isEmpty() && mechanicFlags.isEmpty() && requirements.isEmpty()) return null
        return BattleMoveEffectsView(
            BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
            effects,
            scriptedBehavior = requirements.isNotEmpty() || id in STALLING_PROTECTION_MOVES,
            requirements = requirements,
            mechanicFlags = mechanicFlags,
        )
    }

    private fun heal(value: Double) = fraction(BattleMoveEffectKind.HEAL_FRACTION, value)

    private fun fraction(kind: BattleMoveEffectKind, value: Double) = BattleMoveEffectView(
        kind,
        BattleMoveEffectTarget.USER,
        fractionRange = BattleFractionRange(value, value),
    )

    private fun status(value: String, probability: Double = 1.0) = BattleMoveEffectView(
        BattleMoveEffectKind.STATUS,
        BattleMoveEffectTarget.SELECTED_TARGET,
        probability = probability,
        valueId = value,
    )

    private fun stage(target: BattleMoveEffectTarget, vararg stages: Pair<String, Int>) =
        stage(target, 1.0, *stages)

    private fun stage(
        target: BattleMoveEffectTarget,
        probability: Double,
        vararg stages: Pair<String, Int>,
    ) = BattleMoveEffectView(
        BattleMoveEffectKind.STAT_STAGE,
        target,
        probability = probability,
        statStages = mapOf(*stages),
    )

    private fun effect(kind: BattleMoveEffectKind, target: BattleMoveEffectTarget) =
        BattleMoveEffectView(kind, target, probability = 1.0)

    private fun volatile(value: String) = BattleMoveEffectView(
        BattleMoveEffectKind.VOLATILE_STATUS,
        BattleMoveEffectTarget.SELECTED_TARGET,
        probability = 1.0,
        valueId = value,
    )

    private fun sideCondition(value: String) = BattleMoveEffectView(
        BattleMoveEffectKind.SIDE_CONDITION,
        BattleMoveEffectTarget.TARGET_SIDE,
        probability = 1.0,
        valueId = value,
    )

    private fun canonical(value: String?) = value.orEmpty()
        .substringAfter(':')
        .lowercase()
        .filter(Char::isLetterOrDigit)

    private val SELF_TARGET_MOVES = setOf(
        "slackoff", "recover", "roost", "moonlight", "morningsun", "softboiled", "synthesis",
        "swordsdance", "nastyplot", "bulkup", "irondefense", "agility", "rockpolish", "coil",
        "shellsmash", "calmmind", "quiverdance", "dragondance",
    )
    private val SIDE_TARGET_MOVES = setOf("stealthrock", "stickyweb", "spikes", "toxicspikes")
    private val CONTACT_MOVES = setOf(
        "outrage", "bravebird", "bodypress", "uturn", "flipturn", "bugbite", "bulletpunch",
        "closecombat", "flareblitz", "extremespeed", "playrough", "shadowclaw", "shadowsneak",
        "wildcharge", "firefang", "knockoff", "woodhammer", "wavecrash", "doubleedge", "headsmash",
        "drainpunch", "hammerarm", "superpower", "vcreate",
    )
    private val STALLING_PROTECTION_MOVES = setOf(
        "protect", "detect", "kingsshield", "spikyshield", "banefulbunker", "obstruct",
        "silktrap", "burningbulwark", "maxguard",
    )
}
