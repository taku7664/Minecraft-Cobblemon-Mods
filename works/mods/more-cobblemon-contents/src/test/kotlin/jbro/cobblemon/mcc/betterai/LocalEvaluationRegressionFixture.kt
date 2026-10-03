package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.*

internal object LocalEvaluationRegressionFixture {
    fun mon(side: BattleSide, slot: Int?, type: String = "normal", hp: Double = 1.0,
        ability: String? = null, speed: Int = 100, moves: Set<String> = emptySet(),
        volatiles: Set<String> = emptySet(), attack: Int = 120) = BattlePokemonStateView(
        battlePokemonId = UUID.randomUUID(), side = side, activeSlot = slot,
        speciesId = "showdown:probe", formId = null, level = 50, hpFraction = hp, statusId = null,
        statStages = emptyMap(), knownMoveIds = moves, knownAbilityId = ability, knownHeldItemId = null,
        fainted = hp <= 0.0, knownTypeIds = setOf(type), knownVolatileEffectIds = volatiles,
        combatStats = BattleCombatStatRangesView(BattleIntegerRange(160, 160), BattleIntegerRange(attack, attack),
            BattleIntegerRange(100, 100), BattleIntegerRange(120, 120), BattleIntegerRange(100, 100),
            BattleIntegerRange(speed, speed), BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE),
    )
    fun state(vararg pokemon: BattlePokemonStateView, format: BattleFormat = BattleFormat.SINGLE) = BattleStateView(
        UUID.randomUUID(), format, 3, pokemon.toList(), BattleFieldStateView.empty(),
        BattleSide.entries.associateWith { side -> pokemon.count { it.side == side && !it.fainted } },
        emptyList(), emptyList(),
    )
    fun attack(id: String = "tackle", type: String = "normal", power: Double = 80.0, slot: Int = 0,
        accuracy: Double = 100.0, effects: BattleMoveEffectsView? = null,
        pattern: BattleMoveTargetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT) = BattleActionCandidate(
        actionId = "$id:$slot", kind = BattleActionKind.USE_MOVE, actorSlot = slot, moveSlot = 0,
        moveId = "cobblemon:$id", targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
        moveDetails = BattleMoveCandidateView(type, BattleMoveDamageCategory.PHYSICAL, power, accuracy, 0, 10,
            pattern, effects = effects),
    )
    fun context(state: BattleStateView, vararg candidates: BattleActionCandidate) = BattleDecisionContext(
        requestId = UUID.randomUUID(), state = state, candidates = candidates.toList(),
        deadlineEpochMillis = Long.MAX_VALUE, memory = BattleTacticalMemoryView.empty(),
        publicActionCatalog = BattlePublicActionCatalogView(emptyList()),
    )
}
