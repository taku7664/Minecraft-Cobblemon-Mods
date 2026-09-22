package jbro.cobblemon.morebattlecontent.betterai.mechanics

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*

internal data class LocalAppliedDirectHit(
    val state: BattleStateView,
    /** Damage attributed to the move itself. Drain, recoil, and contact reactions use this value. */
    val directDamageFraction: Double,
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
    ): LocalAppliedDirectHit {
        val target = state.pokemon.firstOrNull { it.battlePokemonId == targetId }
        val targetResolution = target?.let {
            resolveTarget(state, it, incomingDamageFraction.coerceAtMost(it.hpFraction), ignoreTargetAbility)
        }
        val directDamage = targetResolution?.directDamageFraction ?: 0.0
        val actor = state.pokemon.firstOrNull { it.battlePokemonId == actorId }
        val fixedHealing = effects.filter {
            it.kind == BattleMoveEffectKind.HEAL_FRACTION &&
                it.target == BattleMoveEffectTarget.USER && it.fractionRange != null
        }.sumOf { midpoint(requireNotNull(it.fractionRange)) * (it.probability ?: 1.0) }
        val drainHealing = effects.filter {
            it.kind == BattleMoveEffectKind.DRAIN_FRACTION &&
                it.target == BattleMoveEffectTarget.USER && it.fractionRange != null
        }.sumOf { LocalDamageHpTransfer.fraction(directDamage, midpoint(requireNotNull(it.fractionRange)), actor, target) * (it.probability ?: 1.0) }
        val damageRecoil = effects.filter {
            it.kind == BattleMoveEffectKind.RECOIL_FRACTION &&
                it.target == BattleMoveEffectTarget.USER && it.fractionRange != null
        }.sumOf { LocalDamageHpTransfer.fraction(directDamage, midpoint(requireNotNull(it.fractionRange)), actor, target) * (it.probability ?: 1.0) }
        val maxHpRecoil = effects.filter {
            it.kind == BattleMoveEffectKind.MAX_HP_RECOIL || it.kind == BattleMoveEffectKind.STRUGGLE_RECOIL
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
                        (pokemon.hpFraction + fixedHealing + drainHealing - damageRecoil - maxHpRecoil)
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
        return LocalAppliedDirectHit(copyState(state, nextPokemon), directDamage)
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

        val sashReady = !magicRoomActive(state) && canonical(target.knownHeldItemId) == "focussash" &&
            target.hpFraction >= FULL_HP_EPSILON && incomingDamage >= target.hpFraction
        val sturdyReady = !ignoreTargetAbility &&
            LocalPublicAbilityState.effectiveKnownAbility(state, target) == "sturdy" &&
            target.hpFraction >= FULL_HP_EPSILON && incomingDamage >= target.hpFraction
        if (sturdyReady || sashReady) {
            val oneHp = oneHpFraction(target)
            return TargetResolution(
                pokemon = copyPokemon(
                    target,
                    hpFraction = oneHp,
                    // Sturdy's damage callback precedes Sash, so the item is not consumed.
                    knownHeldItemId = if (sturdyReady) target.knownHeldItemId else null,
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
        val berryHealing = pinchBerryHealing(state, target, hp)
        if (berryHealing > 0.0) {
            val healed = LocalHpArithmetic.change(target, hp, berryHealing).coerceAtMost(1.0)
            return TargetResolution(
                pokemon = copyPokemon(target, hpFraction = healed, knownHeldItemId = null, fainted = false),
                directDamageFraction = incomingDamage,
            )
        }
        return TargetResolution(
            pokemon = copyPokemon(target, hpFraction = hp, fainted = hp <= 0.0),
            directDamageFraction = incomingDamage,
        )
    }

    /**
     * What a revealed pinch berry restores, given the health the hit left behind.
     *
     * Nothing is restored to a fainted Pokemon, and nothing to one still above the threshold: the berry
     * is a reaction to being brought low, not a passive heal. Only a revealed item counts, which for
     * the opponent means one that has already been seen to fire.
     */
    private fun pinchBerryHealing(
        state: BattleStateView,
        target: BattlePokemonStateView,
        healthAfterHit: Double,
    ): Double {
        if (healthAfterHit <= 0.0) return 0.0
        if (magicRoomActive(state)) return 0.0
        val item = canonical(target.knownHeldItemId)
        val fraction = if (item == "oranberry") {
            // Oran heals ten absolute HP, not ten percent. Missing public HP units cannot
            // establish the resulting fraction; a public range uses the existing midpoint model.
            val maxHp = target.combatStats?.maxHp ?: return 0.0
            10.0 / ((maxHp.minimum.toDouble() + maxHp.maximum) / 2.0)
        } else PINCH_BERRY_HEALING[item] ?: return 0.0
        val threshold = if (item == "oranberry" || item == "sitrusberry" || canonical(target.knownAbilityId) == "gluttony") {
            HALF_HP_BERRY_THRESHOLD
        } else QUARTER_HP_BERRY_THRESHOLD
        if (target.hpFraction <= threshold) return 0.0
        if (healthAfterHit > threshold) return 0.0
        val maxHp = target.combatStats?.maxHp
        if (item != "oranberry" && maxHp != null && maxHp.minimum == maxHp.maximum) {
            // Native heal truncates fractional HP, with a minimum of one for positive healing.
            return kotlin.math.floor(maxHp.minimum * fraction).coerceAtLeast(1.0) / maxHp.minimum
        }
        return fraction
    }

    private fun magicRoomActive(state: BattleStateView): Boolean =
        state.field.roomEffects.any { canonical(it.effectId) == "magicroom" }

    /** Fractional recovery amounts; activation thresholds are separate from the amount healed. */
    private val PINCH_BERRY_HEALING = mapOf(
        "sitrusberry" to 0.25,
        "figyberry" to 1.0 / 3.0,
        "wikiberry" to 1.0 / 3.0,
        "magoberry" to 1.0 / 3.0,
        "aguavberry" to 1.0 / 3.0,
        "iapapaberry" to 1.0 / 3.0,
    )
    private const val HALF_HP_BERRY_THRESHOLD = 0.5
    private const val QUARTER_HP_BERRY_THRESHOLD = 0.25

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
        knownVolatileEffectIds = if (fainted) emptySet() else pokemon.knownVolatileEffectIds,
    )

    private fun midpoint(range: BattleFractionRange): Double = (range.minimum + range.maximum) / 2.0

    private fun canonical(value: String?): String? = value
        ?.substringAfter(':')
        ?.lowercase()
        ?.filter(Char::isLetterOrDigit)

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
}
