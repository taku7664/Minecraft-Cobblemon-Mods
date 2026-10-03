package jbro.cobblemon.mcc.betterai.mechanics

import java.util.UUID
import jbro.cobblemon.mcc.betterai.engine.Js
import jbro.cobblemon.mcc.betterai.simulation.EngineRuntimeDex
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * What a damaging hit sets off once it has landed, for the search's projection: the target's item and ability
 * reacting to it (Air Balloon, Weakness Policy, Justified, Weak Armor, Stamina and the rest), Knock Off taking the
 * item, the attacker's knockout abilities (Moxie, Beast Boost) and Life Orb's recoil. Without these, hitting a
 * Weakness Policy holder was pure gain and a Knock Off left the target its Leftovers.
 */
internal object LocalAfterHitReactions {
    fun apply(
        before: BattleStateView,
        after: BattleStateView,
        actorId: UUID,
        targetId: UUID?,
        action: BattleActionCandidate,
        directDamage: Double,
        /** Once per move: false for every target of a spread move after the first. */
        userEffects: Boolean = true,
    ): BattleStateView {
        val details = action.moveDetails ?: return after
        if (details.damageCategory == BattleMoveDamageCategory.STATUS) return after
        var state = after
        val moveId = canonical(action.moveId)
        val moveType = canonical(details.typeId)
        val targetBefore = targetId?.let { id -> before.pokemon.firstOrNull { it.battlePokemonId == id } }
        val target = targetId?.let { id -> state.pokemon.firstOrNull { it.battlePokemonId == id } }
        if (target != null && targetBefore != null && directDamage > 0.0) {
            val alive = !target.fainted && target.hpFraction > 0.0
            val item = LocalPublicItemState.activeItemId(state, target)
            val ability = LocalPublicAbilityState.effectiveKnownAbility(state, target)
            if (alive) {
                if (item == AIR_BALLOON) state = setItem(state, target.battlePokemonId, "")
                if (weaknessPolicyActivates(state, target, action,
                        before.pokemon.firstOrNull { it.battlePokemonId == actorId })) {
                    state = LocalStatStageChange.apply(state, target.battlePokemonId, null, mapOf("attack" to 2, "special_attack" to 2))
                    state = setItem(state, target.battlePokemonId, "")
                }
                reactiveBoost(ability, moveType, details.damageCategory, targetBefore.hpFraction, target.hpFraction)
                    ?.let { state = LocalStatStageChange.apply(state, target.battlePokemonId, null, it) }
            }
            // Clear Smog resets its target's stat stages.
            if (alive && moveId == "clearsmog" && target.knownVolatileEffectIds.none { canonical(it) == "substitute" }) {
                state = state.copyState(pokemon = state.pokemon.map {
                    if (it.battlePokemonId == target.battlePokemonId) it.copyState(statStages = emptyMap()) else it
                })
            }
            // Knock Off removes an item it can take, whether or not the holder survives.
            val currentTarget = state.pokemon.first { it.battlePokemonId == target.battlePokemonId }
            val actorBefore = before.pokemon.firstOrNull { it.battlePokemonId == actorId }
            if (moveId == KNOCK_OFF && LocalPublicItemTransferRules.canRemove(state, currentTarget,
                    LocalPublicAbilityMechanics.ignoresTargetAbility(action, actorBefore, currentTarget, state))) {
                state = setItem(state, currentTarget.battlePokemonId, "")
            }
        }
        if (!userEffects) return state
        val actor = state.pokemon.firstOrNull { it.battlePokemonId == actorId } ?: return state
        if (actor.fainted || actor.hpFraction <= 0.0) return state
        val actorAbility = LocalPublicAbilityState.effectiveKnownAbility(state, actor)
        val knockedOut = targetBefore != null && !targetBefore.fainted && targetBefore.hpFraction > 0.0 &&
            target != null && (target.fainted || target.hpFraction <= 0.0)
        if (knockedOut) {
            knockoutBoost(actorAbility, actor)?.let { state = LocalStatStageChange.apply(state, actorId, null, it) }
        }
        if (directDamage > 0.0 && LocalPublicItemState.activeItemId(state, actor) == LIFE_ORB &&
            actorAbility != "magicguard" && !(actorAbility == "sheerforce" && LocalDamageAbilityModifiers.hasSecondaryEffect(details))
        ) {
            val current = state.pokemon.first { it.battlePokemonId == actorId }
            val hp = LocalHpArithmetic.change(current, current.hpFraction, -tick(current, 10)).coerceAtLeast(0.0)
            state = state.copyState(pokemon = state.pokemon.map {
                if (it.battlePokemonId == actorId) it.copyState(hpFraction = hp, fainted = hp <= 0.0) else it
            })
        }
        return state
    }

    /** Weakness Policy excludes fixed damage and damage callbacks even on a super-effective body hit. */
    fun weaknessPolicyActivates(
        state: BattleStateView,
        target: BattlePokemonStateView,
        action: BattleActionCandidate,
        actor: BattlePokemonStateView?,
    ): Boolean {
        if (LocalPublicItemState.activeItemId(state, target) != WEAKNESS_POLICY) return false
        val details = action.moveDetails ?: return false
        if (details.damageCategory == BattleMoveDamageCategory.STATUS || details.effects?.effects.orEmpty().any {
                it.kind == BattleMoveEffectKind.FIXED_DAMAGE_LEVEL || it.kind == BattleMoveEffectKind.FIXED_DAMAGE_VALUE
            }) return false
        val move = runCatching { EngineRuntimeDex.current().second.move(canonical(action.moveId)) }.getOrNull()
        if (move != null && (Js.truthy(move.data("damage")) || move.declares("damageCallback"))) return false
        val type = actor?.let { LocalPublicMoveDamageInputs.resolvedTypeId(action, it, state) } ?: details.typeId
        val chart = action.facts?.typeChartMultiplier ?: target.knownTypeIds.takeIf { it.isNotEmpty() }?.let {
            StandardTypeEffectiveness.multiplier(type, it, false)
        }
        return chart != null && chart > 1.0
    }

    private fun reactiveBoost(
        ability: String?,
        moveType: String,
        category: BattleMoveDamageCategory,
        hpBefore: Double,
        hpAfter: Double,
    ): Map<String, Int>? {
        val crossedHalf = hpBefore > 0.5 && hpAfter <= 0.5
        return when (ability) {
            "justified" -> if (moveType == "dark") mapOf("attack" to 1) else null
            "weakarmor" -> if (category == BattleMoveDamageCategory.PHYSICAL) mapOf("defence" to -1, "speed" to 2) else null
            "stamina" -> mapOf("defence" to 1)
            "steamengine" -> if (moveType == "fire" || moveType == "water") mapOf("speed" to 6) else null
            "watercompaction" -> if (moveType == "water") mapOf("defence" to 2) else null
            "thermalexchange" -> if (moveType == "fire") mapOf("attack" to 1) else null
            "rattled" -> if (moveType in setOf("bug", "dark", "ghost")) mapOf("speed" to 1) else null
            "berserk" -> if (crossedHalf) mapOf("special_attack" to 1) else null
            "angershell" -> if (crossedHalf) mapOf(
                "attack" to 1, "special_attack" to 1, "speed" to 1, "defence" to -1, "special_defence" to -1,
            ) else null
            else -> null
        }
    }

    private fun knockoutBoost(ability: String?, actor: BattlePokemonStateView): Map<String, Int>? = when (ability) {
        "moxie", "chillingneigh", "asoneglastrier" -> mapOf("attack" to 1)
        "grimneigh", "asonespectrier" -> mapOf("special_attack" to 1)
        "beastboost" -> highestStat(actor)?.let { mapOf(it to 1) }
        else -> null
    }

    private fun highestStat(actor: BattlePokemonStateView): String? {
        val stats = actor.combatStats ?: return null
        return listOf(
            "attack" to stats.attack.maximum,
            "defence" to stats.defence.maximum,
            "special_attack" to stats.specialAttack.maximum,
            "special_defence" to stats.specialDefence.maximum,
            "speed" to stats.speed.maximum,
        ).maxByOrNull { it.second }?.first
    }

    private fun setItem(state: BattleStateView, pokemonId: UUID, item: String?): BattleStateView =
        state.copyState(pokemon = state.pokemon.map {
            if (it.battlePokemonId == pokemonId) it.copyState(knownHeldItemId = item) else it
        })

    private fun tick(pokemon: BattlePokemonStateView, divisor: Int): Double {
        val maxHp = pokemon.combatStats?.maxHp
        if (maxHp == null || maxHp.minimum != maxHp.maximum) return 1.0 / divisor
        return (maxHp.minimum / divisor).coerceAtLeast(1).toDouble() / maxHp.minimum
    }

    private fun canonical(value: String?): String = value?.let(PublicIds::canonical).orEmpty()

    private const val AIR_BALLOON = "airballoon"
    private const val WEAKNESS_POLICY = "weaknesspolicy"
    private const val LIFE_ORB = "lifeorb"
    private const val KNOCK_OFF = "knockoff"
}
