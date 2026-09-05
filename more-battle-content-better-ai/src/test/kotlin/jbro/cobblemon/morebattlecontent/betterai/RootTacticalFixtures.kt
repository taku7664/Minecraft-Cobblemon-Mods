package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import java.util.UUID

internal data class RootTacticalFixture(
    val id: String,
    val family: String,
    val context: BattleDecisionContext,
    val expectedAction: String? = null,
)

/** Synthetic public mechanics fixtures, not legal preset teams or independent battle-engine truth. */
internal object RootTacticalFixtures {
    private val ally = UUID(0, 901)
    private val opponent = UUID(0, 902)

    fun all(): List<RootTacticalFixture> = buildList {
        // Predeclared Cartesian grid: preserve every cell regardless of which scheduler wins.
        for (hp in listOf(0.4, 1.0)) for (power in listOf(60, 90, 120)) for (reply in listOf(30, 60, 90)) {
            add(make("setup_hp${(hp * 100).toInt()}_hit${power}_reply$reply", "SETUP_WINDOW",
                hp, 1.0, power, reply, 80, 100))
        }
        add(make("secure_finish", "CONTROL_FINISH", 1.0, 0.05, 80, 90, 120, 100, expected = "strike"))
        add(make("priority_survival", "CONTROL_SURVIVAL", 0.1, 0.05, 80, 120, 80, 120,
            priority = true, expected = "priority"))
    }

    private fun make(id: String, family: String, hp: Double, foeHp: Double, power: Int, replyPower: Int,
        speed: Int, foeSpeed: Int, priority: Boolean = false, expected: String? = null): RootTacticalFixture {
        val strike = move("strike", 0, power.toDouble())
        val setup = move("setup", 1, 0.0, setup = true)
        val own = listOfNotNull(strike, setup, if (priority) move("priority", 2, 40.0, priority = 1) else null)
        val reply = move("reply", 0, replyPower.toDouble(), side = BattleSide.OPPONENT)
        val state = BattleStateView(
            battleId = UUID.nameUUIDFromBytes(id.toByteArray()), format = BattleFormat.SINGLE, turn = 1,
            pokemon = listOf(pokemon(ally, BattleSide.ALLY, hp, speed, own),
                pokemon(opponent, BattleSide.OPPONENT, foeHp, foeSpeed, listOf(reply))),
            field = BattleFieldStateView.empty(), remainingPokemonBySide = mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1),
            observedEvents = emptyList(), inferences = emptyList())
        fun catalog(id: UUID, moves: List<BattleActionCandidate>, knowledge: BattlePublicMoveKnowledge) =
            BattlePokemonActionCatalogView(id, moves.map {
                BattlePublicMoveOptionView(requireNotNull(it.moveId), requireNotNull(it.moveDetails), knowledge)
            }, moveSetComplete = true)
        val context = BattleDecisionContext(requestId = UUID.nameUUIDFromBytes("request:$id".toByteArray()),
            state = state, candidates = own, deadlineEpochMillis = Long.MAX_VALUE,
            publicActionCatalog = BattlePublicActionCatalogView(listOf(
                catalog(ally, own, BattlePublicMoveKnowledge.EXACT_OWN),
                catalog(opponent, listOf(reply), BattlePublicMoveKnowledge.PUBLICLY_REVEALED))))
        return RootTacticalFixture(id, family, PublicBattleTacticalCalculator.calculate(context), expected)
    }

    private fun pokemon(id: UUID, side: BattleSide, hp: Double, speed: Int, moves: List<BattleActionCandidate>) =
        BattlePokemonStateView(battlePokemonId = id, side = side, activeSlot = 0, speciesId = "showdown:test", formId = null,
            level = 50, hpFraction = hp, statusId = null, statStages = emptyMap(),
            knownMoveIds = moves.map { requireNotNull(it.moveId) }.toSet(), knownAbilityId = null, knownHeldItemId = null,
            fainted = false, knownTypeIds = setOf("normal"),
            combatStats = BattleCombatStatRangesView(
                maxHp = BattleIntegerRange(200, 200), attack = BattleIntegerRange(120, 120),
                defence = BattleIntegerRange(100, 100), specialAttack = BattleIntegerRange(120, 120),
                specialDefence = BattleIntegerRange(100, 100), speed = BattleIntegerRange(speed, speed),
                knowledge = if (side == BattleSide.ALLY) BattleCombatStatKnowledge.EXACT_OWN
                    else BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE))

    private fun move(id: String, slot: Int, power: Double, priority: Int = 0,
        side: BattleSide = BattleSide.ALLY, setup: Boolean = false) = BattleActionCandidate(
        actionId = id, kind = BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = slot,
        moveId = if (setup) "cobblemon:swordsdance" else "fixture:$id",
        targets = if (setup) emptyList() else listOf(BattleTargetSlot(
            if (side == BattleSide.ALLY) BattleSide.OPPONENT else BattleSide.ALLY, 0)),
        moveDetails = BattleMoveCandidateView(typeId = "normal", power = power, accuracy = 100.0,
            damageCategory = if (setup) BattleMoveDamageCategory.STATUS else BattleMoveDamageCategory.PHYSICAL,
            priority = priority, currentPp = 10,
            targetPattern = if (setup) BattleMoveTargetPattern.SELF else BattleMoveTargetPattern.SELECTED_OPPONENT,
            effects = if (!setup) null else BattleMoveEffectsView(
                coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, scriptedBehavior = false,
                effects = listOf(BattleMoveEffectView(kind = BattleMoveEffectKind.STAT_STAGE,
                    target = BattleMoveEffectTarget.USER, probability = 1.0, statStages = mapOf("attack" to 2))))))
}
