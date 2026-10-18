package jbro.cobblemon.mcc.internal.ai

/**
 * What both set-level attack scores ([BattleOpponentMoveExpectationScorer] for the move inference,
 * `LocalMoveThreatScore` for the engine's preview worlds) do with an attack that only works under a condition, so the
 * two pick the same attacks (2026-10-18). Read from the move data, except the four named below.
 *
 * Steel Roller (terrain), Dream Eater (a sleeping target), Last Resort and Snore fail unless their condition holds.
 * Focus Punch (fails if its user is hit first), Shell Trap (unless it is hit by contact), Synchronoise (unless the
 * target shares a type) and Belch (until a Berry is eaten) fail in Showdown callbacks their data does not show: none of
 * them is an attack a set is assumed to carry. With STAB always inferred, an ADVANCED Lucario's Fighting STAB read as
 * Focus Punch (150 power), a Ferrothorn's Steel STAB as Steel Roller and a Gallade's Psychic STAB as Synchronoise. A delayed hit (Future Sight), a first-turn move (Fake Out) and one that needs
 * the target to attack (Sucker Punch) count half. Conditions that only add (Blizzard's weather accuracy, Poltergeist's
 * item) are left alone.
 */
internal object BattleMoveSetExpectation {
    /** The share of an attack's value a set is assumed to get from it: 0 for one it cannot count on. */
    fun factor(moveId: String?, move: BattleMoveCandidateView): Double {
        if (moveId != null && PublicIds.canonical(moveId) in FAILS_BY_SCRIPT) return 0.0
        val effects = move.effects ?: return 1.0
        if (effects.requirements.any { it.kind in FAILS_OTHERWISE }) return 0.0
        var factor = 1.0
        if (effects.mechanicFlags.any { PublicIds.canonical(it) == FUTURE_MOVE }) factor *= CONDITIONAL
        if (effects.effects.any { it.kind == BattleMoveEffectKind.FIRST_ACTIVE_TURN_ONLY }) factor *= CONDITIONAL
        if (effects.requirements.any { it.kind == BattleMoveRequirementKind.TARGET_PENDING_DAMAGING_MOVE }) factor *= CONDITIONAL
        return factor
    }

    /**
     * The type an attack has when [pokemon] uses it: Tera Blast takes a publicly Terastallized user's Tera type, so it is
     * that type's STAB (**hardcoded** by move id, as `LocalPublicMoveDamageInputs.resolvedTypeId` does; user-approved).
     */
    fun typeId(moveId: String?, move: BattleMoveCandidateView, pokemon: BattlePokemonStateView): String {
        val tera = pokemon.knownTeraTypeId
        return if (tera != null && moveId != null && PublicIds.canonical(moveId) == TERA_BLAST) tera else move.typeId
    }

    /**
     * The expected hits of a multi-hit attack: Showdown draws 2-5 hits as 2, 3, 4, 5 at 7:7:3:3 (3.1 on average,
     * `BattleActions.hitStepMoveHitLoop`), any other range evenly.
     */
    fun averageHits(range: BattleIntegerRange): Double =
        if (range.minimum == 2 && range.maximum == 5) TWO_TO_FIVE_HITS else (range.minimum + range.maximum) / 2.0

    private const val TWO_TO_FIVE_HITS = (2 * 7 + 3 * 7 + 4 * 3 + 5 * 3) / 20.0

    private val FAILS_OTHERWISE = setOf(
        BattleMoveRequirementKind.TERRAIN_PRESENT,
        BattleMoveRequirementKind.TARGET_STATUS_ANY_OF,
        BattleMoveRequirementKind.OTHER_MOVES_USED,
        BattleMoveRequirementKind.USER_STATUS_ANY_OF,
    )
    /**
     * **Hardcoded** (user-approved 2026-10-18): no condition in their data, Showdown fails them in a callback. Found by
     * reading every damaging move's callbacks in the server's `data/moves.js`, Past moves included (Cobblemon learnsets
     * still teach them).
     */
    private val FAILS_BY_SCRIPT = setOf("focuspunch", "shelltrap", "synchronoise", "belch")
    private const val TERA_BLAST = "terablast"
    private const val FUTURE_MOVE = "futuremove"
    private const val CONDITIONAL = 0.5
}
