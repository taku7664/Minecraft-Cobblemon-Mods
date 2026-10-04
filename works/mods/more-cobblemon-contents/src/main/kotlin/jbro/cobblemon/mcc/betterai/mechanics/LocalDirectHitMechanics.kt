package jbro.cobblemon.mcc.betterai.mechanics

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.*

internal data class LocalAppliedDirectHit(
    val state: BattleStateView,
    /** Damage attributed to the move itself. Drain, recoil, and contact reactions use this value. */
    val directDamageFraction: Double,
    val recoilHpFraction: Double,
)

/** Resolves one damaging hit, including one-hit survival and disguise consumption. */
internal object LocalDirectHitMechanics {
    fun apply(
        state: BattleStateView,
        actorId: UUID,
        targetId: UUID?,
        incomingDamageFraction: Double,
        effects: List<BattleMoveEffectView>,
        ignoreTargetAbility: Boolean,
        /** A sound move or an Infiltrator reaches past a Substitute. */
        bypassesSubstitute: Boolean = false,
        /** Move execution defers Update until its onHit and DamagingHit callbacks finish. */
        updateItems: Boolean = true,
    ): LocalAppliedDirectHit {
        val target = state.pokemon.firstOrNull { it.battlePokemonId == targetId }
        val targetResolution = target?.let {
            substituteAbsorbs(it, actorId, incomingDamageFraction, bypassesSubstitute)
                ?: resolveTarget(state, it, incomingDamageFraction.coerceAtMost(it.hpFraction), ignoreTargetAbility)
        }
        val directDamage = targetResolution?.directDamageFraction ?: 0.0
        val actor = state.pokemon.firstOrNull { it.battlePokemonId == actorId }
        val fixedHealing = effects.filter {
            it.kind == BattleMoveEffectKind.HEAL_FRACTION &&
                it.target == BattleMoveEffectTarget.USER && it.fractionRange != null
        }.sumOf { midpoint(requireNotNull(it.fractionRange)) * (it.probability ?: 1.0) }
        val healBlocked = actor?.knownVolatileEffectIds?.any { canonical(it) == "healblock" } == true
        val drainHealing = effects.filter {
            it.kind == BattleMoveEffectKind.DRAIN_FRACTION &&
                it.target == BattleMoveEffectTarget.USER && it.fractionRange != null
        }.sumOf { LocalDamageHpTransfer.fraction(directDamage, midpoint(requireNotNull(it.fractionRange)), actor, target) * (it.probability ?: 1.0) }
        val actorAbility = actor?.let { LocalPublicAbilityState.effectiveKnownAbility(state, it) }
        val damageRecoil = effects.filter {
            it.kind == BattleMoveEffectKind.RECOIL_FRACTION && !LocalRecoilImmunity.blocksDamageRecoil(actorAbility) &&
                it.target == BattleMoveEffectTarget.USER && it.fractionRange != null
        }.sumOf { LocalDamageHpTransfer.fraction(directDamage, midpoint(requireNotNull(it.fractionRange)), actor, target) * (it.probability ?: 1.0) }
        val maxHpRecoil = effects.filter {
            it.kind == BattleMoveEffectKind.MAX_HP_RECOIL && !LocalRecoilImmunity.blocksMaxHpRecoil(it, actorAbility) ||
                it.kind == BattleMoveEffectKind.STRUGGLE_RECOIL
        }.sumOf { effect -> effect.fractionRange?.let(::midpoint)?.times(effect.probability ?: 1.0) ?: 0.0 }
        val selfDestructs = effects.any {
            it.kind == BattleMoveEffectKind.SELF_DESTRUCT && (it.probability ?: 1.0) == 1.0
        }
        val stealsStages = effects.any { it.kind == BattleMoveEffectKind.STEALS_STAT_STAGES }
        val stolenStages = if (stealsStages) {
            target?.statStages.orEmpty().filterValues { it > 0 }
        } else {
            emptyMap()
        }
        val thawsTarget = effects.any { it.kind == BattleMoveEffectKind.THAWS_TARGET }
        val nextPokemon = state.pokemon.map { pokemon ->
            when (pokemon.battlePokemonId) {
                actorId -> {
                    val hp = if (selfDestructs) 0.0 else {
                        (pokemon.hpFraction + (if (healBlocked) 0.0 else fixedHealing + drainHealing) - damageRecoil - maxHpRecoil)
                            .coerceIn(0.0, 1.0)
                    }
                    val stages = stolenStages.entries.fold(pokemon.statStages) { current, (stat, amount) ->
                        current + (stat to ((current[stat] ?: 0) + amount).coerceIn(-6, 6))
                    }
                    copyPokemon(pokemon, hpFraction = hp, statStages = stages, fainted = hp <= 0.0)
                }
                targetId -> {
                    val resolved = requireNotNull(targetResolution).pokemon
                    copyPokemon(
                        resolved,
                        statusId = if (thawsTarget && canonical(resolved.statusId) in FREEZE_STATUSES) null else resolved.statusId,
                        statStages = if (stealsStages) {
                            resolved.statStages.mapValues { (_, value) -> if (value > 0) 0 else value }
                        } else {
                            resolved.statStages
                        },
                    )
                }
                else -> pokemon
            }
        }
        val actualRecoil = actor?.takeUnless { selfDestructs }?.let {
            val beforeRecoil = (it.hpFraction + fixedHealing + drainHealing).coerceIn(0.0, 1.0)
            val afterRecoil = (it.hpFraction + fixedHealing + drainHealing - damageRecoil - maxHpRecoil)
                .coerceIn(0.0, 1.0)
            beforeRecoil - afterRecoil
        } ?: 0.0
        val raw = copyState(state, nextPokemon)
        return LocalAppliedDirectHit(if (updateItems) LocalBerryMechanics.afterUpdate(raw) else raw, directDamage, actualRecoil)
    }

    /**
     * Newly created decoys retain their actual remaining HP across attacks in this branch.
     * An older observed decoy does not expose its remaining HP.
     */
    private fun substituteAbsorbs(
        target: BattlePokemonStateView,
        actorId: UUID,
        incomingDamage: Double,
        bypassed: Boolean,
    ): TargetResolution? {
        if (bypassed || incomingDamage <= 0.0 || target.battlePokemonId == actorId) return null
        if (target.knownVolatileEffectIds.none { canonical(it) == SUBSTITUTE }) return null
        return TargetResolution(
            pokemon = LocalPersistentMoveState.damageSubstitute(target, incomingDamage),
            directDamageFraction = 0.0,
        )
    }

    private fun resolveTarget(
        state: BattleStateView,
        target: BattlePokemonStateView,
        incomingDamage: Double,
        ignoreTargetAbility: Boolean,
    ): TargetResolution {
        val disguiseReady = !ignoreTargetAbility &&
            LocalPublicAbilityState.effectiveKnownAbility(state, target) == "disguise" &&
            canonical(target.speciesId) in MIMIKYU_SPECIES &&
            !canonical(target.formId).orEmpty().contains("busted") &&
            incomingDamage > 0.0
        if (disguiseReady) {
            val hp = (target.hpFraction - DISGUISE_HP_LOSS).coerceAtLeast(0.0)
            return TargetResolution(
                pokemon = copyPokemon(
                    target,
                    hpFraction = hp,
                    formId = BUSTED_MIMIKYU_FORM,
                    fainted = hp <= 0.0,
                ),
                directDamageFraction = 0.0,
            )
        }

        val sashReady = LocalPublicItemState.activeItemId(state, target) == "focussash" &&
            target.hpFraction >= FULL_HP_EPSILON && incomingDamage >= target.hpFraction
        val sturdyReady = !ignoreTargetAbility &&
            LocalPublicAbilityState.effectiveKnownAbility(state, target) == "sturdy" &&
            target.hpFraction >= FULL_HP_EPSILON && incomingDamage >= target.hpFraction ||
            // Endure leaves its user on one HP from any hit this turn.
            target.knownVolatileEffectIds.any { canonical(it) == "endure" } && incomingDamage >= target.hpFraction &&
            target.hpFraction > oneHpFraction(target)
        if (sturdyReady || sashReady) {
            val oneHp = oneHpFraction(target)
            return TargetResolution(
                pokemon = copyPokemon(
                    target,
                    hpFraction = oneHp,
                    // Sturdy's damage callback precedes Sash, so the item is not consumed.
                    knownHeldItemId = if (sturdyReady) target.knownHeldItemId else "",
                    fainted = false,
                ),
                directDamageFraction = (target.hpFraction - oneHp).coerceAtLeast(0.0),
            )
        }

        val hp = LocalHpArithmetic.change(target, target.hpFraction, -incomingDamage).coerceAtLeast(0.0)
        // A pinch berry fires the moment the hit lands, so it belongs here beside the Sash rather than
        // with the end-of-turn residuals. It does not save anything from a knockout - it only triggers
        // on a survivor - but it moves the health a second attack has to get through, and 394 of the
        // battle tower's sets carry one. The AI holds them itself, where the item is never hidden, so
        // this is mostly the trainer learning that it can afford the turn it was refusing to take.
        return TargetResolution(
            pokemon = copyPokemon(target, hpFraction = hp, fainted = hp <= 0.0),
            directDamageFraction = incomingDamage,
        )
    }

    private fun oneHpFraction(target: BattlePokemonStateView): Double {
        val maxHp = target.combatStats?.maxHp?.maximum?.coerceAtLeast(1) ?: return DEFAULT_ONE_HP_FRACTION
        return 1.0 / maxHp
    }

    private fun copyState(state: BattleStateView, pokemon: List<BattlePokemonStateView>) = BattleStateView(
        battleId = state.battleId,
        format = state.format,
        turn = state.turn,
        pokemon = pokemon,
        field = state.field,
        remainingPokemonBySide = BattleSide.entries.associateWith { side ->
            val oldLiving = state.pokemon.count { it.side == side && !it.fainted && it.hpFraction > 0.0 }
            val newLiving = pokemon.count { it.side == side && !it.fainted && it.hpFraction > 0.0 }
            (state.remainingPokemonBySide.getValue(side) + newLiving - oldLiving).coerceAtLeast(0)
        },
        observedEvents = state.observedEvents,
        inferences = state.inferences,
    )

    private fun copyPokemon(
        pokemon: BattlePokemonStateView,
        hpFraction: Double = pokemon.hpFraction,
        formId: String? = pokemon.formId,
        knownHeldItemId: String? = pokemon.knownHeldItemId,
        statusId: String? = pokemon.statusId,
        statStages: Map<String, Int> = pokemon.statStages,
        fainted: Boolean = pokemon.fainted,
    ) = BattlePokemonStateView(
        battlePokemonId = pokemon.battlePokemonId,
        side = pokemon.side,
        activeSlot = pokemon.activeSlot,
        speciesId = pokemon.speciesId,
        formId = formId,
        level = pokemon.level,
        hpFraction = hpFraction,
        statusId = statusId,
        statStages = statStages,
        knownMoveIds = pokemon.knownMoveIds,
        knownAbilityId = pokemon.knownAbilityId,
        knownHeldItemId = knownHeldItemId,
        fainted = fainted,
        knownTypeIds = pokemon.knownTypeIds,
        combatStats = pokemon.combatStats,
        knownFormStates = pokemon.knownFormStates,
        actionConstraints = pokemon.actionConstraints,
        knownVolatileEffectIds = if (fainted) pokemon.knownVolatileEffectIds.filterTo(linkedSetOf()) { it.startsWith(LocalBerryMechanics.LAST_CONSUMED_ITEM) } else pokemon.knownVolatileEffectIds,
        knownBaseStabTypeIds = pokemon.knownBaseStabTypeIds,
        knownTeraTypeId = pokemon.knownTeraTypeId,
        knownStellarBoostedTypeIds = pokemon.knownStellarBoostedTypeIds,
        knownBaseAbilityId = pokemon.knownBaseAbilityId,
    )

    private fun midpoint(range: BattleFractionRange): Double = (range.minimum + range.maximum) / 2.0

    private fun canonical(value: String?): String? = value?.let(PublicIds::canonical)

    private data class TargetResolution(
        val pokemon: BattlePokemonStateView,
        val directDamageFraction: Double,
    )

    private val MIMIKYU_SPECIES = setOf("mimikyu", "mimikyutotem")
    private val FREEZE_STATUSES = setOf("frz", "freeze", "frozen")
    private const val BUSTED_MIMIKYU_FORM = "cobblemon:mimikyu-busted"
    private const val DISGUISE_HP_LOSS = 1.0 / 8.0
    private const val FULL_HP_EPSILON = 1.0 - 1e-9
    private const val DEFAULT_ONE_HP_FRACTION = 1e-6
    private const val SUBSTITUTE = "substitute"
}

/**
 * Showdown's recoil exemptions: Rock Head and Magic Guard stop recoil from damage dealt, Magic Guard alone stops
 * Mind Blown and Steel Beam's half of maximum HP, and nothing stops Struggle's or an HP cost like Belly Drum's.
 */
internal object LocalRecoilImmunity {
    fun blocksDamageRecoil(ability: String?): Boolean = ability == "rockhead" || ability == "magicguard"

    fun blocksMaxHpRecoil(effect: BattleMoveEffectView, ability: String?): Boolean =
        ability == "magicguard" && effect.valueId == BattleDeclarativeMoveEffects.MIND_BLOWN_RECOIL
}
