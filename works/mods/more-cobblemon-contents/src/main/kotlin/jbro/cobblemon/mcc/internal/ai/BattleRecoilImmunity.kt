package jbro.cobblemon.mcc.internal.ai

/**
 * Showdown's recoil exemptions: Rock Head and Magic Guard stop recoil from damage dealt and Chloroblast's half of
 * maximum HP, Magic Guard alone stops Mind Blown and Steel Beam's, and nothing stops Struggle's or an HP cost like
 * Belly Drum's. Abilities come canonical (`rockhead`). Shared by the battle mechanics and both set-level attack scores,
 * so a Rock Head user's Head Smash is not assumed away for a recoil it never takes (Codex review of be4e9824).
 */
internal object BattleRecoilImmunity {
    fun blocksDamageRecoil(ability: String?): Boolean = ability == ROCK_HEAD || ability == MAGIC_GUARD

    fun blocksMaxHpRecoil(effect: BattleMoveEffectView, ability: String?): Boolean = when (effect.valueId) {
        BattleDeclarativeMoveEffects.MIND_BLOWN_RECOIL -> ability == MAGIC_GUARD
        BattleDeclarativeMoveEffects.MAX_HP_DAMAGE_RECOIL -> blocksDamageRecoil(ability)
        else -> false
    }

    /** Magic Guard also stops the half of maximum HP a missed High Jump Kick costs. */
    fun blocksCrashRecoil(ability: String?): Boolean = ability == MAGIC_GUARD

    /** Whether [effect], a recoil of any kind, costs a user with [ability] nothing. */
    fun blocks(effect: BattleMoveEffectView, ability: String?): Boolean = when (effect.kind) {
        BattleMoveEffectKind.RECOIL_FRACTION -> blocksDamageRecoil(ability)
        BattleMoveEffectKind.MAX_HP_RECOIL -> blocksMaxHpRecoil(effect, ability)
        BattleMoveEffectKind.CRASH_RECOIL -> blocksCrashRecoil(ability)
        else -> false
    }

    private const val ROCK_HEAD = "rockhead"
    private const val MAGIC_GUARD = "magicguard"
}
